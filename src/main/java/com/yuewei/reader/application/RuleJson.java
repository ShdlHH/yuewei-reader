package com.yuewei.reader.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 书源 JSON 读取工具。 */
public final class RuleJson {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleJson() {
    }

    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json == null ? "" : json);
        } catch (Exception e) {
            throw new AppException("书源 JSON 语法错误：" + e.getMessage(), e);
        }
    }
}
