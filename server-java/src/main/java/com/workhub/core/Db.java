package com.workhub.core;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据访问（MySQL）。
 *
 * <p>建表语句全部带 {@code IF NOT EXISTS}，所以指向已存在的库时不会改动任何数据。
 *
 * <p>两点与 SQLite 不同的取舍，都是为了让前端契约一个字都不用改：
 * <ul>
 *   <li><b>时间字段用 VARCHAR 存 ISO 字符串</b>（{@code 2026-10-02T15:38:01}）而不是 DATETIME。
 *       换成 DATETIME 后 JDBC 会返回 {@code Timestamp}，Jackson 会序列化成 epoch 毫秒或别的格式，
 *       {@code created_at} 的类型就变了；VARCHAR 的字典序天然等于时间序，排序行为也不变。</li>
 *   <li><b>长文本用 TEXT 且不允许有默认值</b>——MySQL 5.7 会拒绝
 *       {@code TEXT NOT NULL DEFAULT ''}（错误 1101）。默认值由应用层补齐，见
 *       {@link Repo#insert}。</li>
 * </ul>
 */
@Component
public class Db {

    private static final Logger log = LoggerFactory.getLogger(Db.class);

    /** 所有业务表统一的存储引擎与字符集（utf8mb4 才能存 emoji；5.7 没有 utf8mb4_0900_ai_ci） */
    private static final String TAIL = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci";

    /**
     * 建表语句（MySQL 5.7 / 8.0 通用语法）。
     *
     * <p>注意索引写在 CREATE TABLE 里面：MySQL 5.7 不支持
     * {@code CREATE INDEX IF NOT EXISTS}（那是 8.0.29 才有的），单独建索引会让
     * 二次启动直接报错。
     */
    private static final String[] SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS users (
              id         VARCHAR(64)  NOT NULL COMMENT '用户 id（uuid hex）',
              email      VARCHAR(255) NOT NULL COMMENT '登录邮箱，全局唯一',
              pwd_salt   VARCHAR(64)  NOT NULL,
              pwd_hash   VARCHAR(128) NOT NULL,
              created_at VARCHAR(32)  NOT NULL,
              PRIMARY KEY (id),
              UNIQUE KEY uk_users_email (email)
            )""" + TAIL,
            """
            CREATE TABLE IF NOT EXISTS sessions (
              token      VARCHAR(64) NOT NULL COMMENT '登录令牌',
              user_id    VARCHAR(64) NOT NULL,
              created_at VARCHAR(32) NOT NULL,
              expires_at VARCHAR(32) NOT NULL,
              PRIMARY KEY (token),
              KEY idx_sessions_user (user_id)
            )""" + TAIL,
            """
            CREATE TABLE IF NOT EXISTS software_packages (
              id             BIGINT       NOT NULL AUTO_INCREMENT,
              owner_id       VARCHAR(64)  NOT NULL,
              name           VARCHAR(255) NOT NULL DEFAULT '',
              version        VARCHAR(64)  NOT NULL DEFAULT '',
              platform       VARCHAR(128) NOT NULL DEFAULT '',
              category       VARCHAR(128) NOT NULL DEFAULT '',
              description    TEXT         NULL,
              file_path      VARCHAR(512) NOT NULL DEFAULT '',
              file_name      VARCHAR(512) NOT NULL DEFAULT '',
              file_size      BIGINT       NOT NULL DEFAULT 0,
              download_count BIGINT       NOT NULL DEFAULT 0,
              created_at     VARCHAR(32)  NOT NULL,
              updated_at     VARCHAR(32)  NOT NULL,
              PRIMARY KEY (id),
              KEY idx_sw_owner (owner_id, created_at DESC)
            )""" + TAIL,
            """
            CREATE TABLE IF NOT EXISTS daily_reports (
              id            BIGINT      NOT NULL AUTO_INCREMENT,
              owner_id      VARCHAR(64) NOT NULL,
              report_date   VARCHAR(16) NOT NULL COMMENT 'YYYY-MM-DD',
              done_today    TEXT        NULL,
              plan_tomorrow TEXT        NULL,
              issues        TEXT        NULL,
              created_at    VARCHAR(32) NOT NULL,
              updated_at    VARCHAR(32) NOT NULL,
              PRIMARY KEY (id),
              UNIQUE KEY uk_report_owner_date (owner_id, report_date)
            )""" + TAIL,
            """
            CREATE TABLE IF NOT EXISTS work_logs (
              id         BIGINT       NOT NULL AUTO_INCREMENT,
              owner_id   VARCHAR(64)  NOT NULL,
              log_date   VARCHAR(16)  NOT NULL DEFAULT '' COMMENT 'YYYY-MM-DD',
              title      VARCHAR(255) NOT NULL DEFAULT '',
              content    TEXT         NULL,
              tags       VARCHAR(1024) NOT NULL DEFAULT '',
              created_at VARCHAR(32)  NOT NULL,
              updated_at VARCHAR(32)  NOT NULL,
              PRIMARY KEY (id),
              KEY idx_log_owner (owner_id, log_date DESC, id DESC)
            )""" + TAIL,
            """
            CREATE TABLE IF NOT EXISTS todos (
              id         BIGINT       NOT NULL AUTO_INCREMENT,
              owner_id   VARCHAR(64)  NOT NULL,
              title      VARCHAR(512) NOT NULL DEFAULT '',
              detail     TEXT         NULL,
              priority   VARCHAR(32)  NOT NULL DEFAULT 'normal',
              due_date   VARCHAR(32)  NULL,
              status     VARCHAR(32)  NOT NULL DEFAULT 'pending',
              done_at    VARCHAR(32)  NULL,
              created_at VARCHAR(32)  NOT NULL,
              updated_at VARCHAR(32)  NOT NULL,
              PRIMARY KEY (id),
              KEY idx_todo_owner (owner_id, created_at DESC)
            )""" + TAIL,
            """
            CREATE TABLE IF NOT EXISTS documents (
              id             BIGINT        NOT NULL AUTO_INCREMENT,
              owner_id       VARCHAR(64)   NOT NULL,
              title          VARCHAR(512)  NOT NULL DEFAULT '',
              file_path      VARCHAR(512)  NOT NULL DEFAULT '',
              file_name      VARCHAR(512)  NOT NULL DEFAULT '',
              file_size      BIGINT        NOT NULL DEFAULT 0,
              mime_type      VARCHAR(128)  NOT NULL DEFAULT '',
              category       VARCHAR(128)  NOT NULL DEFAULT '',
              tags           VARCHAR(1024) NOT NULL DEFAULT '',
              note           TEXT          NULL,
              download_count BIGINT        NOT NULL DEFAULT 0,
              created_at     VARCHAR(32)   NOT NULL,
              updated_at     VARCHAR(32)   NOT NULL,
              PRIMARY KEY (id),
              KEY idx_doc_owner (owner_id, created_at DESC)
            )""" + TAIL,
    };

    private static final RowMapper<Map<String, Object>> MAPPER = (rs, rowNum) -> {
        ResultSetMetaData md = rs.getMetaData();
        int count = md.getColumnCount();
        Map<String, Object> row = new LinkedHashMap<>(count * 2);
        for (int i = 1; i <= count; i++) {
            row.put(md.getColumnLabel(i), rs.getObject(i));
        }
        return row;
    };

    private final JdbcTemplate jdbc;

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        alignDatabaseCharset();
        for (String sql : SCHEMA) {
            jdbc.execute(sql);
        }
        log.info("数据库已就绪：{} 张表", SCHEMA.length);
    }

    /**
     * 把库的默认字符集对齐到 utf8mb4。
     *
     * <p>连接串里带了 {@code createDatabaseIfNotExist=true}，库若由驱动顺手创建，
     * 字符集会跟着服务器的默认值走（本机 my.ini 是 utf8，三字节，存不了 emoji）。
     * 表本身已经显式声明了 utf8mb4，这一步只是把库的默认值也拉齐。
     * 权限不足时只告警不中断——不影响表的使用。
     */
    private void alignDatabaseCharset() {
        try {
            String db = jdbc.queryForObject("SELECT DATABASE()", String.class);
            if (db != null && !db.isEmpty()) {
                jdbc.execute("ALTER DATABASE `" + db
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            }
        } catch (Exception e) {
            log.warn("库默认字符集未对齐（不影响使用，表已各自声明 utf8mb4）：{}", e.getMessage());
        }
    }

    /** 查一行，无结果返回 null（不是抛异常，调用方依赖这个语义）。 */
    public Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.query(sql, MAPPER, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查多行。 */
    public List<Map<String, Object>> all(String sql, Object... args) {
        return jdbc.query(sql, MAPPER, args);
    }

    /** 写入类语句（UPDATE / DELETE），返回影响行数。 */
    public int exec(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    /**
     * 批量写入，整批在一个事务里，要么全成要么全不成。
     *
     * <p>SQLite 版这里是「同一把锁内顺序执行」；迁到 MySQL 后改用事务，
     * 语义等价（原子性）但不再阻塞读。
     */
    public void execBatch(List<SqlAndArgs> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        jdbc.execute((ConnectionCallback<Void>) con -> {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                for (SqlAndArgs item : batch) {
                    try (PreparedStatement ps = con.prepareStatement(item.sql())) {
                        List<Object> args = item.args();
                        for (int i = 0; i < args.size(); i++) {
                            ps.setObject(i + 1, args.get(i));
                        }
                        ps.executeUpdate();
                    }
                }
                con.commit();
            } catch (Exception e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(auto);
            }
            return null;
        });
    }

    /** 插入并返回自增主键。 */
    public long insert(String sql, Object... args) {
        return jdbc.execute((ConnectionCallback<Long>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /** 供健康检查用：确认连接可用。 */
    public boolean ping() {
        try (Connection con = jdbc.getDataSource().getConnection()) {
            return con.isValid(3);
        } catch (Exception e) {
            return false;
        }
    }

    public record SqlAndArgs(String sql, List<Object> args) {
        public static SqlAndArgs of(String sql, Object... args) {
            List<Object> list = new ArrayList<>(args.length);
            for (Object a : args) {
                list.add(a);
            }
            return new SqlAndArgs(sql, list);
        }
    }
}
