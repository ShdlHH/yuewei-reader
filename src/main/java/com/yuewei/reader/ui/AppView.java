package com.yuewei.reader.ui;

import javafx.scene.Node;

/** 主窗口内可切换的页面。 */
public interface AppView {

    Node node();

    /** 每次切换到该页面时调用，用于刷新数据。 */
    default void onShow() {
    }
}
