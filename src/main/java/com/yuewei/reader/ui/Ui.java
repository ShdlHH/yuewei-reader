package com.yuewei.reader.ui;

import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.text.Font;
import javafx.stage.Window;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** 界面通用小工具：控件构造、弹窗、后台任务。 */
public final class Ui {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 桌面端统一间距：页面 20、列表 16、卡片 8、表单 10，圆角 14。 */
    public static final int SPACE_PAGE = 20;
    public static final int SPACE_LIST = 16;
    public static final int SPACE_CARD = 8;
    public static final int SPACE_FORM = 10;
    public static final int RADIUS_CARD = 14;

    /** 一套主题令牌，与 style.css 的 looked-up colors 一一对应。 */
    public static final class Theme {
        public final String label;
        public final String background;
        public final String bar;
        public final String card;
        public final String card2;
        public final String ink;
        public final String ink2;
        public final String muted;
        public final String faint;
        public final String accent;
        public final String accent2;
        public final String line;

        Theme(String label, String background, String bar, String card, String card2,
              String ink, String ink2, String muted, String faint,
              String accent, String accent2, String line) {
            this.label = label;
            this.background = background;
            this.bar = bar;
            this.card = card;
            this.card2 = card2;
            this.ink = ink;
            this.ink2 = ink2;
            this.muted = muted;
            this.faint = faint;
            this.accent = accent;
            this.accent2 = accent2;
            this.line = line;
        }
    }

    private static final Map<String, Theme> THEMES = Map.of(
            "paper", new Theme("纸白", "#f4eddf", "#eae0cb", "#fbf6ea", "#f7f0e1",
                    "#221d18", "#4b4238", "#8b7f6c", "#b7ab96", "#a63b2c", "#c76a53", "#ded1b8"),
            "night", new Theme("夜间", "#141519", "#1b1d23", "#191b21", "#1e2027",
                    "#d2cec4", "#a9a399", "#8b877d", "#5e5b54", "#d07a66", "#e29a86", "#2d3038"),
            "green", new Theme("护眼", "#e8f0e3", "#dce8d5", "#f2f8ee", "#eaf2e5",
                    "#22301f", "#45533f", "#67735f", "#9aaa94", "#3f5c3d", "#5e7c58", "#c9dac2"));

    /** 当前主题键：主窗口注入令牌时同步，供弹窗等独立场景复用。 */
    private static String themeKey = "paper";

    private Ui() {
    }

    public static void setThemeKey(String key) {
        themeKey = key == null ? "paper" : key;
    }

    public static Theme theme(String key) {
        return THEMES.getOrDefault(key, THEMES.get("paper"));
    }

    /** 把主题写成根节点 looked-up colors，子节点 CSS 统一随之切换。 */
    public static String themeTokens(Theme theme) {
        return "-paper:" + theme.background + ";"
                + "-paper-2:" + theme.bar + ";"
                + "-card:" + theme.card + ";"
                + "-card-2:" + theme.card2 + ";"
                + "-ink:" + theme.ink + ";"
                + "-ink-2:" + theme.ink2 + ";"
                + "-muted:" + theme.muted + ";"
                + "-faint:" + theme.faint + ";"
                + "-accent:" + theme.accent + ";"
                + "-accent-2:" + theme.accent2 + ";"
                + "-line:" + theme.line + ";";
    }

    /** 弹窗复用主窗口样式表与主题令牌，避免回落到 JavaFX 默认外观或纸白底色。 */
    public static void styleDialog(Dialog<?> dialog, Window owner) {
        if (dialog == null || owner == null || owner.getScene() == null) {
            return;
        }
        dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        dialog.getDialogPane().setStyle(themeTokens(theme(themeKey)));
    }

    private static Window focusedWindow() {
        return Window.getWindows().stream().filter(Window::isFocused).findFirst().orElse(null);
    }

    public static Button button(String text, String... styleClasses) {
        Button button = new Button(text);
        button.getStyleClass().addAll(styleClasses);
        return button;
    }

    public static Label label(String text, String... styleClasses) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    public static void info(String message) {
        Alert alert = alert(Alert.AlertType.INFORMATION, "提示", message);
        Window owner = focusedWindow();
        alert.initOwner(owner);
        styleDialog(alert, owner);
        alert.show();
    }

    public static void error(String message) {
        error(null, "出错了", message);
    }

    public static void error(String title, String message) {
        error(null, title, message);
    }

    public static void error(Window owner, String title, String message) {
        Alert alert = alert(Alert.AlertType.ERROR, title, message);
        Window target = owner == null ? focusedWindow() : owner;
        alert.initOwner(target);
        styleDialog(alert, target);
        alert.show();
    }

    public static boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle(title);
        alert.setHeaderText(null);
        Window owner = focusedWindow();
        alert.initOwner(owner);
        styleDialog(alert, owner);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    public static String input(String title, String message, String defaultValue) {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(defaultValue);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(message);
        Window owner = focusedWindow();
        dialog.initOwner(owner);
        styleDialog(dialog, owner);
        return dialog.showAndWait().orElse(null);
    }

    private static Alert alert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message == null ? "" : message);
        alert.getDialogPane().setMinWidth(420);
        return alert;
    }

    /** 后台执行，回调在 FX 线程。 */
    public static <T> void async(Callable<T> work, Consumer<T> onSuccess) {
        async("ui-task", work, onSuccess, error -> error(null, "操作失败", message(error)));
    }

    public static <T> void async(String name, Callable<T> work, Consumer<T> onSuccess) {
        async(name, work, onSuccess, error -> error(null, "操作失败", message(error)));
    }

    public static <T> void async(String name, Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> onError.accept(task.getException()));
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    public static String message(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /** 系统已安装字体：首项“系统默认”，其余按名称排序，供排版选择与预览。 */
    public static List<String> fontFamilies() {
        List<String> families = new ArrayList<>();
        families.add("系统默认");
        Font.getFamilies().stream()
                .filter(name -> name != null && !name.isBlank() && !name.startsWith("@"))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(families::add);
        return families;
    }

    public static void setMonospace(TextInputControl control) {
        control.getStyleClass().add("monospace");
    }

    public static String formatTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return "—";
        }
        try {
            return LocalDateTime.parse(iso).format(TIME);
        } catch (Exception e) {
            return iso;
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
