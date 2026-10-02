package com.workhub.core;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 应用配置与路径。
 *
 * <p>业务数据在 MySQL 里，这个目录只留本地文件：
 * <pre>
 *   {base-dir}/storage/        上传的文件，按用户 id 分目录
 *   {base-dir}/secret.key      分享链接签名用的密钥（32 字节）
 * </pre>
 *
 * <p>环境变量：{@code WORKHUB_BASE} / {@code WORKHUB_STATIC} / {@code WORKHUB_PORT} / {@code WORKHUB_HOST}
 * 数据库连接另见 {@code WORKHUB_DB_URL} / {@code WORKHUB_DB_USER} / {@code WORKHUB_DB_PASSWORD}
 */
@Component
public class AppProps {

    /** 密码派生轮数，与 Python 版一致。改动会导致所有已有密码失效。 */
    public static final int PBKDF2_ROUNDS = 120000;
    /** 登录会话有效期：30 天 */
    public static final int TOKEN_TTL_SECONDS = 30 * 24 * 3600;
    /** 分享链接默认有效期：10 分钟 */
    public static final int SHARE_TTL_SECONDS = 600;
    /** JSON 请求体上限 */
    public static final long MAX_JSON_BODY = 4L * 1024 * 1024;
    /** 流式读写分块大小 */
    public static final int CHUNK = 256 * 1024;
    /** 允许写入的业务目录白名单 */
    public static final List<String> ALLOWED_FOLDERS = List.of("software", "docs", "misc");
    public static final String VERSION = "1.0";

    private final Path baseDir;
    private final Path staticDir;
    private final Path storageDir;
    private final Path secretPath;

    private byte[] secret;

    public AppProps(@Value("${workhub.base-dir}") String baseDir,
                    @Value("${workhub.static-dir}") String staticDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        this.staticDir = Paths.get(staticDir).toAbsolutePath().normalize();
        this.storageDir = this.baseDir.resolve("storage");
        this.secretPath = this.baseDir.resolve("secret.key");
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(baseDir);
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建数据目录：" + baseDir, e);
        }
        this.secret = loadOrCreateSecret();
    }

    /**
     * 读取分享签名密钥，不存在则生成 32 字节随机密钥。
     *
     * <p>与 Python 版共用同一个 {@code secret.key}：两版互相签发的分享链接都能验证通过。
     */
    private byte[] loadOrCreateSecret() {
        try {
            if (Files.exists(secretPath)) {
                byte[] data = Files.readAllBytes(secretPath);
                // 等价 Python 的 .read().strip()
                int start = 0;
                int end = data.length;
                while (start < end && isSpace(data[start])) {
                    start++;
                }
                while (end > start && isSpace(data[end - 1])) {
                    end--;
                }
                if (end > start) {
                    byte[] trimmed = new byte[end - start];
                    System.arraycopy(data, start, trimmed, 0, end - start);
                    return trimmed;
                }
            }
        } catch (IOException ignored) {
            // 读取失败则重新生成，下面统一处理
        }
        byte[] fresh = new byte[32];
        new java.security.SecureRandom().nextBytes(fresh);
        try {
            Files.write(secretPath, fresh);
        } catch (IOException e) {
            throw new UncheckedIOException("无法写入 " + secretPath, e);
        }
        return fresh;
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == 0x0b || b == 0x0c;
    }

    public Path baseDir() {
        return baseDir;
    }

    public Path staticDir() {
        return staticDir;
    }

    public Path storageDir() {
        return storageDir;
    }

    public Path secretPath() {
        return secretPath;
    }

    /** 分享链接签名密钥。 */
    public byte[] secret() {
        return secret;
    }
}
