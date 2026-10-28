package com.yuewei.reader.ui;

import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.text.Font;
import javafx.stage.Window;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** 界面通用小工具：控件构造、弹窗、后台任务。 */
public final class Ui {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private Ui() {
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
        alert(Alert.AlertType.INFORMATION, "提示", message).show();
    }

    public static void error(String message) {
        error(null, "出错了", message);
    }

    public static void error(String title, String message) {
        error(null, title, message);
    }

    public static void error(Window owner, String title, String message) {
        Alert alert = alert(Alert.AlertType.ERROR, title, message);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.show();
    }

    public static boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.initOwner(Window.getWindows().stream().filter(Window::isFocused).findFirst().orElse(null));
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    public static String input(String title, String message, String defaultValue) {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(defaultValue);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(message);
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
        control.setStyle("-fx-font-family: Consolas, 'Courier New', monospace; -fx-font-size: 12px;");
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
