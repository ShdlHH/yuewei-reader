package com.yuewei.reader.domain;

/** 书籍来源类型。 */
public enum SourceType {
    LOCAL("本地"),
    ONLINE("在线");

    public final String label;

    SourceType(String label) {
        this.label = label;
    }
}
