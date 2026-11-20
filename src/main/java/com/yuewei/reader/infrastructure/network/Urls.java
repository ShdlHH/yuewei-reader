package com.yuewei.reader.infrastructure.network;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 纯 Java 的 URL 工具：桌面端与 Android 端共用，不依赖 java.net.http。 */
public final class Urls {

    private Urls() {
    }

    /** 去掉 Legado 规则里的选项后缀，并把空格编码为 %20。 */
    public static String normalize(String raw) {
        String url = raw == null ? "" : raw.trim();
        int options = url.indexOf(",{");
        if (options > 0) {
            url = url.substring(0, options);
        }
        return url.replace(" ", "%20");
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * 书源 / 书籍地址的匹配键：去空白与规则选项、去 fragment、去末尾斜杠、scheme 与 host 小写。
     * 只用于跨设备比较，实际请求仍使用原地址。
     */
    public static String key(String raw) {
        String value = normalize(raw);
        if (value.isEmpty()) {
            return "";
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme != null && host != null) {
                String normalized = scheme.toLowerCase(Locale.ROOT) + "://"
                        + host.toLowerCase(Locale.ROOT)
                        + (uri.getPort() >= 0 ? ":" + uri.getPort() : "")
                        + (uri.getPath() == null ? "" : uri.getPath())
                        + (uri.getQuery() == null ? "" : "?" + uri.getQuery());
                return trimSlash(normalized);
            }
        } catch (Exception ignored) {
            // 非法 URL 直接按文本比较
        }
        return trimSlash(value);
    }

    private static String trimSlash(String value) {
        String out = value;
        while (out.endsWith("/") && out.length() > 8) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** 相对链接转为绝对链接；已是绝对链接时原样返回。 */
    public static String absolutize(String baseUrl, String link) {
        if (link == null || link.isBlank()) {
            return link;
        }
        String value = link.trim();
        if (value.startsWith("javascript:") || value.startsWith("#")) {
            return null;
        }
        if (value.startsWith("//")) {
            String scheme = baseUrl != null && baseUrl.startsWith("https") ? "https" : "http";
            return scheme + ":" + value;
        }
        if (value.matches("(?i)^(https?|ftp|file):.*")) {
            return value.replace(" ", "%20");
        }
        try {
            URI base = URI.create(normalize(baseUrl));
            return base.resolve(value.replace(" ", "%20")).toString();
        } catch (Exception e) {
            return value;
        }
    }
}
