package com.workhub.web;

import com.workhub.core.Ctx;
import com.workhub.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Pattern;

/**
 * 解析登录态与浏览器时间，写进 {@link Ctx}。
 *
 * <p>注意这里是「尽力解析」而非「强制登录」：注册、登录、健康检查、以及带签名的下载链接
 * 都不需要登录态。需要登录的接口自己调用 {@link Ctx#requireUser()} 来强制。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 只接受严格的 yyyy-MM-ddTHH:mm:ss，格式不合法就当没带 */
    private static final Pattern CLIENT_TIME =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}$");

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientTime = request.getHeader("X-Client-Time");
        if (clientTime != null) {
            clientTime = clientTime.trim();
            if (CLIENT_TIME.matcher(clientTime).matches()) {
                Ctx.setClientTime(clientTime);
            }
        }

        String token = tokenOf(request);
        if (token != null && !token.isEmpty()) {
            Ctx.AuthUser user = authService.userByToken(token);
            if (user != null) {
                Ctx.setUser(user);
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 线程会被复用，必须清理，否则身份会串到下一个请求
        Ctx.clear();
    }

    /** 登录态来源：{@code Authorization: Bearer xxx}，或 {@code ?t=xxx}（下载链接沿用）。 */
    public static String tokenOf(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.length() > 7 && auth.regionMatches(true, 0, "bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        String t = request.getParameter("t");
        return t == null ? null : t.trim();
    }
}
