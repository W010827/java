package com.workhub.web;

/**
 * 业务异常。抛出后由 {@link ApiErrorAdvice} 统一转成与 Python 版一致的 JSON 信封：
 * <pre>{"error": {"kind": "...", "message": "...", "code": "..."}}</pre>
 */
public class ApiError extends RuntimeException {

    private final int status;
    private final String kind;
    private final String code;

    public ApiError(int status, String kind, String message) {
        this(status, kind, message, null);
    }

    public ApiError(int status, String kind, String message, String code) {
        super(message);
        this.status = status;
        this.kind = kind;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public static ApiError badRequest(String message) {
        return new ApiError(400, "invalid-request", message);
    }

    public static ApiError unauthorized(String message) {
        return new ApiError(401, "unauthenticated", message);
    }

    public static ApiError notFound(String message) {
        return new ApiError(404, "not-found", message);
    }
}
