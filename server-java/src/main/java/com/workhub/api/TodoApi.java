package com.workhub.api;

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

import java.util.List;
import java.util.Map;

/**
 * 任务待办。完成状态切换也走 PUT，由前端传 status / done_at。
 */
@RestController
@RequestMapping("/api/todos")
public class TodoApi {

    private static final List<String> FIELDS =
            List.of("title", "detail", "priority", "due_date", "status", "done_at");

    private final Db db;
    private final Repo repo;

    public TodoApi(Db db, Repo repo) {
        this.db = db;
        this.repo = repo;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String limit) {
        Ctx.AuthUser user = Ctx.requireUser();
        return db.all("SELECT * FROM todos WHERE owner_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?",
                user.id(), Values.intOr(limit, 500));
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        if (Values.isBlank(b.get("title"))) {
            throw ApiError.badRequest("请填写待办内容");
        }
        Ctx.AuthUser user = Ctx.requireUser();
        return repo.insert("todos", user.id(), b, FIELDS);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Ctx.AuthUser user = Ctx.requireUser();
        Map<String, Object> row = repo.update("todos", user.id(), id,
                body == null ? Map.of() : body, FIELDS);
        if (row == null) {
            throw ApiError.notFound("待办不存在");
        }
        return row;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        Ctx.AuthUser user = Ctx.requireUser();
        repo.delete("todos", user.id(), id);
        return Repo.map("ok", true);
    }
}
