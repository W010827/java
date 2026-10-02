package com.workhub.web;

import com.workhub.core.AppProps;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 前端静态资源。
 *
 * <p>生产部署时前端由后端一起提供（页面与接口同源，不需要配跨域）。
 * 本地开发走 Vite 开发服务器，用不到这里。
 *
 * <p>两条规则与 Python 版一致：
 * <ul>
 *   <li>带扩展名但文件不存在 → 404</li>
 *   <li>不带扩展名 → 回退到 index.html（前端路由交给浏览器处理）</li>
 * </ul>
 */
@Controller
public class StaticResourceController {

    private final AppProps props;

    public StaticResourceController(AppProps props) {
        this.props = props;
    }

    @GetMapping("/**")
    public ResponseEntity<Resource> serve(HttpServletRequest request) {
        String path = UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8);

        // 走到这里的 /api/** 说明没有对应接口，保持与 Python 版一致的错误文案
        if (path.startsWith("/api/")) {
            throw new ApiError(404, "not-found", "接口不存在：" + request.getMethod() + " " + path);
        }

        if (path.isEmpty() || path.equals("/")) {
            path = "/index.html";
        }

        Path root = props.staticDir().toAbsolutePath().normalize();
        String relative = path.startsWith("/") ? path.substring(1) : path;
        Path full = root.resolve(relative).normalize();

        // 目录穿越防护
        if (!full.startsWith(root)) {
            throw new ApiError(403, "permission-denied", "非法路径");
        }
        if (Files.isDirectory(full)) {
            full = full.resolve("index.html");
        }
        if (!Files.exists(full)) {
            // 没有任何扩展名才回退到首页，避免把缺失的图片/CSS 也返回成 HTML
            if (hasExtension(relative)) {
                throw ApiError.notFound("资源不存在");
            }
            Path index = root.resolve("index.html");
            if (!Files.exists(index)) {
                throw ApiError.notFound("前端尚未部署");
            }
            full = index;
        }

        return fileResponse(full);
    }

    private ResponseEntity<Resource> fileResponse(Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (Exception e) {
            throw ApiError.notFound("资源不存在");
        }

        String ctype = MediaTypeFactory.getMediaType(file.getFileName().toString())
                .map(MediaType::toString)
                .orElse("application/octet-stream");
        if (ctype.startsWith("text/")
                || ctype.equals("application/javascript")
                || ctype.equals("application/json")) {
            ctype += "; charset=utf-8";
        }

        boolean hashed = file.toString().replace('\\', '/').contains("/assets/");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, ctype);
        headers.setContentLength(size);
        headers.set("Accept-Ranges", "none");
        // 带内容哈希的构建产物可以长缓存，其余不缓存
        headers.set(HttpHeaders.CACHE_CONTROL,
                hashed ? "public, max-age=31536000, immutable" : "no-cache");

        return new ResponseEntity<>(new FileSystemResource(file), headers, HttpStatus.OK);
    }

    private static boolean hasExtension(String relative) {
        int slash = Math.max(relative.lastIndexOf('/'), relative.lastIndexOf('\\'));
        return relative.lastIndexOf('.') > slash;
    }
}
