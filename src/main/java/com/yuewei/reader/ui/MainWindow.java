package com.yuewei.reader.ui;

import com.yuewei.reader.application.AppContext;
import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.ReadingProgress;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
        return sidebar;
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
