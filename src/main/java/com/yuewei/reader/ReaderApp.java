package com.yuewei.reader;

import com.yuewei.reader.application.AppContext;
import com.yuewei.reader.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.net.URL;

/** JavaFX 应用：初始化数据目录，装配主窗口。 */
public class ReaderApp extends Application {

    public static final String VERSION = "0.2.0";

    private AppContext context;

    @Override
    public void init() {
        context = new AppContext(AppContext.defaultDataDir());
    }

    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow(context, stage);
        Scene scene = new Scene(window.root(), 1180, 780);
        URL css = ReaderApp.class.getResource("/ui/style.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        applyIcons(stage);
        stage.setTitle("阅微 · 小说阅读器");
        stage.setScene(scene);
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        stage.show();
    }

    /** 窗口与任务栏图标：使用同一图标的多种尺寸，保证缩放清晰。 */
    private void applyIcons(Stage stage) {
        for (String size : new String[]{"16", "32", "64", "128", "256"}) {
            URL icon = ReaderApp.class.getResource("/icon/app-" + size + ".png");
            if (icon != null) {
                stage.getIcons().add(new Image(icon.toExternalForm()));
            }
        }
    }

    @Override
    public void stop() {
        if (context != null) {
            context.close();
        }
    }
}
