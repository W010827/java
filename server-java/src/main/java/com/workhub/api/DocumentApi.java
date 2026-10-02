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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文件资料库：归档文档，带分类、标签与备注。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentApi {

    private static final List<String> FIELDS = List.of(
            "title", "file_path", "file_name", "file_size", "mime_type",
            "category", "tags", "note", "download_count");

    private final Db db;
    private final Repo repo;

    public DocumentApi(Db db, Repo repo) {
        this.db = db;
        this.repo = repo;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String category) {
        Ctx.AuthUser user = Ctx.requireUser();
        StringBuilder sql = new StringBuilder("SELECT * FROM documents WHERE owner_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(user.id());

        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (title LIKE ? OR file_name LIKE ? OR tags LIKE ?)");
            String pattern = Repo.likeKeyword(keyword);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        if (category != null && !category.isEmpty()) {
            sql.append(" AND category = ?");
            params.add(category);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT 500");
        return db.all(sql.toString(), params.toArray());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        if (Values.isBlank(b.get("title"))) {
            throw ApiError.badRequest("请填写资料标题");
        }
        Ctx.AuthUser user = Ctx.requireUser();
        return repo.insert("documents", user.id(), b, FIELDS);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Ctx.AuthUser user = Ctx.requireUser();
        Map<String, Object> row = repo.update("documents", user.id(), id,
                body == null ? Map.of() : body, FIELDS);
        if (row == null) {
            throw ApiError.notFound("记录不存在");
        }
        return row;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        Ctx.AuthUser user = Ctx.requireUser();
        repo.delete("documents", user.id(), id);
        return Repo.map("ok", true);
    }

    /** 下载计数 +1（真正的文件下载走 /api/download）。 */
    @PostMapping("/{id}/download")
    public Map<String, Object> countDownload(@PathVariable long id) {
        Ctx.AuthUser user = Ctx.requireUser();
        Map<String, Object> row = repo.bumpCounter("documents", user.id(), id, "download_count");
        if (row == null) {
            throw ApiError.notFound("记录不存在");
        }
        return row;
    }
}
