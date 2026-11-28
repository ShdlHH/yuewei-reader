package com.yuewei.reader.ui;

import com.yuewei.reader.ReaderApp;
import com.yuewei.reader.application.AppContext;
import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.ReadingProgress;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
    private final Map<String, Label> navHints = new LinkedHashMap<>();
    private final Map<String, AppView> views = new LinkedHashMap<>();
    private Label syncCardValue;

    private final ShelfView shelfView;
    private final SearchView searchView;
    private final SourceView sourceView;
    private final DownloadsView downloadsView;
    private final SettingsView settingsView;
    private final ReaderView readerView;

    private boolean switching;
    private boolean checkingUpdates;

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
        applyTheme();
        show("shelf");
        pullOnStartup();
        scheduleUpdateCheck();
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
        refreshSidebarHints();
    }

    /** 主题令牌写在主窗口根节点，侧栏、状态栏与内容区一起换色。 */
    public void applyTheme() {
        String key = ctx.settings.theme();
        root.setStyle(Ui.themeTokens(Ui.theme(key)));
        Ui.setThemeKey(key);
    }

    /** 设置页保存阅读样式后，立即刷新外壳主题与已打开的阅读界面。 */
    public void applyReaderStyle() {
        applyTheme();
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
        refreshSidebarHints();
        view.onShow();
    }

    /** 打开阅读器：从上次阅读进度继续。 */
    public void openBook(Book book) {
        ReadingProgress progress = ctx.bookService.progress(book.id);
        openBookFrom(book, progress == null ? 0 : progress.chapterIndex,
                progress == null ? 0 : progress.charOffset);
    }

    /** 打开阅读器并定位到指定章节（下载任务、读最新章等入口使用）。 */
    public void openBookFrom(Book book, int chapterIndex) {
        openBookFrom(book, chapterIndex, 0);
    }

    /** 打开阅读器并定位到章节内百分比位置。 */
    public void openBookFrom(Book book, int chapterIndex, int charOffset) {
        readerView.open(book, Math.max(0, chapterIndex), Math.max(0, charOffset));
        content.getChildren().setAll(readerView.node());
        switching = true;
        navButtons.values().forEach(button -> button.setSelected(false));
        switching = false;
        readerView.onShow();
        status("正在阅读《" + book.title + "》");
    }

    /** 启动 5 秒后检查一次追更，之后每分钟判断是否到间隔（关闭或 0 分钟时跳过）。 */
    private void scheduleUpdateCheck() {
        javafx.animation.PauseTransition initial = new javafx.animation.PauseTransition(
                javafx.util.Duration.seconds(5));
        initial.setOnFinished(e -> runUpdateCheck());
        initial.play();
        javafx.animation.Timeline timer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.minutes(1), e -> runUpdateCheck()));
        timer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        timer.play();
    }

    /** 静默执行一轮追更检查：有更新或失败才在状态栏提示，不打断阅读。 */
    private void runUpdateCheck() {
        if (checkingUpdates || !ctx.updateCheckService.due()) {
            return;
        }
        checkingUpdates = true;
        Ui.async("update-check",
                () -> ctx.updateCheckService.runOnce(
                        com.yuewei.reader.application.UpdateCheckService.DEFAULT_MAX_BOOKS),
                result -> {
                    checkingUpdates = false;
                    refreshShelf();
                    if (result.updated() > 0) {
                        status(result.summary() + "，可在书架点「读最新章」");
                    } else if (result.failed() > 0) {
                        status(result.summary());
                    }
                },
                error -> {
                    checkingUpdates = false;
                    status("更新检查失败：" + ErrorKind.classify(error).message);
                });
    }

    /** 阅读页「更新目录」：抓最新目录并刷新书架与阅读页章节列表。 */
    public void refreshBookToc(Book book) {
        if (book == null || !book.isOnline()) {
            return;
        }
        status("正在更新《" + book.title + "》目录…");
        Ui.async("refresh-toc", () -> ctx.onlineBookService.refreshToc(book),
                added -> {
                    ctx.bookService.markUpdate(book.id, 0);
                    readerView.reloadChapters();
                    refreshShelf();
                    status(added > 0 ? "目录已更新，新增 " + added + " 章" : "目录已是最新");
                },
                error -> {
                    String message = ErrorKind.classify(error).message;
                    status("目录更新失败：" + message);
                    Ui.error("目录更新失败", message);
                });
    }

    public void backToShelf() {
        show("shelf");
        pushQuietly();
    }

    /** 启动后静默拉取一次进度；上次同步被中断则补跑一次完整同步。 */
    private void pullOnStartup() {
        if (!ctx.syncService.configured()) {
            return;
        }
        boolean resume = ctx.settings.syncPending();
        Ui.async("sync-pull",
                () -> resume ? ctx.syncService.syncNow() : ctx.syncService.pull(),
                result -> {
                    if (!result.skipped()) {
                        refreshShelf();
                        settingsView.refreshSyncHint();
                        readerView.refreshStyle();
                        refreshSyncCard();
                        status(result.message());
                    }
                },
                error -> status("同步拉取失败：" + ErrorKind.classify(error).message));
    }

    /** 设置页“立即同步”：先拉远端进度，再把本地进度、书架与设置推上去。 */
    public void syncNow(boolean verbose) {
        if (!ctx.syncService.configured()) {
            if (verbose) {
                Ui.info("请先在设置页填写 WebDAV 地址，并勾选启用同步。");
            }
            return;
        }
        status("正在同步…");
        Ui.async("sync-now", () -> ctx.syncService.syncNow().message(),
                message -> {
                    refreshShelf();
                    settingsView.refreshSyncHint();
                    refreshSyncCard();
                    status(message);
                    readerView.refreshStyle();
                    if (verbose) {
                        Ui.info(message);
                    }
                },
                error -> {
                    String message = ErrorKind.classify(error).message;
                    status("同步失败：" + message + "，点击侧栏同步卡可重试");
                    if (verbose) {
                        Ui.error("同步失败：" + message, Ui.message(error));
                    }
                });
    }

    /** 切章或退出阅读时顺带上传这本书的进度，失败不打扰阅读。 */
    public void syncBookProgress(Book book) {
        if (!ctx.syncService.configured() || book == null) {
            return;
        }
        Ui.async("sync-book", () -> {
            ctx.syncService.pushBookProgress(book);
            return null;
        }, value -> {
        }, error -> status("进度上传失败：" + Ui.message(error)));
    }

    /** 关闭窗口前把当前阅读位置落库，再进行退出同步。 */
    public void flushReader() {
        readerView.saveNow();
    }

    private void pushQuietly() {
        if (!ctx.syncService.configured()) {
            return;
        }
        Ui.async("sync-push", () -> ctx.syncService.pushAll(),
                result -> status(result.message()),
                error -> status("同步上传失败：" + Ui.message(error)));
    }

    private VBox buildSidebar() {
        VBox sidebar = new VBox(4);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(18, 12, 18, 12));
        sidebar.setPrefWidth(224);
        sidebar.setMinWidth(224);

        Label seal = Ui.label("阅微", "mini-seal");
        seal.setMinSize(38, 38);
        seal.setPrefSize(38, 38);
        seal.setMaxSize(38, 38);
        seal.setAlignment(Pos.CENTER);

        Label brand = Ui.label("阅微", "brand");
        Label subtitle = Ui.label("小说阅读器", "brand-sub");
        HBox header = new HBox(12, seal, new VBox(2, brand, subtitle));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 6, 14, 6));
        sidebar.getChildren().addAll(header, new Separator());

        ToggleGroup group = new ToggleGroup();
        group.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null && oldValue != null && !switching) {
                oldValue.setSelected(true);
            }
        });
        addNav(sidebar, group, "shelf", "书架", "");
        addNav(sidebar, group, "search", "搜索", "SEARCH");
        addNav(sidebar, group, "sources", "书源", "");
        addNav(sidebar, group, "downloads", "下载", "");
        addNav(sidebar, group, "settings", "设置", "Aa");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label syncTitle = Ui.label("上次同步", "sync-card-title");
        syncCardValue = Ui.label(ctx.settings.lastSyncText(), "sync-card-value");
        syncCardValue.setWrapText(true);
        VBox syncCard = new VBox(6, syncTitle, syncCardValue);
        syncCard.getStyleClass().add("sync-card");
        syncCard.setOnMouseClicked(e -> syncNow(true));

        Button about = Ui.button("关于", "ghost-button");
        about.setMaxWidth(Double.MAX_VALUE);
        about.setOnAction(e -> showAbout());
        sidebar.getChildren().addAll(spacer, syncCard, new Separator(), about);
        refreshSidebarHints();
        return sidebar;
    }

    /** 侧栏提示：书架与书源数量随数据刷新，其余为固定快捷键语义。 */
    private void refreshSidebarHints() {
        setNavHint("shelf", String.valueOf(ctx.bookService.shelf("", null, "recent").size()));
        setNavHint("search", "SEARCH");
        setNavHint("sources", String.valueOf(ctx.sourceService.findAll().size()));
        setNavHint("downloads", "");
        setNavHint("settings", "Aa");
    }

    private void setNavHint(String key, String text) {
        Label hint = navHints.get(key);
        if (hint != null) {
            hint.setText(text);
        }
    }

    private void refreshSyncCard() {
        if (syncCardValue != null) {
            syncCardValue.setText(ctx.settings.lastSyncText());
        }
    }

    /** 关于：版本、开源地址与免责声明。 */
    private void showAbout() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("关于阅微");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Ui.styleDialog(dialog, stage);

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

    private void addNav(VBox sidebar, ToggleGroup group, String key, String text, String hint) {
        Label name = Ui.label(text, "nav-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hintLabel = Ui.label(hint, "nav-hint");
        HBox row = new HBox(6, name, spacer, hintLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));

        ToggleButton button = new ToggleButton();
        button.setGraphic(row);
        button.getStyleClass().add("nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setToggleGroup(group);
        row.prefWidthProperty().bind(button.widthProperty());
        button.setOnAction(e -> {
            if (!switching) {
                show(key);
            }
        });
        navButtons.put(key, button);
        navHints.put(key, hintLabel);
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
        if (event.getCode() == KeyCode.F11) {
            stage.setFullScreen(!stage.isFullScreen());
            event.consume();
            return;
        }
        boolean reading = content.getChildren().size() == 1
                && content.getChildren().get(0) == readerView.node();
        if (reading) {
            String action = Keymap.actionOf(Keymap.load(ctx.settings), event);
            if (action != null) {
                switch (action) {
                    case Keymap.TOC -> {
                        readerView.toggleToc();
                        event.consume();
                        return;
                    }
                    case Keymap.READER_PANEL -> {
                        event.consume();
                        readerView.openPanel();
                        return;
                    }
                    case Keymap.IMMERSIVE -> {
                        readerView.toggleImmersive();
                        event.consume();
                        return;
                    }
                    default -> {
                        // 翻页与切章由阅读页自己处理
                    }
                }
            }
        }
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
