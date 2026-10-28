package com.yuewei.reader.ui;

import com.yuewei.reader.ReaderApp;
import com.yuewei.reader.application.AppContext;
import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.ReadingProgress;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.LinkedHashMap;
import java.util.Map;

/** 主窗口：左侧导航 + 内容区 + 状态栏。 */
public class MainWindow {

    private final AppContext ctx;
    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final StackPane content = new StackPane();
    private final Label status = Ui.label("就绪", "status-text");
    private final Map<String, ToggleButton> navButtons = new LinkedHashMap<>();
    private final Map<String, AppView> views = new LinkedHashMap<>();

    private final ShelfView shelfView;
    private final SearchView searchView;
    private final SourceView sourceView;
    private final DownloadsView downloadsView;
    private final SettingsView settingsView;
    private final ReaderView readerView;

    private boolean switching;

    public MainWindow(AppContext ctx, Stage stage) {
        this.ctx = ctx;
        this.stage = stage;

        shelfView = new ShelfView(ctx, this);
        searchView = new SearchView(ctx, this);
        sourceView = new SourceView(ctx, this);
        downloadsView = new DownloadsView(ctx, this);
        settingsView = new SettingsView(ctx, this);
        readerView = new ReaderView(ctx, this);

        views.put("shelf", shelfView);
        views.put("search", searchView);
        views.put("sources", sourceView);
        views.put("downloads", downloadsView);
        views.put("settings", settingsView);

        root.getStyleClass().add("app-root");
        root.setLeft(buildSidebar());
        root.setCenter(content);
        root.setBottom(buildStatusBar());
        content.getStyleClass().add("content-area");

        root.addEventFilter(KeyEvent.KEY_PRESSED, this::handleShortcut);
        show("shelf");
    }

    public Parent root() {
        return root;
    }

    public Stage stage() {
        return stage;
    }

    public AppContext ctx() {
        return ctx;
    }

    public void status(String text) {
        status.setText(text == null ? "" : text);
    }

    public void refreshShelf() {
        shelfView.refresh();
    }

    /** 设置页保存阅读样式后，立即刷新已打开的阅读界面。 */
    public void applyReaderStyle() {
        readerView.refreshStyle();
    }

    public void show(String key) {
        AppView view = views.get(key);
        if (view == null) {
            return;
        }
        content.getChildren().setAll(view.node());
        switching = true;
        navButtons.forEach((k, button) -> button.setSelected(k.equals(key)));
        switching = false;
        view.onShow();
    }

    /** 打开阅读器：从上次阅读进度继续。 */
    public void openBook(Book book) {
        ReadingProgress progress = ctx.bookService.progress(book.id);
        int chapterIndex = progress == null ? 0 : progress.chapterIndex;
        int offset = progress == null ? 0 : progress.charOffset;
        readerView.open(book, chapterIndex, offset);
        content.getChildren().setAll(readerView.node());
        switching = true;
        navButtons.values().forEach(button -> button.setSelected(false));
        switching = false;
        readerView.onShow();
        status("正在阅读《" + book.title + "》");
    }

    public void backToShelf() {
        show("shelf");
    }

    private VBox buildSidebar() {
        VBox sidebar = new VBox(4);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(18, 12, 18, 12));
        sidebar.setPrefWidth(184);
        sidebar.setMinWidth(184);

        Label brand = Ui.label("阅微", "brand");
        Label subtitle = Ui.label("小说阅读器", "brand-sub");
        VBox header = new VBox(2, brand, subtitle);
        header.setPadding(new Insets(0, 8, 12, 8));
        sidebar.getChildren().addAll(header, new Separator());

        ToggleGroup group = new ToggleGroup();
        group.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null && oldValue != null && !switching) {
                oldValue.setSelected(true);
            }
        });
        addNav(sidebar, group, "shelf", "书架");
        addNav(sidebar, group, "search", "搜索");
        addNav(sidebar, group, "sources", "书源");
        addNav(sidebar, group, "downloads", "下载");
        addNav(sidebar, group, "settings", "设置");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Button about = Ui.button("关于", "ghost-button");
        about.setMaxWidth(Double.MAX_VALUE);
        about.setOnAction(e -> showAbout());
        sidebar.getChildren().addAll(spacer, new Separator(), about);
        return sidebar;
    }

    /** 关于：版本、开源地址与免责声明。 */
    private void showAbout() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("关于阅微");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        if (root.getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(root.getScene().getStylesheets());
        }

        Label name = Ui.label("阅微 · 小说阅读器", "about-name");
        Label version = Ui.label("版本 " + ReaderApp.VERSION + " · JavaFX 17 · SQLite", "muted");
        Label intro = Ui.label("支持本地 TXT 与 Legado 书源的桌面小说阅读器，书籍、进度与设置只保存在本机。",
                "about-text");
        intro.setWrapText(true);

        Hyperlink repo = new Hyperlink("https://github.com/ShdlHH/yuewei-reader");
        repo.setOnAction(e -> openUrl(repo.getText()));
        Label license = Ui.label("开源协议：MIT License", "muted");
        Label disclaimer = Ui.label("本软件不内置任何书源与书籍内容。书源由使用者自行导入，"
                + "抓取内容的版权归原作者与站点所有，请遵守目标站点的使用条款。", "muted");
        disclaimer.setWrapText(true);

        VBox content = new VBox(8, name, version, intro, repo, license, new Separator(), disclaimer);
        content.setPadding(new Insets(18, 22, 8, 22));
        content.setPrefWidth(440);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private void openUrl(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            Ui.error("无法打开浏览器", url);
        }
    }

    private void addNav(VBox sidebar, ToggleGroup group, String key, String text) {
        ToggleButton button = new ToggleButton(text);
        button.getStyleClass().add("nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setToggleGroup(group);
        button.setOnAction(e -> {
            if (!switching) {
                show(key);
            }
        });
        navButtons.put(key, button);
        sidebar.getChildren().add(button);
    }

    private HBox buildStatusBar() {
        HBox bar = new HBox(12, status);
        bar.getStyleClass().add("status-bar");
        bar.setPadding(new Insets(6, 14, 6, 14));
        HBox.setHgrow(status, Priority.ALWAYS);
        Label data = Ui.label("数据目录：" + ctx.dataDir, "status-muted");
        bar.getChildren().add(data);
        return bar;
    }

    private void handleShortcut(KeyEvent event) {
        if (!event.isControlDown()) {
            return;
        }
        if (event.getCode() == KeyCode.F) {
            show("search");
            searchView.focusKeyword();
            event.consume();
        } else if (event.getCode() == KeyCode.O) {
            show("shelf");
            shelfView.importTxt();
            event.consume();
        }
    }
}
