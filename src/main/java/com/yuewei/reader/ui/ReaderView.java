package com.yuewei.reader.ui;

import com.yuewei.reader.application.AppContext;
import com.yuewei.reader.application.SettingsService;
import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.Chapter;
import com.yuewei.reader.domain.ContentState;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 阅读器：目录侧栏 + 正文滚动区，进度按章节与章内百分比保存。 */
public class ReaderView implements AppView {

    private static final Map<String, Theme> THEMES = Map.of(
            "paper", new Theme("纸白", "#f6f1e7", "#2f2a24", "#ece4d4"),
            "night", new Theme("夜间", "#1b1c20", "#c8c6c0", "#24262c"),
            "green", new Theme("护眼", "#e6efe4", "#2d3a2b", "#d8e6d5"));

    private static final class Theme {
        final String label;
        final String background;
        final String foreground;
        final String bar;

        Theme(String label, String background, String foreground, String bar) {
            this.label = label;
            this.background = background;
            this.foreground = foreground;
            this.bar = bar;
        }
    }

    private final AppContext ctx;
    private final MainWindow window;

    private final BorderPane root = new BorderPane();
    private final HBox toolbar = new HBox(8);
    private final Label bookLabel = Ui.label("", "reader-book");
    private final Label chapterLabel = Ui.label("", "reader-chapter");
    private final Label percentLabel = Ui.label("", "reader-percent");
    private final VBox tocBox = new VBox(6);
    private final ListView<Chapter> toc = new ListView<>();
    private final TextField tocSearch = new TextField();
    private final Label tocCount = Ui.label("", "toc-count");
    private final Label tocFooter = Ui.label("", "toc-footer");
    private final Region resizeHandle = new Region();
    private final ScrollPane scroll = new ScrollPane();
    private final VBox flow = new VBox();
    private final Button highlightToggle = Ui.button("着色 开", "ghost-button");
    private final PauseTransition saveTimer = new PauseTransition(Duration.millis(700));

    private Book book;
    private List<Chapter> chapters = List.of();
    private int index;
    private boolean loading;
    private boolean selecting;
    private double pendingFraction = -1;
    private String content = "";
    private Font readerFont = Font.font(18);
    private Chapter currentChapter;
    private int cachedCount;
    private double tocWidth = 270;
    private double dragStartX;
    private double dragStartWidth;
    private long lastChapterJump;
    private double pageDelta;
    private long lastPageTurn;
    /** 当前章节的正文自然段（空行用空串表示），分页时按视口高度切分。 */
    private final List<String> blocks = new ArrayList<>();
    /** 预分页结果：每页只保存本页的段落节点，阅读时整页替换。 */
    private final List<List<Node>> pages = new ArrayList<>();
    private int pageIndex;
    private int pendingPage = -1;
    private boolean fadeNextRender;
    private Timeline pageAnimation;

    public ReaderView(AppContext ctx, MainWindow window) {
        this.ctx = ctx;
        this.window = window;
        build();
    }

