package com.yuewei.reader.ui;

import com.yuewei.reader.application.AppException;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 错误分类：四类异常映射正确，未知异常回落网络类。 */
class ErrorKindTest {

    @Test
    void classifiesNetworkErrors() {
        assertEquals(ErrorKind.NETWORK, ErrorKind.classify(new SocketTimeoutException("Read timed out")));
        assertEquals(ErrorKind.NETWORK, ErrorKind.classify(new RuntimeException("连接被拒绝")));
        assertEquals(ErrorKind.NETWORK, ErrorKind.classify(new RuntimeException("boom")));
    }

    @Test
    void classifiesSourceRuleFailure() {
        assertEquals(ErrorKind.SOURCE_RULE,
                ErrorKind.classify(new AppException("正文解析为空，该书源正文规则可能已失效")));
    }

    @Test
    void classifiesMissingContent() {
        assertEquals(ErrorKind.CONTENT_MISSING,
                ErrorKind.classify(new AppException("该章节缺少正文地址")));
        assertEquals(ErrorKind.CONTENT_MISSING,
                ErrorKind.classify(new AppException("本书还没有章节")));
    }

    @Test
    void classifiesDeletedData() {
        assertEquals(ErrorKind.DATA_DELETED,
                ErrorKind.classify(new AppException("所属书源已被删除，只能阅读已下载章节")));
        assertEquals(ErrorKind.DATA_DELETED, ErrorKind.classify(new RuntimeException("书籍已删除")));
    }

    @Test
    void usesCauseChain() {
        Throwable wrapped = new RuntimeException("请求失败", new AppException("解析为空"));
        assertEquals(ErrorKind.SOURCE_RULE, ErrorKind.classify(wrapped));
    }
}
