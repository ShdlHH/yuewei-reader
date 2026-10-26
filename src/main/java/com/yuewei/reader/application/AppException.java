package com.yuewei.reader.application;

/** 面向用户的业务异常，消息可直接展示。 */
public class AppException extends RuntimeException {
    public AppException(String message) {
        super(message);
    }

    public AppException(String message, Throwable cause) {
        super(message, cause);
    }
}
