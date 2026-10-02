package com.workhub.api;

import com.workhub.core.Ctx;
import com.workhub.core.Repo;
import com.workhub.service.AuthService;
import com.workhub.web.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口：注册 / 登录 / 退出 / 当前用户 / 改密码。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthApi {

    private final AuthService auth;

    public AuthApi(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return auth.register(str(b.get("email")), str(b.get("password")));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return auth.login(str(b.get("email")), str(b.get("password")));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        auth.logout(AuthInterceptor.tokenOf(request));
        return Repo.map("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        Ctx.AuthUser user = Ctx.requireUser();
        return Repo.map("user", Repo.map(
                "id", user.id(),
                "email", user.email(),
                "created_at", user.createdAt()));
    }

    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody(required = false) Map<String, Object> body,
                                              HttpServletRequest request) {
        Map<String, Object> b = body == null ? Map.of() : body;
        // 兼容两种字段命名：old/new 与 oldPassword/newPassword
        Object oldPwd = b.get("old");
        if (isEmpty(oldPwd)) {
            oldPwd = b.get("oldPassword");
        }
        Object newPwd = b.get("new");
        if (isEmpty(newPwd)) {
            newPwd = b.get("newPassword");
        }
        Ctx.AuthUser user = Ctx.requireUser();
        auth.changePassword(user.id(), str(oldPwd), str(newPwd), AuthInterceptor.tokenOf(request));
        return Repo.map("ok", true);
    }

    private static boolean isEmpty(Object value) {
        return value == null || String.valueOf(value).isEmpty();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
