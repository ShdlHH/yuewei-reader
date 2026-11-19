package com.yuewei.reader.ui;

import com.yuewei.reader.application.AppContext;
import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.DownloadRangeType;
import com.yuewei.reader.domain.ReadingProgress;
import com.yuewei.reader.domain.SourceType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** 书架：搜索、筛选、排序、导入 TXT 与书籍操作。 */
public class ShelfView implements AppView {

    private final AppContext ctx;
    private final MainWindow window;
    private final BorderPane root = new BorderPane();
    private final VBox rows = new VBox();
    private final VBox heroHolder = new VBox();
    private final TextField query = new TextField();
    private final ComboBox<String> filter = new ComboBox<>();
    private final ComboBox<String> sort = new ComboBox<>();
    private final Label summary = Ui.label("", "muted");

    public ShelfView(AppContext ctx, MainWindow window) {
        this.ctx = ctx;
        this.window = window;
        build();
    }

    private void build() {
        query.setPromptText("搜索书名或作者");
        query.setPrefWidth(220);
        query.textProperty().addListener((observable, oldValue, newValue) -> refresh());

        filter.getItems().addAll("全部", "本地", "在线");
        filter.setValue("全部");
        filter.valueProperty().addListener((observable, oldValue, newValue) -> refresh());

        sort.getItems().addAll("最近阅读", "添加时间", "书名");
        sort.setValue("最近阅读");
        sort.valueProperty().addListener((observable, oldValue, newValue) -> refresh());

        Button importButton = Ui.button("导入 TXT", "primary-button");
        importButton.setOnAction(e -> importTxt());
        Button searchButton = Ui.button("搜索", "ghost-button");
        searchButton.setOnAction(e -> window.show("search"));
        Button sourcesButton = Ui.button("书源", "ghost-button");
        sourcesButton.setOnAction(e -> window.show("sources"));
        Button syncButton = Ui.button("同步", "ghost-button");
        syncButton.setOnAction(e -> window.syncNow(true));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label greeting = Ui.label(greetingText(), "shelf-greeting");
        Label title = Ui.label("书架", "shelf-title");
        VBox titles = new VBox(4, greeting, title);
        HBox header = new HBox(12, titles, spacer, summary, importButton, searchButton,
                sourcesButton, syncButton);
        header.getStyleClass().add("shelf-header");
        header.setAlignment(Pos.CENTER_LEFT);

        HBox tools = new HBox(10, query, filter, sort);
        tools.getStyleClass().add("shelf-tools");
        tools.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(header, tools);

        heroHolder.setSpacing(12);
        rows.setSpacing(0);
        VBox body = new VBox(24, heroHolder, rows);
        body.setMaxWidth(980);
        body.setPadding(new Insets(10, 24, 24, 32));
        StackPane center = new StackPane(body);
        center.setAlignment(Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(center);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("shelf-scroll");

        root.setTop(top);
        root.setCenter(scroll);
        refresh();
    }

    public void refresh() {
        SourceType type = switch (filter.getValue()) {
            case "本地" -> SourceType.LOCAL;
            case "在线" -> SourceType.ONLINE;
            default -> null;
        };
        String sortKey = switch (sort.getValue()) {
            case "添加时间" -> "added";
            case "书名" -> "title";
            default -> "recent";
        };
        List<Book> books = ctx.bookService.shelf(query.getText(), type, sortKey);
        refreshHero(books);
        rows.getChildren().clear();
        for (int i = 0; i < books.size(); i++) {
            rows.getChildren().add(bookRow(books.get(i), i));
        }
        if (books.isEmpty()) {
            rows.getChildren().add(emptyState());
        }
        long cacheSize = ctx.bookService.totalCacheSize();
        String text = "共 " + books.size() + " 本 · 缓存 " + Ui.humanSize(cacheSize);
        if (cacheSize > (long) ctx.settings.cacheLimitMb() * 1024 * 1024) {
            text += " · 已超出上限，可在设置中清理";
        }
        summary.setText(text);
    }

    private void refreshHero(List<Book> books) {
        heroHolder.getChildren().clear();
        if (!books.isEmpty()) {
            heroHolder.getChildren().add(heroCard(books.get(0)));
        }
    }

    /** 继续阅读：最近一本书的书脊卡片。 */
    private Node heroCard(Book book) {
        HBox hero = new HBox(16);
        hero.getStyleClass().add("hero-book");
        hero.setAlignment(Pos.CENTER_LEFT);

        ReadingProgress progress = ctx.bookService.progress(book.id);
        int chapterIndex = progress == null ? 0 : progress.chapterIndex;
        int total = Math.max(1, book.chapterCount);
        String chapter = "在读 · 第 " + (chapterIndex + 1) + " 章 · " + progressText(progress, total);

        Label title = Ui.label(book.title, "hero-title");
        Label meta = Ui.label(chapter, "hero-meta");
        VBox info = new VBox(7, title, meta);
        HBox.setHgrow(info, Priority.ALWAYS);

        Label action = Ui.label("继续阅读", "hero-continue");
        hero.getChildren().addAll(verticalSpine(book.title), info, action);
        hero.setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                window.openBook(book);
            }
        });
        return hero;
    }

    private String greetingText() {
        LocalDate now = LocalDate.now();
        return now.getMonthValue() + " 月 " + now.getDayOfMonth() + " 日 · 夜读";
    }

    /** 竖排书脊：逐字排布，最多五个字，超出只取前四字。 */
    private Node verticalSpine(String title) {
        VBox spine = new VBox(0);
        spine.getStyleClass().add("hero-spine");
        spine.setAlignment(Pos.CENTER);
        String text = title == null || title.isBlank() ? "书" : title.strip();
        int limit = Math.min(5, text.length());
        for (int i = 0; i < limit; i++) {
            spine.getChildren().add(new Label(String.valueOf(text.charAt(i))));
        }
        return spine;
    }

    public void importTxt() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 TXT 文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("文本文件", "*.txt"));
        File file = chooser.showOpenDialog(window.stage());
        if (file == null) {
            return;
        }
        Path path = file.toPath();
        window.status("正在读取 " + file.getName() + "…");
        Ui.async("import-preview", () -> ctx.importService.preview(path), preview -> {
            window.status("就绪");
            ImportDialog.Params params = ImportDialog.show(window.stage(), preview);
            if (params == null) {
                window.status("已取消导入");
                return;
            }
            doImport(path, params, false);
        });
    }

    private void doImport(Path path, ImportDialog.Params params, boolean forceDuplicate) {
        window.status("正在导入 " + path.getFileName() + "…");
        Ui.async("import-book",
                () -> ctx.importService.importBook(path, params.charset, params.mode, params.regex,
                        params.title, params.author, forceDuplicate),
                book -> {
                    refresh();
                    window.status("已导入《" + book.title + "》");
                    Ui.info("已加入书架：《" + book.title + "》，共 " + book.chapterCount + " 章");
                },
                error -> {
                    String message = Ui.message(error);
                    if (!forceDuplicate && message.startsWith("该书已在书架")) {
                        if (Ui.confirm("重复导入", message + "\n\n仍要再导入一份吗？")) {
                            doImport(path, params, true);
                        }
                        window.status("就绪");
                        return;
                    }
                    Ui.error("导入失败", message);
                    window.status("导入失败：" + message);
                });
    }

    /** 书目行：序号 + 书名/来源章节 + 阅读百分比，行间以发丝线分隔。 */
    private Node bookRow(Book book, int position) {
        Label index = Ui.label(String.format(Locale.ROOT, "%02d", position + 1), "shelf-index");
        index.setMinWidth(28);

        String author = book.author == null || book.author.isBlank() ? "未知" : book.author;
        String mode = book.isOnline() ? "在线阅读" : "本地 TXT";
        ReadingProgress progress = ctx.bookService.progress(book.id);
        int total = Math.max(1, book.chapterCount);
        String chapter = progress == null
                ? "共 " + total + " 章"
                : "读至第 " + (progress.chapterIndex + 1) + " 章";

        Label title = Ui.label(book.title, "shelf-row-title");
        Label meta = Ui.label(author + " · " + chapter + " · " + mode, "shelf-row-meta");
        VBox info = new VBox(4, title, meta);
        HBox.setHgrow(info, Priority.ALWAYS);

        Label percent = Ui.label(progressText(progress, total), "shelf-row-pct");
        percent.setMinWidth(64);
        percent.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(14, index, info, percent);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("shelf-row");
        row.setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                window.openBook(book);
            }
        });
        row.setOnContextMenuRequested(event -> contextMenu(book, row)
                .show(row, event.getScreenX(), event.getScreenY()));
        return row;
    }

    private String progressText(ReadingProgress progress, int total) {
        if (progress == null) {
            return "未读";
        }
        double ratio = (progress.chapterIndex + Math.max(0, progress.charOffset) / 10000.0) / total;
        ratio = Math.max(0, Math.min(1, ratio));
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
    }

    private ContextMenu contextMenu(Book book, Node anchor) {
        ContextMenu menu = new ContextMenu();
        MenuItem read = new MenuItem("阅读");
        read.setOnAction(e -> window.openBook(book));

        MenuItem download = new MenuItem("全部下载");
        download.setDisable(!book.isOnline());
        download.setOnAction(e -> downloadAll(book));

        MenuItem updateToc = new MenuItem("更新目录");
        updateToc.setDisable(!book.isOnline() || book.sourceMissing);
        updateToc.setOnAction(e -> updateToc(book));

        MenuItem info = new MenuItem("书籍信息");
        info.setOnAction(e -> showInfo(book));

        MenuItem clearCache = new MenuItem("清除缓存");
        clearCache.setOnAction(e -> clearCache(book));

        MenuItem delete = new MenuItem("删除书籍");
        delete.setOnAction(e -> delete(book));

        menu.getItems().addAll(read, download, updateToc, info, clearCache, delete);
        return menu;
    }

    private void downloadAll(Book book) {
        try {
            var task = ctx.downloadService.start(book, DownloadRangeType.ALL, null, null);
            window.status("《" + book.title + "》已加入下载队列");
            window.show("downloads");
        } catch (RuntimeException e) {
            Ui.error("无法下载", Ui.message(e));
        }
    }

    private void updateToc(Book book) {
        window.status("正在更新《" + book.title + "》的目录…");
        Ui.async("update-toc", () -> ctx.onlineBookService.refreshToc(book), added -> {
            refresh();
            window.status("目录已更新");
            Ui.info(added > 0 ? "更新完成，新增 " + added + " 章" : "目录已是最新");
        }, error -> {
            Ui.error("更新目录失败", Ui.message(error));
            window.status("更新目录失败");
        });
    }

    private void showInfo(Book book) {
        long size = ctx.bookService.cacheSize(book.id);
        int total = ctx.bookService.chapters(book.id).size();
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("书籍信息");
        alert.setHeaderText(book.title);
        alert.setContentText("作者：" + (book.author == null ? "未知" : book.author)
                + "\n来源：" + (book.isOnline() ? book.sourceName : "本地 TXT")
                + "\n章节：" + total + " 章"
                + "\n缓存：" + Ui.humanSize(size)
                + "\n加入时间：" + Ui.formatTime(book.addedAt)
                + (book.intro == null || book.intro.isBlank() ? "" : "\n\n简介：" + book.intro));
        alert.getDialogPane().setPrefWidth(480);
        alert.initOwner(window.stage());
        Ui.styleDialog(alert, window.stage());
        alert.show();
    }

    private void clearCache(Book book) {
        if (!Ui.confirm("清除缓存", "将删除《" + book.title + "》已缓存的章节正文，在线书需要联网重新获取。继续吗？")) {
            return;
        }
        ctx.bookService.clearCache(book.id);
        refresh();
        window.status("已清除《" + book.title + "》的缓存");
    }

    private void delete(Book book) {
        if (!Ui.confirm("删除书籍", "确定从书架删除《" + book.title + "》吗？本地缓存会一并删除。")) {
            return;
        }
        ctx.bookService.delete(book.id, true);
        refresh();
        window.status("已删除《" + book.title + "》");
    }

    private Node emptyState() {
        VBox box = new VBox(10);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(80, 0, 0, 0));
        box.setPrefWidth(900);
        box.getChildren().addAll(
                Ui.label("书架还是空的", "empty-title"),
                Ui.label("导入本地 TXT，或到「搜索」里用书源找一本书", "muted"),
                Ui.label("快捷键 Ctrl+O 导入 · Ctrl+F 全网搜索", "muted"));
        return box;
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        refresh();
    }
}
