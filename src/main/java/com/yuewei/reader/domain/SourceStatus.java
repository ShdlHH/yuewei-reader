package com.yuewei.reader.domain;

/** 书源健康状态。 */
public enum SourceStatus {
    UNCHECKED("未校验"),
    CHECKING("校验中"),
    OK("正常"),
    ERROR("异常"),
    PARTIAL("部分支持"),
    UNSUPPORTED("不支持");

    public final String label;

    SourceStatus(String label) {
        this.label = label;
    }
}
