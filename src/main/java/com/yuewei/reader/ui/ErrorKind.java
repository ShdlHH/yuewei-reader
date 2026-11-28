package com.yuewei.reader.ui;

/**
 * 错误分类：把技术异常收敛为一句人话，并据此决定可执行动作。
 *
 * <p>只依据异常类型与固定文案判断，未识别的异常一律按网络问题处理（可重试、不误导用户改书源）。</p>
 */
public enum ErrorKind {

    NETWORK("网络连接失败，请检查网络后重试"),
    SOURCE_RULE("该书源解析不到正文，可能规则已失效"),
    CONTENT_MISSING("这一章在源站不存在或地址已变更"),
    DATA_DELETED("书源或书籍已被删除");

    public final String message;

    ErrorKind(String message) {
        this.message = message;
    }

    public static ErrorKind classify(Throwable error) {
        String text = textOf(error);
        if (contains(text, "书源已被删除", "已被删除", "书籍已删除", "不在书架", "无法打开")) {
            return DATA_DELETED;
        }
        if (contains(text, "解析为空", "规则", "解析不到", "未匹配", "选择器")) {
            return SOURCE_RULE;
        }
        if (contains(text, "缺少正文地址", "章节不存在", "未找到章节", "内容缺失", "还没有章节")) {
            return CONTENT_MISSING;
        }
        if (contains(text, "超时", "连接", "网络", "未知主机", "拒绝",
                "timeout", "Timeout", "timed out", "Connection", "UnknownHost",
                "SocketTimeout", "ConnectException", "SSL")) {
            return NETWORK;
        }
        return NETWORK;
    }

    private static String textOf(Throwable error) {
        if (error == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 4) {
            builder.append(current.getClass().getSimpleName()).append(' ');
            if (current.getMessage() != null) {
                builder.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
            depth++;
        }
        return builder.toString();
    }

    private static boolean contains(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
