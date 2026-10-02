package com.workhub.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具。这里刻意区分了两个时间源，原因见 {@link #nowIso()} 与 {@link #serverNow()}。
 */
public final class Clock {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Clock() {
    }

    /**
     * 业务记录时间（created_at / updated_at）。
     *
     * <p>优先采用浏览器传来的时间（请求头 {@code X-Client-Time}）：如果服务器时钟不准
     * （虚拟机时间停滞、NTP 未生效等），记录时间仍与使用者电脑一致，不影响阅读。
     * 没带该头时回退到服务器时间。
     */
    public static String nowIso() {
        String clientTime = Ctx.clientTime();
        return clientTime != null ? clientTime : serverNow();
    }

    /**
     * 服务器本地时间。<b>只用于会话与分享链接的有效期判断</b> ——
     * 签发和校验必须落在同一个时钟上，否则会互相矛盾。
     */
    public static String serverNow() {
        return LocalDateTime.now().format(DATE_TIME);
    }

    /** 今天（yyyy-MM-dd）。 */
    public static String todayIso() {
        return LocalDate.now().format(DATE);
    }

    /** 当前时间往后 n 秒，用于会话过期时间。 */
    public static String isoAfterSeconds(long seconds) {
        return LocalDateTime.now().plusSeconds(seconds).format(DATE_TIME);
    }

    /** Unix 秒。分享链接的过期判断用它，与 Python 的 {@code int(time.time())} 一致。 */
    public static long epochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
