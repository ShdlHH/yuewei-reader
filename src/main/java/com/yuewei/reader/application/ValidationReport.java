package com.yuewei.reader.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 书源校验/调试报告：四段链路逐段结果。 */
public class ValidationReport {
    public String sourceName;
    public String keyword;
    public boolean ok;
    public long elapsedMs;
    public final List<Stage> stages = new ArrayList<>();

    public static class Stage {
        public String name;
        public String url;
        public int status;
        public long millis;
        public String message;
        public String sample;
        public final Map<String, String> fields = new LinkedHashMap<>();
        public boolean skipped;

        @Override
        public String toString() {
            return name + " · " + (skipped ? "未执行" : status + " · " + millis + "ms") + " · " + message;
        }
    }

    public String firstFailure() {
        for (Stage s : stages) {
            if (!s.skipped && (s.status <= 0 || s.status >= 400)) {
                return s.name + "：" + s.message;
            }
            if (!s.skipped && s.message != null && s.message.startsWith("失败")) {
                return s.name + "：" + s.message;
            }
        }
        return "";
    }
}
