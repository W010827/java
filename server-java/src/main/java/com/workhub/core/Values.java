package com.workhub.core;

/**
 * 取值工具。JSON 反序列化出来的数字可能是 Integer/Long/Double，字符串又可能带空白，
 * 这里统一处理，避免每个接口重复写。
 */
public final class Values {

    private Values() {
    }

    public static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 去掉首尾空白；null 视为空串（与 Python 的 {@code str(x or '').strip()} 等价）。 */
    public static String trimmed(Object value) {
        String s = str(value);
        return s == null ? "" : s.trim();
    }

    public static boolean isBlank(Object value) {
        return trimmed(value).isEmpty();
    }

    /** 转 int，转不了就用默认值（不抛异常）。 */
    public static int intOr(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 转 long，转不了就用默认值。 */
    public static long longOr(Object value, long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String orEmpty(Object value) {
        String s = str(value);
        return s == null ? "" : s;
    }
}
