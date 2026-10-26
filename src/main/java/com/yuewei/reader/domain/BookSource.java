package com.yuewei.reader.domain;

/** 一份 Legado 3.0 书源。 */
public class BookSource {
    public long id;
    public String name;
    public String url;
    public String group;
    public int type = 0;
    public boolean enabled = true;
    public SourceStatus status = SourceStatus.UNCHECKED;
    public SupportLevel support = SupportLevel.FULL;
    public String lastCheck;
    public String ruleJson;
    public String lastError;
    /** 展示用：兼容性说明，不落库。 */
    public String compatNote;

    public boolean isTextSource() {
        return type == 0;
    }

    @Override
    public String toString() {
        return name == null ? "" : name;
    }
}
