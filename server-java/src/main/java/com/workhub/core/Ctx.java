package com.workhub.core;

/**
 * 请求级上下文。
 *
 * <p>由 {@code AuthInterceptor} 写入、在请求结束时清理。用 ThreadLocal 保存，
 * 这样业务方法不必层层传递用户信息。
 */
public final class Ctx {

    private static final ThreadLocal<AuthUser> USER = new ThreadLocal<>();
    private static final ThreadLocal<String> CLIENT_TIME = new ThreadLocal<>();

    private Ctx() {
    }

    public static void setUser(AuthUser user) {
        USER.set(user);
    }

    /** 当前登录用户；未登录返回 null。 */
    public static AuthUser user() {
        return USER.get();
    }

    /** 取当前登录用户，未登录直接抛 401 —— 与 Python 版 {@code require_user()} 行为一致。 */
    public static AuthUser requireUser() {
        AuthUser user = USER.get();
        if (user == null) {
            throw new com.workhub.web.ApiError(401, "unauthenticated", "登录状态已失效，请重新登录");
        }
        return user;
    }

    public static void setClientTime(String value) {
        CLIENT_TIME.set(value);
    }

    /** 浏览器上报的时间，格式不合法时拦截器不会写入，这里即为 null。 */
    public static String clientTime() {
        return CLIENT_TIME.get();
    }

    /** 请求结束务必调用，避免线程复用导致上一个请求的身份泄漏到下一个请求。 */
    public static void clear() {
        USER.remove();
        CLIENT_TIME.remove();
    }

    /** 登录用户的公开信息（不含密码相关字段）。 */
    public record AuthUser(String id, String email, String createdAt) {
    }
}
