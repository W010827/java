package com.workhub.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * 与 Python 版（server/workhub.py）字节级兼容的加密工具。
 *
 * <p>关于 PBKDF2：这里刻意<b>没有</b>用 JDK 自带的 {@code PBKDF2WithHmacSHA256}，
 * 而是按 RFC 2898 自己实现。原因是各家 JDK 对密码 {@code char[]} 的编码处理
 * 存在差异，自己实现可以保证与 Python 的
 * {@code hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), salt.encode('utf-8'), 120000)}
 * 结果完全一致 —— 这样服务器上已有的账号密码，在 Java 版里也能直接登录。
 */
public final class Crypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final String HMAC_ALGO = "HmacSHA256";

    private Crypto() {
    }

    /** 转小写十六进制，等价 Python 的 {@code bytes.hex()}。 */
    public static String hex(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * PBKDF2-HMAC-SHA256（RFC 2898）。
     *
     * @param password   密码的 UTF-8 字节
     * @param salt       盐的 UTF-8 字节
     * @param iterations 迭代轮数
     * @param dkLen      派生密钥长度（字节）
     */
    public static byte[] pbkdf2Sha256(byte[] password, byte[] salt, int iterations, int dkLen) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(password, HMAC_ALGO));
            int hLen = mac.getMacLength();
            int blocks = (dkLen + hLen - 1) / hLen;
            byte[] dk = new byte[blocks * hLen];

            // 每个分块的输入是 salt || INT_BE(i)，随后反复做 HMAC 并逐字节异或
            byte[] input = new byte[salt.length + 4];
            System.arraycopy(salt, 0, input, 0, salt.length);

            for (int i = 1; i <= blocks; i++) {
                input[salt.length] = (byte) (i >>> 24);
                input[salt.length + 1] = (byte) (i >>> 16);
                input[salt.length + 2] = (byte) (i >>> 8);
                input[salt.length + 3] = (byte) i;

                byte[] u = mac.doFinal(input);   // doFinal 后 mac 自动复位，可复用
                byte[] acc = u.clone();
                for (int c = 1; c < iterations; c++) {
                    u = mac.doFinal(u);
                    for (int k = 0; k < hLen; k++) {
                        acc[k] ^= u[k];
                    }
                }
                System.arraycopy(acc, 0, dk, (i - 1) * hLen, hLen);
            }

            if (dk.length == dkLen) {
                return dk;
            }
            byte[] trimmed = new byte[dkLen];
            System.arraycopy(dk, 0, trimmed, 0, dkLen);
            return trimmed;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 计算失败", e);
        }
    }

    /** 密码加盐哈希。等价 Python 的 {@code hash_password()}：salt 为 16 字节的 hex。 */
    public static PasswordHash hashPassword(String password) {
        return hashPassword(password, randomSaltHex());
    }

    public static PasswordHash hashPassword(String password, String saltHex) {
        byte[] dk = pbkdf2Sha256(
                password.getBytes(StandardCharsets.UTF_8),
                saltHex.getBytes(StandardCharsets.UTF_8),
                AppProps.PBKDF2_ROUNDS,
                32);
        return new PasswordHash(saltHex, hex(dk));
    }

    /** 校验密码。等价 Python 的 {@code verify_password()}，用常量时间比较。 */
    public static boolean verifyPassword(String password, String saltHex, String expectedHex) {
        if (saltHex == null || expectedHex == null) {
            return false;
        }
        String actual = hashPassword(password, saltHex).hash;
        return constantTimeEquals(actual, expectedHex);
    }

    /** 等价 Python 的 {@code secrets.token_urlsafe(32)}：32 字节随机数的 base64url（无填充）。 */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 等价 Python 的 {@code secrets.token_hex(16)}：32 个十六进制字符。 */
    public static String randomSaltHex() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return hex(bytes);
    }

    /** 等价 Python 的 {@code uuid.uuid4().hex}：32 位小写十六进制、无连字符。 */
    public static String uuidHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 等价 Python 的 {@code hmac.new(key, msg, sha256).hexdigest()}。 */
    public static String hmacHex(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key, HMAC_ALGO));
            return hex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /** 常量时间字符串比较，避免按字符提前返回导致的时序泄漏。 */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /** 加盐哈希的结果：盐 + 派生哈希，都是十六进制字符串。 */
    public record PasswordHash(String salt, String hash) {
    }
}
