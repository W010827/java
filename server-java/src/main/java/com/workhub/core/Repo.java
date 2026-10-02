package com.workhub.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用数据访问。业务表的 CRUD 形态完全一致，收在这里，各接口只写自己的校验和查询条件。
 *
 * <p>所有方法都带 {@code owner_id} 条件，业务层拿不到不属于自己的数据。
 */
@Component
public class Repo {

    /** 允许为 NULL 的字段，其余字段缺省时用空串或 0 填充 */
    private static final Set<String> NULLABLE_FIELDS = Set.of("due_date", "done_at");
    /** 需要按整数处理的字段 */
    private static final Set<String> INT_FIELDS = Set.of("file_size", "download_count");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Db db;

    public Repo(Db db) {
        this.db = db;
    }

    /** 按 id 取一行，同样限定 owner。等价 Python 的 {@code get_by_id()}。 */
    public Map<String, Object> byId(String table, String owner, long id) {
        return db.one("SELECT * FROM " + table + " WHERE id = ? AND owner_id = ?", id, owner);
    }

    /**
     * 插入一行并返回完整记录。
     *
     * <p><b>只写入 {@code data} 里出现过的字段</b>，没出现的交给列上的 {@code DEFAULT}
     * 兜底 —— 这与 SQLite 版语义完全一致，是必须守住的契约。
     *
     * <p>曾经这里写成"没提供也显式填空串"，结果把 {@code todos.status} 的
     * {@code DEFAULT 'pending'}、{@code todos.priority} 的 {@code DEFAULT 'normal'}
     * 覆盖成了空串（前端不传就变成非法状态）。列默认值只在"该列未出现在 INSERT 语句里"
     * 时才生效，显式写入 {@code ''} 会直接顶掉它。
     *
     * <p>之所以敢依赖列默认值：走本方法的四张表（software_packages / work_logs /
     * todos / documents）里，所有 {@code NOT NULL} 列都写了 {@code DEFAULT}，
     * 而 TEXT 列一律允许 NULL（MySQL 5.7 不允许 TEXT 有默认值）。
     * 唯一 {@code NOT NULL} 且无 DEFAULT 的 {@code daily_reports.report_date}
     * 不走本方法，由 ReportApi 自己 upsert 并显式传值。
     */
    public Map<String, Object> insert(String table, String owner, Map<String, Object> data,
                                      List<String> fields) {
        List<String> cols = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        cols.add("owner_id");
        vals.add(owner);
        for (String f : fields) {
            if (data.containsKey(f)) {
                cols.add(f);
                vals.add(norm(f, data.get(f)));
            }
        }
        String ts = Clock.nowIso();
        cols.add("created_at");
        cols.add("updated_at");
        vals.add(ts);
        vals.add(ts);

        String placeholders = String.join(",", java.util.Collections.nCopies(cols.size(), "?"));
        String sql = "INSERT INTO " + table + " (" + String.join(",", cols) + ") VALUES (" + placeholders + ")";
        long id = db.insert(sql, vals.toArray());
        return byId(table, owner, id);
    }

    /** 局部更新，返回更新后的记录；id 不存在或不属于 owner 时返回 null。 */
    public Map<String, Object> update(String table, String owner, long id,
                                      Map<String, Object> patch, List<String> fields) {
        List<String> sets = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (String f : fields) {
            if (patch.containsKey(f)) {
                sets.add(f + " = ?");
                vals.add(norm(f, patch.get(f)));
            }
        }
        if (sets.isEmpty()) {
            // 没有可更新字段时直接返回原记录，且不动 updated_at —— 与 Python 版一致
            return byId(table, owner, id);
        }
        sets.add("updated_at = ?");
        vals.add(Clock.nowIso());
        vals.add(id);
        vals.add(owner);

        String sql = "UPDATE " + table + " SET " + String.join(",", sets)
                + " WHERE id = ? AND owner_id = ?";
        db.exec(sql, vals.toArray());
        return byId(table, owner, id);
    }

    /** 删除一行，返回是否真的删掉了。 */
    public boolean delete(String table, String owner, long id) {
        int rows = db.exec("DELETE FROM " + table + " WHERE id = ? AND owner_id = ?", id, owner);
        return rows > 0;
    }

    /** 计数 +1（下载次数），返回更新后的记录。 */
    public Map<String, Object> bumpCounter(String table, String owner, long id, String field) {
        db.exec("UPDATE " + table + " SET " + field + " = " + field
                + " + 1 WHERE id = ? AND owner_id = ?", id, owner);
        return byId(table, owner, id);
    }

    /**
     * 字段归一化，与 Python 版 {@code norm_value()} 行为保持一致：
     * <ul>
     *   <li>null → 可空字段保留 null，整型字段给 0，其余给空串</li>
     *   <li>整型字段：能解析就解析，解析不了给 0（不报错）</li>
     *   <li>数组 → 逗号拼接；对象 → JSON 字符串</li>
     * </ul>
     */
    public static Object norm(String field, Object value) {
        if (value == null) {
            if (NULLABLE_FIELDS.contains(field)) {
                return null;
            }
            return INT_FIELDS.contains(field) ? 0 : "";
        }
        if (value instanceof Boolean b) {
            value = b ? 1 : 0;
        }
        if (INT_FIELDS.contains(field)) {
            if (value instanceof Number n) {
                return n.intValue();
            }
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        if (value instanceof Number n) {
            return String.valueOf(n);
        }
        if (value instanceof Collection<?> c) {
            List<String> parts = new ArrayList<>(c.size());
            for (Object item : c) {
                parts.add(String.valueOf(item));
            }
            return String.join(",", parts);
        }
        if (value instanceof Map<?, ?>) {
            try {
                return JSON.writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    /** 组装一个可变的有序 Map（JSON 字段顺序与 Python 版一致，便于比对） */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /**
     * LIKE 关键词处理：先把用户输入里的通配符 {% _ \} 换成空格，再拼成 %kw%。
     * 等价 Python 的 {@code like_keyword()}，避免用户输入的 % 变成"匹配任意内容"。
     */
    public static String likeKeyword(String keyword) {
        return "%" + keyword.replaceAll("[%_\\\\]", " ").trim() + "%";
    }
}
