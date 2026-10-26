package com.yuewei.reader.domain;

/** 下载任务状态。 */
public enum DownloadState {
    QUEUED("排队中"),
    RUNNING("下载中"),
    PAUSED("已暂停"),
    COMPLETED("已完成"),
    PARTIAL_FAILED("部分失败"),
    CANCELLED("已取消");

    public final String label;

    DownloadState(String label) {
        this.label = label;
    }
}
