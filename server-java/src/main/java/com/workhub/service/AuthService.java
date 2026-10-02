package com.workhub.service;

import com.workhub.core.AppProps;
import com.workhub.core.Clock;
import com.workhub.core.Crypto;
import com.workhub.core.Ctx;
import com.workhub.core.Db;
import com.workhub.web.ApiError;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 账号与会话。
 *
 * <p>密码使用 PBKDF2-HMAC-SHA256（12 万轮）加盐存储，与 Python 版字节级兼容，
 * 因此指向同一个库时，两边签发的账号可以互相登录。
 */
@Service
public class AuthService {

    private static final Pattern EMAIL =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final Db db;

    public AuthService(Db db) {
        this.db = db;
    }

    /** 注册并直接登录，返回 {user:{id,email}, token}。 */
    public Map<String, Object> register(String emailRaw, String password) {
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        if (!EMAIL.matcher(email).matches()) {
            throw ApiError.badRequest("请填写正确的邮箱地址");
        }
        if (password == null || password.length() < 6) {
            throw ApiError.badRequest("密码至少 6 位");
        }
        if (db.one("SELECT id FROM users WHERE email = ?", email) != null) {
            throw new ApiError(409, "already-exists", "该邮箱已注册，请直接登录");
        }

        Crypto.PasswordHash ph = Crypto.hashPassword(password);
        String userId = Crypto.uuidHex();
        db.exec("INSERT INTO users (id, email, pwd_salt, pwd_hash, created_at) VALUES (?,?,?,?,?)",
                userId, email, ph.salt(), ph.hash(), Clock.nowIso());

        String token = createSession(userId);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", userId);
        user.put("email", email);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", user);
        result.put("token", token);
        return result;
    }

    /** 登录，返回 {user:{id,email,created_at}, token}。 */
    public Map<String, Object> login(String emailRaw, String password) {
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        Map<String, Object> row = db.one("SELECT * FROM users WHERE email = ?", email);
        if (row == null || !Crypto.verifyPassword(password,
                str(row.get("pwd_salt")), str(row.get("pwd_hash")))) {
            throw new ApiError(401, "unauthenticated", "邮箱或密码不正确");
        }
        String token = createSession(str(row.get("id")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", publicUser(row));
        result.put("token", token);
        return result;
    }

    public void logout(String token) {
        if (token != null && !token.isEmpty()) {
            db.exec("DELETE FROM sessions WHERE token = ?", token);
        }
    }

    /** 按 token 取用户；会话过期会顺手删掉该会话。 */
    public Ctx.AuthUser userByToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Map<String, Object> row = db.one(
                "SELECT u.*, s.expires_at AS s_exp FROM sessions s "
                        + "JOIN users u ON u.id = s.user_id WHERE s.token = ?", token);
        if (row == null) {
            return null;
        }
        String expiresAt = str(row.get("s_exp"));
        if (expiresAt == null || expiresAt.compareTo(Clock.serverNow()) < 0) {
            db.exec("DELETE FROM sessions WHERE token = ?", token);
            return null;
        }
        return new Ctx.AuthUser(str(row.get("id")), str(row.get("email")), str(row.get("created_at")));
    }

    /** 修改密码，并使其他设备的会话失效（当前会话保留）。 */
    public void changePassword(String userId, String oldPassword, String newPassword, String currentToken) {
        if (newPassword == null || newPassword.length() < 6) {
            throw ApiError.badRequest("新密码至少 6 位");
        }
        Map<String, Object> row = db.one("SELECT * FROM users WHERE id = ?", userId);
        if (row == null || !Crypto.verifyPassword(oldPassword,
                str(row.get("pwd_salt")), str(row.get("pwd_hash")))) {
            throw ApiError.badRequest("原密码不正确");
        }
        Crypto.PasswordHash ph = Crypto.hashPassword(newPassword);
        String token = currentToken == null ? "" : currentToken;
        db.execBatch(List.of(
                Db.SqlAndArgs.of("UPDATE users SET pwd_salt = ?, pwd_hash = ? WHERE id = ?",
                        ph.salt(), ph.hash(), userId),
                Db.SqlAndArgs.of("DELETE FROM sessions WHERE user_id = ? AND token != ?",
                        userId, token)
        ));
    }

    /** 建会话，同时清理已过期的会话。有效期用服务器时钟判定。 */
    private String createSession(String userId) {
        String token = Crypto.randomToken();
        String created = Clock.serverNow();
        String expires = Clock.isoAfterSeconds(AppProps.TOKEN_TTL_SECONDS);
        db.execBatch(List.of(
                Db.SqlAndArgs.of("INSERT INTO sessions (token, user_id, created_at, expires_at) VALUES (?,?,?,?)",
                        token, userId, created, expires),
                Db.SqlAndArgs.of("DELETE FROM sessions WHERE expires_at < ?", created)
        ));
        return token;
    }

    /** 对外暴露的用户字段：不含密码盐与哈希。 */
    public static Map<String, Object> publicUser(Map<String, Object> row) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", str(row.get("id")));
        user.put("email", str(row.get("email")));
        user.put("created_at", str(row.get("created_at")));
        return user;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
