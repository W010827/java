package com.workhub.api;

import com.workhub.core.AppProps;
import com.workhub.core.Clock;
import com.workhub.core.Crypto;
import com.workhub.core.Ctx;
import com.workhub.core.Db;
import com.workhub.core.Repo;
import com.workhub.core.Storage;
import com.workhub.core.Values;
import com.workhub.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * 文件上传 / 下载 / 分享链接 / 删除 / 健康检查。
 *
 * <p>上传是<b>流式</b>的：边读请求体边写盘，不把整个文件读进内存，所以没有体积上限
 * （只受磁盘空间限制）。这与 Python 版行为一致，也是能传几百 MB 安装包的原因。
 */
@RestController
@RequestMapping("/api")
public class FileApi {

    private final Db db;
    private final Storage storage;
    private final AppProps props;

    public FileApi(Db db, Storage storage, AppProps props) {
        this.db = db;
        this.storage = storage;
        this.props = props;
    }

    /** 上传：请求体就是文件的原始字节，文件名和目录通过 query 参数传。 */
    @PostMapping("/upload")
    public Map<String, Object> upload(HttpServletRequest request,
                                      @RequestParam(defaultValue = "misc") String folder,
                                      @RequestParam(defaultValue = "file") String name) {
        Ctx.AuthUser user = Ctx.requireUser();
        long length = request.getContentLengthLong();
        if (length <= 0) {
            throw ApiError.badRequest("没有收到文件内容");
        }

        String rel = storage.makeRelPath(folder, name);
        Path dest = storage.resolve(user.id(), rel);
        Path tmp = dest.resolveSibling(dest.getFileName().toString() + ".part");

        long written = 0;
        try {
            Files.createDirectories(dest.getParent());
            try (InputStream in = request.getInputStream();
                 OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buffer = new byte[AppProps.CHUNK];
                long remaining = length;
                while (remaining > 0) {
                    int want = (int) Math.min(buffer.length, remaining);
                    int read = in.read(buffer, 0, want);
                    if (read < 0) {
                        throw ApiError.badRequest("上传中断，文件不完整");
                    }
                    out.write(buffer, 0, read);
                    remaining -= read;
                    written += read;
                }
            }
            if (written != length) {
                throw ApiError.badRequest("上传中断，文件不完整");
            }
            // 先写 .part 再原子改名：中途失败不会留下半个"看起来正常"的文件
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (ApiError e) {
            cleanup(tmp);
            throw e;
        } catch (IOException | RuntimeException e) {
            cleanup(tmp);
            throw new ApiError(500, "internal", "文件写入失败：" + e.getMessage());
        }

        String mimeType = MediaTypeFactory.getMediaType(name)
                .map(MediaType::toString)
                .orElse("application/octet-stream");

        return Repo.map(
                "path", rel,
                "name", name,
                "size", written,
                "mime_type", mimeType);
    }

    /** 下载。鉴权方式二选一：登录 token，或分享链接签名（?u=&e=&s=）。 */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam(required = false) String u,
                                             @RequestParam(required = false) String e,
                                             @RequestParam(required = false) String s,
                                             @RequestParam(required = false) String path,
                                             @RequestParam(required = false) String filename) {
        Ctx.AuthUser user = resolveDownloadUser(u, e, s, path);
        if (path == null || path.isEmpty()) {
            throw ApiError.badRequest("缺少文件路径参数");
        }
        Path full = storage.resolve(user.id(), path);
        if (!Files.isRegularFile(full)) {
            throw ApiError.notFound("文件不存在或已被删除");
        }
        String downloadName = (filename == null || filename.isEmpty())
                ? full.getFileName().toString()
                : filename;
        return fileResponse(full, downloadName);
    }

    /** 生成带签名的临时下载地址。 */
    @GetMapping("/share")
    public Map<String, Object> share(@RequestParam(required = false) String path,
                                     @RequestParam(required = false) String ttl) {
        Ctx.AuthUser user = Ctx.requireUser();
        if (path == null || path.isEmpty()) {
            throw ApiError.badRequest("缺少文件路径参数");
        }
        Path full = storage.resolve(user.id(), path);
        if (!Files.isRegularFile(full)) {
            throw ApiError.notFound("文件不存在或已被删除");
        }
        int seconds = Values.intOr(ttl, AppProps.SHARE_TTL_SECONDS);
        seconds = Math.min(Math.max(seconds, 60), 7 * 24 * 3600);
        long expiresAt = Clock.epochSeconds() + seconds;
        String signature = storage.signShare(user.id(), path, expiresAt);

        String url = "/api/download?u=" + urlQuote(user.id())
                + "&e=" + expiresAt
                + "&s=" + signature
                + "&path=" + urlQuote(path);

        return Repo.map(
                "url", url,
                "path", path,
                "expires_at", expiresAt,
                "ttl", seconds);
    }

