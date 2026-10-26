package com.yuewei.reader.application;

import java.util.ArrayList;
import java.util.List;

/** 书源导入结果汇总。 */
public class ImportReport {
    public int added;
    public int skipped;
    public int failed;
    public int duplicates;
    public final List<Item> items = new ArrayList<>();

    public static class Item {
        public String name;
        public String url;
        public String action;
        public String reason;

        public Item(String name, String url, String action, String reason) {
            this.name = name;
            this.url = url;
            this.action = action;
            this.reason = reason;
        }
    }

    public String summary() {
        return "成功 " + added + " 个，跳过 " + skipped + " 个，失败 " + failed + " 个";
    }
}
