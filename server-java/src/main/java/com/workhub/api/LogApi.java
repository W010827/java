package com.workhub.api;

import com.workhub.core.Clock;
import com.workhub.core.Ctx;
import com.workhub.core.Db;
import com.workhub.core.Repo;
import com.workhub.core.Values;
import com.workhub.web.ApiError;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作记录：按天记事，支持标题/正文/标签关键词搜索。
 */
@RestController
@RequestMapping("/api/logs")
public class LogApi {

    private static final List<String> FIELDS = List.of("log_date", "title", "content", "tags");

    private final Db db;
    private final Repo repo;

    public LogApi(Db db, Repo repo) {
        this.db = db;
        this.repo = repo;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String limit) {
        Ctx.AuthUser user = Ctx.requireUser();
        StringBuilder sql = new StringBuilder("SELECT * FROM work_logs WHERE owner_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(user.id());

        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (title LIKE ? OR content LIKE ? OR tags LIKE ?)");
            String pattern = Repo.likeKeyword(keyword);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        sql.append(" ORDER BY log_date DESC, id DESC LIMIT ?");
        params.add(Values.intOr(limit, 300));
        return db.all(sql.toString(), params.toArray());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        // 没指定日期就记到服务器当天（注意：这里用服务器日期，不是浏览器日期）
        if (Values.isBlank(b.get("log_date"))) {
            b.put("log_date", Clock.todayIso());
        }
        Ctx.AuthUser user = Ctx.requireUser();
        return repo.insert("work_logs", user.id(), b, FIELDS);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Ctx.AuthUser user = Ctx.requireUser();
        Map<String, Object> row = repo.update("work_logs", user.id(), id,
                body == null ? Map.of() : body, FIELDS);
        if (row == null) {
            throw ApiError.notFound("记录不存在");
        }
        return row;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        Ctx.AuthUser user = Ctx.requireUser();
        repo.delete("work_logs", user.id(), id);
        return Repo.map("ok", true);
    }
}
