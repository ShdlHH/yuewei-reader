package com.yuewei.reader.infrastructure.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.reader.domain.SupportLevel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 判断书源与当前规则引擎的兼容程度。 */
public final class SourceSupport {

    public record Result(SupportLevel level, String note) {
    }

    private SourceSupport() {
    }

    public static Result analyze(JsonNode source) {
        if (source == null) {
            return new Result(SupportLevel.UNSUPPORTED, "书源内容为空");
        }
        int type = source.path("bookSourceType").asInt(0);
        if (type != 0) {
            return new Result(SupportLevel.UNSUPPORTED, "非文本类书源（bookSourceType=" + type + "）");
        }
        List<String> notes = new ArrayList<>();
        if (!source.path("loginUrl").asText("").isBlank()
                || !source.path("loginCheckJs").asText("").isBlank()) {
            notes.add("依赖登录态");
        }
        if (!source.path("webJs").asText("").isBlank()) {
            notes.add("需要网页 JS");
        }
        scanJs(source, notes);
        if (!notes.isEmpty()) {
            return new Result(SupportLevel.PARTIAL, String.join("；", dedupe(notes)));
        }
        return new Result(SupportLevel.FULL, "");
    }

    private static void scanJs(JsonNode node, List<String> notes) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            String v = node.asText();
            if (v.contains("@js:") || v.contains("<js>") || v.startsWith("js:")) {
                notes.add("规则内嵌 JS 脚本");
            }
            if (v.contains(",{")) {
                notes.add("包含 POST/Headers 请求参数，当前仅支持 GET");
            }
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                scanJs(it.next().getValue(), notes);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                scanJs(child, notes);
            }
        }
    }

    private static List<String> dedupe(List<String> notes) {
        List<String> out = new ArrayList<>();
        for (String n : notes) {
            if (!out.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }
}
