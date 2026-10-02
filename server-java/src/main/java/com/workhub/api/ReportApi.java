package com.workhub.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workhub.core.Clock;
import com.workhub.core.Ctx;
import com.workhub.core.Db;
import com.workhub.core.Repo;
import com.workhub.core.Values;
import com.workhub.web.ApiError;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工作日报：一个日期一条，重复保存即为更新（upsert）。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportApi {

    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final Db db;
    private final Repo repo;
    private final ObjectMapper json;

    public ReportApi(Db db, Repo repo, ObjectMapper json) {
        this.db = db;
        this.repo = repo;
        this.json = json;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(required = false) String limit) {
        Ctx.AuthUser user = Ctx.requireUser();
        StringBuilder sql = new StringBuilder("SELECT * FROM daily_reports WHERE owner_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(user.id());

        if (from != null && !from.isEmpty()) {
            sql.append(" AND report_date >= ?");
            params.add(from);
        }
        if (to != null && !to.isEmpty()) {
            sql.append(" AND report_date <= ?");
            params.add(to);
        }
        sql.append(" ORDER BY report_date DESC LIMIT ?");
        params.add(Values.intOr(limit, 300));
        return db.all(sql.toString(), params.toArray());
    }

    /**
     * 按日期取当天日报。
     *
     * <p>当天没有日报时<b>必须返回裸 null</b>（而不是空对象），前端据此区分「新建」与「编辑」。
     *
     * <p>注意这里刻意返回 {@code String} 而不是 {@code Map}：Spring MVC 遇到 {@code null}
     * 返回值时会「当作没有内容」，写出 200 + 空 body；而 Python 版写的是字面量 {@code null}。
     * 前端 {@code request()} 对空 body 恰好也会得到 null，看着一样，但任何直接
     * {@code JSON.parse(res.text())} 的客户端都会炸。这里自己序列化，保证两版字节一致。
     */
    @GetMapping(value = "/one", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getByDate(@RequestParam(required = false) String date) throws JsonProcessingException {
        Ctx.AuthUser user = Ctx.requireUser();
        return json.writeValueAsString(db.one(
                "SELECT * FROM daily_reports WHERE owner_id = ? AND report_date = ?",
                user.id(), date));
    }

    @PostMapping
    public Map<String, Object> save(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String date = Values.trimmed(b.get("report_date"));
        if (!DATE.matcher(date).matches()) {
            throw ApiError.badRequest("请选择日报日期");
        }
        Ctx.AuthUser user = Ctx.requireUser();
        String ts = Clock.nowIso();

        // 一个日期一条：撞上 uk_report_owner_date 就转为更新（MySQL 的 upsert 写法）。
        // created_at 刻意不更新——保留首次创建时间，与 SQLite 版行为一致。
        db.exec("INSERT INTO daily_reports "
                        + "(owner_id, report_date, done_today, plan_tomorrow, issues, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE "
                        + "done_today = VALUES(done_today), "
                        + "plan_tomorrow = VALUES(plan_tomorrow), "
                        + "issues = VALUES(issues), "
                        + "updated_at = VALUES(updated_at)",
                user.id(), date,
                Repo.norm("done_today", b.get("done_today")),
                Repo.norm("plan_tomorrow", b.get("plan_tomorrow")),
                Repo.norm("issues", b.get("issues")),
                ts, ts);

        return db.one("SELECT * FROM daily_reports WHERE owner_id = ? AND report_date = ?",
                user.id(), date);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        Ctx.AuthUser user = Ctx.requireUser();
        repo.delete("daily_reports", user.id(), id);
        return Repo.map("ok", true);
    }
}