    /** 删除文件（可一次多个 path 参数）。删不掉不算错，返回实际删除数量。 */
    @DeleteMapping("/files")
    public Map<String, Object> deleteFiles(@RequestParam(name = "path", required = false) List<String> paths) {
        Ctx.AuthUser user = Ctx.requireUser();
        int removed = 0;
        if (paths != null) {
            for (String rel : paths) {
                Path full = storage.resolve(user.id(), rel);
                if (Files.isRegularFile(full)) {
                    try {
                        Files.delete(full);
                        removed++;
                    } catch (IOException ignored) {
                        // 单个文件删不掉不影响其它文件
                    }
                }
            }
        }
        return Repo.map("removed", removed);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Repo.map(
                "ok", true,
                "time", Clock.nowIso(),
                "storage", props.baseDir().toString().replace('\\', '/'),
                "static", props.staticDir().toString().replace('\\', '/'),
                "version", AppProps.VERSION);
    }

    /** 下载鉴权：先看登录态，再看分享链接签名。 */
    private Ctx.AuthUser resolveDownloadUser(String u, String e, String s, String path) {
        Ctx.AuthUser user = Ctx.user();
        if (user != null) {
            return user;
        }
        boolean hasSignature = u != null && !u.isEmpty()
                && e != null && !e.isEmpty()
                && s != null && !s.isEmpty();
        if (!hasSignature) {
            throw ApiError.unauthorized("请先登录后再下载");
        }

        long expiresAt;
        try {
            expiresAt = Long.parseLong(e);
        } catch (NumberFormatException ex) {
            throw ApiError.badRequest("分享链接参数不合法");
        }
        if (expiresAt < Clock.epochSeconds()) {
            throw new ApiError(410, "expired", "分享链接已过期，请重新获取");
        }
        String expected = storage.signShare(u, path, expiresAt);
        if (!Crypto.constantTimeEquals(s, expected)) {
            throw new ApiError(403, "permission-denied", "分享链接无效");
        }
        Map<String, Object> row = db.one(
                "SELECT id, email, created_at FROM users WHERE id = ?", u);
        if (row == null) {
            throw new ApiError(403, "permission-denied", "分享链接无效");
        }
        return new Ctx.AuthUser(
                Values.str(row.get("id")),
                Values.str(row.get("email")),
                Values.str(row.get("created_at")));
    }

    /** 组装文件响应：类型、长度、下载文件名、缓存策略。 */
    private ResponseEntity<Resource> fileResponse(Path file, String downloadName) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException ex) {
            throw ApiError.notFound("文件不存在或已被删除");
        }

        String ctype = MediaTypeFactory.getMediaType(file.getFileName().toString())
                .map(MediaType::toString)
                .orElse("application/octet-stream");
        if (ctype.startsWith("text/")
                || ctype.equals("application/javascript")
                || ctype.equals("application/json")) {
            ctype += "; charset=utf-8";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, ctype);
        headers.setContentLength(size);
        headers.set("Accept-Ranges", "none");

        if (downloadName != null && !downloadName.isEmpty()) {
            // 下载：ASCII 名给老浏览器，filename* 给现代浏览器（中文名靠它）
            String ascii = downloadName.replaceAll("[^0-9A-Za-z._\\-]", "_");
            if (ascii.isEmpty()) {
                ascii = "download";
            }
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + urlQuote(downloadName));
            headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        } else if (file.toString().replace('\\', '/').contains("/assets/")) {
            // 带内容哈希的构建产物可以长缓存
            headers.set(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");
        } else {
            headers.set(HttpHeaders.CACHE_CONTROL, "no-cache");
        }

        return new ResponseEntity<>(new FileSystemResource(file), headers, HttpStatus.OK);
    }

    /**
     * URL 编码，但保留斜杠。
     *
     * <p>Tomcat 默认拒绝路径里出现 %2F（编码后的斜杠），而文件路径必然带斜杠，
     * 所以这里按 Python {@code urllib.parse.quote} 的语义处理：斜杠不编码。
     */
    private static String urlQuote(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/")
                .replace("%2f", "/");
    }

    private static void cleanup(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // 清理失败不影响主流程，残留的 .part 文件不影响业务数据
        }
    }
}
