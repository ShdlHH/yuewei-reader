package com.yuewei.reader;

import javafx.application.Application;

/** 启动入口：独立于 JavaFX Application 子类，便于打包为可执行 jar。 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(ReaderApp.class, args);
    }
}
