package com.yuewei.reader.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.reader.domain.BookSource;
import com.yuewei.reader.infrastructure.network.HttpGateway;

/** 书源 URL 模板填充。 */
public final class SourceUrls {

    private SourceUrls() {
    }

    /** 搜索地址：替换 {{key}} / {{keyword}} / {{page}}。 */
    public static String search(BookSource source, JsonNode rules, String keyword, int page) {
        String template = rules == null ? "" : rules.path("searchUrl").asText("");
        if (template.isBlank()) {
            throw new AppException("书源未配置 searchUrl");
        }
        String url = template
                .replace("{{key}}", HttpGateway.encode(keyword))
                .replace("{{keyword}}", HttpGateway.encode(keyword))
                .replace("{{page}}", String.valueOf(page));
        return HttpGateway.absolutize(source.url, url);
    }
}