    private void build() {
        Button back = Ui.button("← 书架", "ghost-button");
        back.setOnAction(e -> {
            saveProgress();
            window.backToShelf();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button tocToggle = Ui.button("目录", "ghost-button");
        tocToggle.setOnAction(e -> {
            boolean visible = !tocBox.isVisible();
            tocBox.setVisible(visible);
            tocBox.setManaged(visible);
            resizeHandle.setVisible(visible);
            resizeHandle.setManaged(visible);
        });

        Button smaller = Ui.button("A－", "ghost-button");
        smaller.setOnAction(e -> changeFontSize(-1));
        Button larger = Ui.button("A＋", "ghost-button");
        larger.setOnAction(e -> changeFontSize(1));
        Button typesetting = Ui.button("排版", "ghost-button");
        typesetting.setOnAction(e -> openTypesetting());
        Button theme = Ui.button("主题", "ghost-button");
        theme.setOnAction(e -> cycleTheme());
        highlightToggle.setOnAction(e -> toggleHighlight());

        Button prev = Ui.button("上一章", "ghost-button");
        prev.setOnAction(e -> previousChapter());
        Button next = Ui.button("下一章", "ghost-button");
        next.setOnAction(e -> nextChapter());

        toolbar.getStyleClass().add("reader-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8, 14, 8, 14));
        toolbar.getChildren().addAll(back, bookLabel, chapterLabel, spacer, prev, next,
                tocToggle, smaller, larger, typesetting, highlightToggle, theme, percentLabel);

        Label tocTitle = Ui.label("目录", "toc-title");
        Region tocSpacer = new Region();
        HBox.setHgrow(tocSpacer, Priority.ALWAYS);
        HBox tocHeader = new HBox(8, tocTitle, tocCount, tocSpacer, Ui.label("读", "toc-seal"));
        tocHeader.setAlignment(Pos.CENTER_LEFT);
        tocHeader.getStyleClass().add("toc-header");

        tocSearch.setPromptText("搜索章节");
        tocSearch.getStyleClass().add("toc-search");
        tocSearch.textProperty().addListener((observable, oldValue, newValue) -> refreshTocList(false));
        HBox tocSearchWrap = new HBox(tocSearch);
        HBox.setHgrow(tocSearch, Priority.ALWAYS);
        tocSearchWrap.getStyleClass().add("toc-search-wrap");

        toc.getStyleClass().add("toc-list");
        toc.setFixedCellSize(36);
        toc.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Chapter item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Label name = new Label(item.title);
                name.getStyleClass().add("toc-name");
                name.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(name, Priority.ALWAYS);
                HBox row = new HBox(8, name);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("toc-row");
                if (item.state == ContentState.CACHED) {
                    Region dot = new Region();
                    dot.getStyleClass().add("toc-dot");
                    row.getChildren().add(dot);
                }
                if (item == currentChapter) {
                    Label reading = new Label("在读");
                    reading.getStyleClass().add("toc-reading");
                    row.getChildren().add(reading);
                }
                setGraphic(row);
            }
        });
        toc.getSelectionModel().selectedIndexProperty().addListener((observable, oldValue, newValue) -> {
            if (selecting || newValue == null) {
                return;
            }
            int pos = newValue.intValue();
            if (pos < 0 || pos >= toc.getItems().size()) {
                return;
            }
            int real = chapters.indexOf(toc.getItems().get(pos));
            if (real >= 0 && real != index) {
                loadChapter(real, 0);
            }
        });
        VBox.setVgrow(toc, Priority.ALWAYS);

        HBox tocFootBox = new HBox(tocFooter);
        tocFootBox.getStyleClass().add("toc-footer-wrap");

        tocBox.getStyleClass().add("toc-box");
        tocBox.setPadding(new Insets(0));
        tocWidth = Math.max(180, Math.min(460, ctx.settings.getInt(SettingsService.TOC_WIDTH, 270)));
        tocBox.setPrefWidth(tocWidth);
        tocBox.setMinWidth(180);
        tocBox.setMaxWidth(460);
        tocBox.setMaxHeight(Double.MAX_VALUE);
        tocBox.getChildren().addAll(tocHeader, tocSearchWrap, toc, tocFootBox);

        resizeHandle.getStyleClass().add("reader-resize");
        resizeHandle.setCursor(Cursor.H_RESIZE);
        resizeHandle.setPrefWidth(6);
        resizeHandle.setMinWidth(6);
        resizeHandle.setMaxWidth(6);
        resizeHandle.setMaxHeight(Double.MAX_VALUE);
        resizeHandle.setOnMousePressed(e -> {
            dragStartX = e.getScreenX();
            dragStartWidth = tocBox.getWidth();
        });
        resizeHandle.setOnMouseDragged(e -> {
            double width = Math.max(180, Math.min(460, dragStartWidth + e.getScreenX() - dragStartX));
            tocWidth = width;
            tocBox.setPrefWidth(width);
        });
        resizeHandle.setOnMouseReleased(e ->
                ctx.settings.put(SettingsService.TOC_WIDTH, String.valueOf(Math.round(tocWidth))));

        flow.getStyleClass().add("reader-flow");
        flow.setFillWidth(true);
        flow.setPadding(new Insets(18, 36, 20, 36));
        scroll.setFitToWidth(true);
        scroll.setContent(flow);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.viewportBoundsProperty().addListener((observable, oldBounds, newBounds) -> {
            if (oldBounds.getWidth() != newBounds.getWidth() || oldBounds.getHeight() != newBounds.getHeight()) {
                Platform.runLater(this::repaginateKeepingPage);
            }
        });
        scroll.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() == 0 || event.isShortcutDown()) {
                return;
            }
            smoothScroll(event.getDeltaY());
            event.consume();
        });

        saveTimer.setOnFinished(e -> saveProgress());

        HBox main = new HBox(tocBox, resizeHandle);
        main.setFillHeight(true);
        root.setTop(toolbar);
        root.setLeft(main);
        root.setCenter(scroll);
    }

    public void open(Book book, int chapterIndex, int charOffset) {
        saveProgress();
        this.book = book;
        this.chapters = ctx.bookService.chapters(book.id);
        this.index = -1;
        this.currentChapter = null;
        this.pendingFraction = Math.max(0, Math.min(1, charOffset / 10000.0));
        this.pendingPage = -1;
        blocks.clear();
        pages.clear();
        pageIndex = 0;
        bookLabel.setText(book.title);
        tocSearch.clear();
        refreshTocList(false);
        applyStyle();
        loadChapter(chapterIndex, -1);
        window.status("《" + book.title + "》 共 " + chapters.size() + " 章");
    }

    private void loadChapter(int target, double fraction) {
        if (book == null) {
            return;
        }
        if (chapters.isEmpty()) {
            chapterLabel.setText("");
            setContent("这本书还没有章节。");
            return;
        }
        int next = Math.max(0, Math.min(chapters.size() - 1, target));
        index = next;
        Chapter chapter = chapters.get(next);
        currentChapter = chapter;
        chapterLabel.setText(chapter.title);
        refreshTocList(true);
        if (fraction >= 0) {
            pendingFraction = fraction;
        }
        pendingPage = -1;
        loading = true;
        setContent("正在加载章节…");
        Ui.async("read-chapter",
                () -> ctx.onlineBookService.loadContent(book, chapter),
                loaded -> {
                    loading = false;
                    fadeNextRender = true;
                    setContent(loaded);
                },
                error -> {
                    loading = false;
                    String message = Ui.message(error);
                    setContent("本章加载失败：" + message + "\n\n可以在书源页校验规则，或先下载本书离线阅读。");
                    window.status("章节加载失败：" + message);
                });
    }

    private void setContent(String value) {
        content = value == null ? "" : value;
        renderContent();
        Platform.runLater(() -> Platform.runLater(this::afterContentLayout));
    }

    /** 章节文本先拆成自然段备用；加载提示直接显示，正文等预分页完成后再上屏。 */
    private void renderContent() {
        blocks.clear();
        pages.clear();
        pageIndex = 0;
        for (String paragraph : content.split("\n", -1)) {
            blocks.add(paragraph.isBlank() ? "" : paragraph);
        }
        flow.getChildren().clear();
        if (loading) {
            for (String block : blocks) {
                flow.getChildren().add(block.isEmpty() ? blankSpacer() : paragraphNode(ReaderStyles.indent(block)));
            }
            flow.setOpacity(1);
        }
    }

    private double lineGap() {
        return Math.max(0, (ctx.settings.lineSpacing() - 1) * readerFont.getSize());
    }

    private Region blankSpacer() {
        double height = readerFont.getSize() * 1.2 + lineGap();
        Region spacer = new Region();
        spacer.setMinHeight(height);
        spacer.setPrefHeight(height);
        spacer.setMaxHeight(height);
        return spacer;
    }

    /** 单个自然段 → 着色 TextFlow；text 传入的是已缩进文本，切页续段不会重复缩进。 */
    private TextFlow paragraphNode(String text) {
        TextFlow line = new TextFlow();
        line.getStyleClass().add("reader-paragraph");
        line.setLineSpacing(lineGap());
        line.setMaxWidth(Double.MAX_VALUE);
        ReaderStyles.fillRaw(line, text, readerFont, ctx.settings.punctEnabled(),
                ReaderStyles.rules(ctx.settings),
                ReaderStyles.colors(ctx.settings, ctx.settings.theme()),
                Color.web(theme().foreground));
        return line;
    }

    /** 滚轮整页翻页；滚动累计到阈值再翻，避免触控板高频事件连翻多页。 */
    private void smoothScroll(double deltaY) {
        pageDelta += deltaY;
        if (Math.abs(pageDelta) < 20) {
            return;
        }
        boolean forward = pageDelta < 0;
        pageDelta = 0;
        long now = System.currentTimeMillis();
        if (now - lastPageTurn < 220) {
            return;
        }
        lastPageTurn = now;
        turnPage(forward);
    }

    /** 按预分页翻页；最后一页继续后翻进下一章，首页前翻回上一章末页。 */
    private void turnPage(boolean forward) {
        boolean canJump = !loading && System.currentTimeMillis() - lastChapterJump > 350;
        if (pages.isEmpty()) {
            if (canJump) {
                jumpChapter(forward);
            }
            return;
        }
        if (forward) {
            if (pageIndex < pages.size() - 1) {
                showPage(pageIndex + 1, true);
            } else if (canJump) {
                jumpChapter(true);
            }
        } else {
            if (pageIndex > 0) {
                showPage(pageIndex - 1, true);
            } else if (canJump) {
                jumpChapter(false);
            }
        }
    }

    /** 内容就绪后先完成整章分页，再按页显示。 */
    private void afterContentLayout() {
        if (loading || flow.getScene() == null) {
            return;
        }
        paginate();
        int target = 0;
        if (pendingPage >= 0) {
            target = pendingPage;
        } else if (pendingFraction >= 0 && !pages.isEmpty()) {
            target = (int) Math.round(pendingFraction * (pages.size() - 1));
        }
        pendingPage = -1;
        pendingFraction = -1;
        boolean animate = fadeNextRender;
        fadeNextRender = false;
        showPage(target, animate);
    }

    /** 把整章切分成若干页：每页只保存该页的节点，页末不留半截段落，超长段落才按字符续页。 */
    private void paginate() {
        pages.clear();
        pageIndex = 0;
        if (blocks.isEmpty()) {
            updatePercent();
            return;
        }
        double width = scroll.getViewportBounds().getWidth();
        Insets padding = flow.getInsets();
        double usable = scroll.getViewportBounds().getHeight() - padding.getTop() - padding.getBottom();
        if (width <= 40 || usable <= 40) {
            pages.add(new ArrayList<>(List.of()));
            pages.get(0).addAll(loadingNodes());
            updatePercent();
            return;
        }
        double contentWidth = Math.max(80, width - padding.getLeft() - padding.getRight());
        List<Node> page = new ArrayList<>();
        double used = 0;
        for (String block : blocks) {
            if (block.isEmpty()) {
                double height = readerFont.getSize() * 1.2 + lineGap();
                if (!page.isEmpty() && used + height > usable) {
                    pages.add(page);
                    page = new ArrayList<>();
                    used = 0;
                }
                page.add(blankSpacer());
                used += height;
                continue;
            }
            String text = ReaderStyles.indent(block);
            int offset = 0;
            while (offset < text.length()) {
                String rest = text.substring(offset);
                TextFlow node = paragraphNode(rest);
                double height = node.prefHeight(contentWidth);
                if (used + height <= usable) {
                    page.add(node);
                    used += height;
                    offset = text.length();
                    continue;
                }
                if (!page.isEmpty()) {
                    pages.add(page);
                    page = new ArrayList<>();
                    used = 0;
                    continue;
                }
                // 空页也放不下：按能容纳的字数切开，剩余部分留给下一页
                int take = fittingCount(rest, contentWidth, usable);
                page.add(paragraphNode(rest.substring(0, take)));
                pages.add(page);
                page = new ArrayList<>();
                used = 0;
                offset += take;
            }
        }
        if (!page.isEmpty()) {
            pages.add(page);
        }
        if (pages.isEmpty()) {
            pages.add(new ArrayList<>());
        }
        updatePercent();
    }

    /** 二分查找在给定高度内最多能排下的字符数，至少取一个字符避免死循环。 */
    private int fittingCount(String text, double width, double maxHeight) {
        int low = 1;
        int high = text.length();
        int best = 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (paragraphNode(text.substring(0, mid)).prefHeight(width) <= maxHeight) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    private List<Node> loadingNodes() {
        List<Node> nodes = new ArrayList<>();
        for (String block : blocks) {
            nodes.add(block.isEmpty() ? blankSpacer() : paragraphNode(ReaderStyles.indent(block)));
        }
        return nodes;
    }

    /** 整页替换：只把本页节点放进容器，因此不会出现上一页末尾残留在本页顶部。 */
    private void showPage(int index, boolean animate) {
        if (pages.isEmpty()) {
            percentLabel.setText("0/0");
            updateTocFooter();
            return;
        }
        int previous = pageIndex;
        pageIndex = Math.max(0, Math.min(pages.size() - 1, index));
        boolean forward = pageIndex >= previous;
        flow.getChildren().setAll(pages.get(pageIndex));
        scroll.setVvalue(0);
        if (animate) {
            playPageAnimation(forward);
        } else {
            stopPageAnimation();
            flow.setOpacity(1);
            flow.setTranslateY(0);
        }
        updatePercent();
        if (!loading && book != null) {
            saveTimer.playFromStart();
        }
    }

    /** 翻页动画：新页淡入并沿阅读方向轻移 18px，缓出收住，避免整页滚动带来的跳动感。 */
    private void playPageAnimation(boolean forward) {
        stopPageAnimation();
        double offset = forward ? 18 : -18;
        flow.setOpacity(0);
        flow.setTranslateY(offset);
        pageAnimation = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(flow.opacityProperty(), 0),
                        new KeyValue(flow.translateYProperty(), offset)),
                new KeyFrame(Duration.millis(200),
                        new KeyValue(flow.opacityProperty(), 1, Interpolator.EASE_OUT),
                        new KeyValue(flow.translateYProperty(), 0, Interpolator.EASE_OUT)));
        pageAnimation.play();
    }

    private void stopPageAnimation() {
        if (pageAnimation != null) {
            pageAnimation.stop();
            pageAnimation = null;
        }
    }

    /** 窗口尺寸或排版变化后重新分页，并按页比例回到原来的阅读位置。 */
    private void repaginateKeepingPage() {
        if (loading || blocks.isEmpty()) {
            return;
        }
        double ratio = pages.size() <= 1 ? 0 : (double) pageIndex / (pages.size() - 1);
        paginate();
        showPage((int) Math.round(ratio * Math.max(0, pages.size() - 1)), false);
    }

    /** 翻章：往后落在新章开头，往前落在上一章结尾。 */
    private void jumpChapter(boolean forward) {
        if (forward) {
            if (index + 1 >= chapters.size()) {
                return;
            }
            lastChapterJump = System.currentTimeMillis();
            loadChapter(index + 1, 0);
        } else {
            if (index <= 0) {
                return;
            }
            lastChapterJump = System.currentTimeMillis();
            loadChapter(index - 1, 1);
        }
    }

    private Theme theme() {
        return THEMES.getOrDefault(ctx.settings.theme(), THEMES.get("paper"));
    }

    private void toggleHighlight() {
        boolean enabled = !ctx.settings.punctEnabled();
        ctx.settings.put(SettingsService.PUNCT_ENABLED, String.valueOf(enabled));
        applyStyle();
    }

    private void updatePercent() {
        if (pages.isEmpty()) {
            percentLabel.setText("0/0");
        } else {
            percentLabel.setText((pageIndex + 1) + "/" + pages.size());
        }
        updateTocFooter();
    }

    /** 按搜索词重建目录列表；followCurrent 为 true 时选中并滚动到当前章。 */
    private void refreshTocList(boolean followCurrent) {
        String query = tocSearch.getText() == null ? "" : tocSearch.getText().trim();
        List<Chapter> visible = new ArrayList<>();
        cachedCount = 0;
        for (Chapter chapter : chapters) {
            if (chapter.state == ContentState.CACHED) {
                cachedCount++;
            }
            if (query.isEmpty() || (chapter.title != null && chapter.title.contains(query))) {
                visible.add(chapter);
            }
        }
        selecting = true;
        toc.getItems().setAll(visible);
        if (followCurrent && currentChapter != null) {
            int pos = visible.indexOf(currentChapter);
            if (pos >= 0) {
                toc.getSelectionModel().select(pos);
                toc.scrollTo(Math.max(0, pos - 3));
            } else {
                toc.getSelectionModel().clearSelection();
            }
        } else {
            toc.getSelectionModel().clearSelection();
            if (!query.isEmpty()) {
                toc.scrollTo(0);
            }
        }
        selecting = false;
        tocCount.setText("共 " + chapters.size() + " 章");
        updateTocFooter();
    }

    private void updateTocFooter() {
        if (book == null || chapters.isEmpty()) {
            tocFooter.setText("");
            return;
        }
        int chapterNo = index < 0 ? 0 : index + 1;
        String page = pages.isEmpty() ? "0/0" : (pageIndex + 1) + "/" + pages.size();
        tocFooter.setText("已缓存 " + cachedCount + " 章 · 第 " + chapterNo + " 章 · " + page + " 页");
    }

    private static String rgba(Color color, double opacity) {
        return String.format("rgba(%d,%d,%d,%.2f)",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255),
                opacity);
    }

    private void saveProgress() {
        if (book == null || chapters.isEmpty() || index < 0) {
            return;
        }
        int percent = pages.size() <= 1 ? 0
                : (int) Math.round(pageIndex * 10000.0 / (pages.size() - 1));
        ctx.bookService.saveProgress(book.id, index, percent);
    }

    private void changeFontSize(int delta) {
        int size = Math.max(12, Math.min(32, ctx.settings.fontSize() + delta));
        ctx.settings.put(SettingsService.FONT_SIZE, String.valueOf(size));
        applyStyle();
    }

    /** 阅读中排版面板：字体取系统已安装字体，调整即时生效并持久化。 */
    private void openTypesetting() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(window.stage());
        dialog.setTitle("排版设置");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        if (root.getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(root.getScene().getStylesheets());
        }

        ComboBox<String> families = new ComboBox<>();
        families.getItems().setAll(Ui.fontFamilies());
        families.setValue(ctx.settings.fontFamily());
        families.setPrefWidth(260);

        Spinner<Integer> size = new Spinner<>(12, 32, ctx.settings.fontSize());
        Spinner<Double> spacing = new Spinner<>(1.2, 3.0, ctx.settings.lineSpacing(), 0.1);
        size.setEditable(true);
        spacing.setEditable(true);

        CheckBox highlight = new CheckBox("标点着色");
        highlight.setSelected(ctx.settings.punctEnabled());
        highlight.selectedProperty().addListener((observable, oldValue, newValue) -> {
            ctx.settings.put(SettingsService.PUNCT_ENABLED, String.valueOf(newValue));
            applyStyle();
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.add(new Label("字体"), 0, 0);
        grid.add(families, 1, 0);
        grid.add(new Label("字号"), 0, 1);
        grid.add(size, 1, 1);
        grid.add(new Label("行距（倍）"), 0, 2);
        grid.add(spacing, 1, 2);
        grid.add(new Label("标点着色"), 0, 3);
        grid.add(highlight, 1, 3);

        Button reset = Ui.button("恢复默认", "ghost-button");
        reset.setOnAction(e -> {
            families.setValue(SettingsService.DEFAULT_FONT_FAMILY);
            size.getValueFactory().setValue(18);
            spacing.getValueFactory().setValue(1.8);
        });

        families.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                ctx.settings.put(SettingsService.FONT_FAMILY, newValue);
                applyStyle();
            }
        });
        size.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                ctx.settings.put(SettingsService.FONT_SIZE, String.valueOf(newValue));
                applyStyle();
            }
        });
        spacing.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                ctx.settings.put(SettingsService.LINE_SPACING, String.valueOf(newValue));
                applyStyle();
            }
        });

        VBox content = new VBox(12, grid, reset,
                Ui.label("调整会立即应用到正文并自动保存；关闭窗口即可。", "muted"));
        content.setPadding(new Insets(16, 20, 8, 20));
        content.setPrefWidth(380);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private void cycleTheme() {
        String current = ctx.settings.theme();
        String next = switch (current) {
            case "night" -> "green";
            case "green" -> "paper";
            default -> "night";
        };
        ctx.settings.put(SettingsService.THEME, next);
        applyStyle();
    }

    private void applyStyle() {
        int size = ctx.settings.fontSize();
        double spacing = ctx.settings.lineSpacing();
        String family = Fonts.resolve(ctx.settings.fontFamily());
        Theme theme = theme();

        readerFont = family == null ? Font.font(size) : Font.font(family, size);
        highlightToggle.setText(ctx.settings.punctEnabled() ? "着色 开" : "着色 关");
        // 重新排版会重建分页，用当前页比例保住阅读位置
        pendingPage = -1;
        if (!pages.isEmpty()) {
            pendingFraction = pages.size() <= 1 ? 0 : (double) pageIndex / (pages.size() - 1);
        }
        flow.setStyle("-fx-background-color:" + theme.background + ";");
        scroll.setStyle("-fx-background:" + theme.background + "; -fx-background-color:" + theme.background + ";");
        toolbar.setStyle("-fx-background-color:" + theme.bar + ";");
        applyTocStyle(theme);
        root.setStyle("-fx-background-color:" + theme.background + ";");
        setContent(content);
    }

    /** 目录配色跟随主题：通过 looked-up color 让 CSS 与代码共用一套颜色。 */
    private void applyTocStyle(Theme theme) {
        String key = ctx.settings.theme();
        Color ink = Color.web(theme.foreground);
        String accent = "night".equals(key) ? "#e08b7d" : "#a63d32";
        String selected = switch (key) {
            case "night" -> "rgba(224,139,125,0.20)";
            case "green" -> "rgba(166,61,50,0.12)";
            default -> "rgba(166,61,50,0.10)";
        };
        String hover = switch (key) {
            case "night" -> "rgba(255,255,255,0.05)";
            case "green" -> "rgba(166,61,50,0.06)";
            default -> "rgba(166,61,50,0.05)";
        };
        String field = switch (key) {
            case "night" -> "rgba(255,255,255,0.06)";
            case "green" -> "rgba(255,255,255,0.45)";
            default -> "rgba(255,253,249,0.65)";
        };
        tocBox.setStyle("-fx-background-color:" + theme.bar + ";"
                + "-toc-ink:" + theme.foreground + ";"
                + "-toc-muted:" + rgba(ink, 0.62) + ";"
                + "-toc-line:" + rgba(ink, 0.14) + ";"
                + "-toc-accent:" + accent + ";"
                + "-toc-selected:" + selected + ";"
                + "-toc-hover:" + hover + ";"
                + "-toc-field:" + field + ";");
        resizeHandle.setStyle("-fx-border-color: transparent transparent transparent " + rgba(ink, 0.18)
                + "; -fx-border-width: 0 0 0 1;");
    }

    /** 设置页保存后调用，让已打开的阅读界面立即套用新样式。 */
    public void refreshStyle() {
        applyStyle();
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        applyStyle();
    }

    public void previousChapter() {
        if (index > 0) {
            loadChapter(index - 1, 0);
        }
    }

    public void nextChapter() {
        if (index + 1 < chapters.size()) {
            loadChapter(index + 1, 0);
        }
    }
}
