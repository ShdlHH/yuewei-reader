package com.yuewei.reader.infrastructure.network;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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
