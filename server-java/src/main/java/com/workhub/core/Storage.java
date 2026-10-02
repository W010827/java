package com.workhub.core;

import com.workhub.web.ApiError;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件存储：路径拼装、安全校验、分享链接签名。
 *
 * <p>布局与 Python 版一致：{@code {storage}/{用户id}/{software|docs|misc}/{uuid}{扩展名}}
 */
@Component
public class Storage {

    /** 只保留这些字符，其余剔除 */
    private static final String SAFE_PATTERN = "[^0-9A-Za-z_\\-]";
    private static final String EXT_PATTERN = "[^0-9a-z.]";

    private final AppProps props;

    public Storage(AppProps props) {
        this.props = props;
    }

    /**
     * 把用户传来的相对路径解析成绝对路径，并挡掉目录穿越。
     *
     * <p>对已存在的部分会做一次真实路径解析，避免通过符号链接跳出用户目录。
     */
    public Path resolve(String userId, String rel) {
        String cleaned = rel == null ? "" : rel.replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        Path base = props.storageDir().resolve(String.valueOf(userId)).toAbsolutePath().normalize();
        Path full = base.resolve(cleaned).toAbsolutePath().normalize();

        Path checked = full;
        try {
            if (Files.exists(full)) {
                checked = full.toRealPath();
            } else if (full.getParent() != null && Files.exists(full.getParent())) {
                checked = full.getParent().toRealPath().resolve(full.getFileName());
            }
        } catch (IOException ignored) {
            // 解析失败就退回用 normalize 的结果
        }

        if (!checked.equals(base) && !checked.startsWith(base + java.io.File.separator)) {
            throw new ApiError(403, "permission-denied", "非法的文件路径");
        }
        return full;
    }

    /** 生成存储用的相对路径：目录白名单校验 + 随机文件名 + 清洗过的扩展名。 */
    public String makeRelPath(String folder, String filename) {
        String dir = safeSegment(folder, "misc");
        if (!AppProps.ALLOWED_FOLDERS.contains(dir)) {
            dir = "misc";
        }
        String ext = extension(filename).toLowerCase().replaceAll(EXT_PATTERN, "");
        if (ext.length() > 16) {
            ext = ext.substring(0, 16);
        }
        return dir + "/" + Crypto.uuidHex() + ext;
    }

    /** 分享链接签名：HMAC-SHA256(secret, "{userId}|{rel}|{exp}") */
    public String signShare(String userId, String rel, long exp) {
        String message = userId + "|" + rel + "|" + exp;
        return Crypto.hmacHex(props.secret(), message);
    }

    public Path storageDir() {
        return props.storageDir();
    }

    /** 等价 Python 的 {@code safe_segment()}：剔除特殊字符、截断 32、转小写。 */
    public static String safeSegment(String text, String defaultValue) {
        String cleaned = (text == null ? "" : text).replaceAll(SAFE_PATTERN, "");
        if (cleaned.length() > 32) {
            cleaned = cleaned.substring(0, 32);
        }
        cleaned = cleaned.toLowerCase();
        return cleaned.isEmpty() ? defaultValue : cleaned;
    }

    /** 取扩展名（含点），等价 Python 的 {@code os.path.splitext()[1]}。 */
    public static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        int dot = filename.lastIndexOf('.');
        if (dot <= slash || dot < 0) {
            return "";
        }
        return filename.substring(dot);
    }
}
