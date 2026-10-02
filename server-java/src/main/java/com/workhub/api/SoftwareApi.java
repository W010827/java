package com.workhub.api;

import com.workhub.core.Ctx;
import com.workhub.core.Db;
import com.workhub.core.Repo;
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
 * 软件仓库：安装包的上架、检索、编辑、删除与下载计数。
 */
@RestController
@RequestMapping("/api/software")
public class SoftwareApi {

    private static final List<String> FIELDS = List.of(
            "name", "version", "platform", "category", "description",
            "file_path", "file_name", "file_size", "download_count");

    private final Db db;
    private final Repo repo;

    public SoftwareApi(Db db, Repo repo) {
        this.db = db;
        this.repo = repo;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String category) {
        Ctx.AuthUser user = Ctx.requireUser();
        StringBuilder sql = new StringBuilder("SELECT * FROM software_packages WHERE owner_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(user.id());

        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (name LIKE ? OR description LIKE ? OR category LIKE ?)");
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
        Object name = b.get("name");
        if (name == null || String.valueOf(name).trim().isEmpty()) {
            throw ApiError.badRequest("请填写软件名称");
        }
        Ctx.AuthUser user = Ctx.requireUser();
        return repo.insert("software_packages", user.id(), b, FIELDS);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Ctx.AuthUser user = Ctx.requireUser();
        Map<String, Object> row = repo.update("software_packages", user.id(), id,
                body == null ? Map.of() : body, FIELDS);
        if (row == null) {
            throw ApiError.notFound("记录不存在");
        }
        return row;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        Ctx.AuthUser user = Ctx.requireUser();
        if (repo.byId("software_packages", user.id(), id) == null) {
            throw ApiError.notFound("记录不存在");
        }
        repo.delete("software_packages", user.id(), id);
        return Repo.map("ok", true);
    }

    /** 下载计数 +1（真正的文件下载走 /api/download）。 */
    @PostMapping("/{id}/download")
    public Map<String, Object> countDownload(@PathVariable long id) {
        Ctx.AuthUser user = Ctx.requireUser();
        Map<String, Object> row = repo.bumpCounter("software_packages", user.id(), id, "download_count");
        if (row == null) {
            throw ApiError.notFound("记录不存在");
        }
        return row;
    }
}
