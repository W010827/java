package com.workhub.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误出口，产出与 Python 版一致的 JSON 信封：
 * <pre>{"error": {"kind": "...", "message": "...", "code": "..."}}</pre>
 *
 * <p>前端的错误提示完全依赖 {@code kind}（unauthenticated / already-exists / network …），
 * 所以这里不能改结构。
 */
@RestControllerAdvice
public class ApiErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorAdvice.class);

    @ExceptionHandler(ApiError.class)
    public ResponseEntity<Map<String, Object>> handleApiError(ApiError e) {
        return ResponseEntity.status(e.status()).body(envelope(e.kind(), e.getMessage(), e.code()));
    }

    /** 接口路径不存在，保持和 Python 版一样的文案。 */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(HttpServletRequest request) {
        String message = "接口不存在：" + request.getMethod() + " " + request.getRequestURI();
        return ResponseEntity.status(404).body(envelope("not-found", message, null));
    }

    /** 兜底：未预期的异常统一转 500，日志里留完整堆栈方便排查。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("未处理的异常", e);
        return ResponseEntity.status(500)
                .body(envelope("internal", "服务器内部错误：" + e.getMessage(), null));
    }

    private static Map<String, Object> envelope(String kind, String message, String code) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("kind", kind);
        err.put("message", message);
        if (code != null) {
            err.put("code", code);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", err);
        return body;
    }
}
