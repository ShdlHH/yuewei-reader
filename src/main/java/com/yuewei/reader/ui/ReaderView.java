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
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyEvent;
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

/** 阅读器：目录侧栏 + 正文滚动区，进度按章节与章内百分比保存。 */
public class ReaderView implements AppView {

    private final AppContext ctx;
    private final MainWindow window;

    /** 版心宽度：窗口再宽正文也保持约 620px 的舒适行宽。 */
    private static final double PAGE_WIDTH = 620;
    private static final double PAGE_SIDE = 24;

    private final BorderPane root = new BorderPane();
    private final HBox toolbar = new HBox(8);
    private final HBox runningHeader = new HBox(10);
    private final HBox runningFoot = new HBox(10);
    private final Label toolbarTitle = Ui.label("", "reader-toolbar-title");
    private final Label chapterLabel = Ui.label("", "running-text", "running-chapter");
    private final Label pageLabel = Ui.label("", "running-page");
    private final Label footBook = Ui.label("", "running-text", "running-book");
    private final Label percentLabel = Ui.label("", "running-page");
    private final VBox tocBox = new VBox(6);
    private final ListView<Chapter> toc = new ListView<>();
    private final TextField tocSearch = new TextField();
    private final Label tocCount = Ui.label("", "toc-count");
    private final Label tocFooter = Ui.label("", "toc-footer");
    private final Region resizeHandle = new Region();
    private final ScrollPane scroll = new ScrollPane();
    private final VBox flow = new VBox();
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
    /** 首个正文自然段下标：不缩进、改用楷体字号，作为内页起手式。 */
    private int firstParagraphIndex = -1;
    /** 用户是否真的翻页/切章；只打开书或窗口重排不应刷新进度时间。 */
    private boolean progressDirty;
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
            saveIfDirty();
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

        Button panel = Ui.button("Aa", "ghost-button");
        panel.setOnAction(e -> openReaderPanel());

        toolbar.getStyleClass().add("reader-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8, 14, 8, 14));
        toolbar.getChildren().addAll(back, toolbarTitle, spacer, tocToggle, panel);

        Label tocTitle = Ui.label("目录", "toc-title");
        Region tocSpacer = new Region();
        HBox.setHgrow(tocSpacer, Priority.ALWAYS);

        tocSearch.setPromptText("搜索章节");
        tocSearch.getStyleClass().add("toc-search");
        tocSearch.textProperty().addListener((observable, oldValue, newValue) -> refreshTocList(false));
        HBox tocSearchWrap = new HBox(tocSearch);
        HBox.setHgrow(tocSearch, Priority.ALWAYS);
        tocSearchWrap.getStyleClass().add("toc-search-wrap");
        tocSearchWrap.setVisible(false);
        tocSearchWrap.setManaged(false);

        Button tocSearchToggle = Ui.button("搜", "toc-search-toggle");
        tocSearchToggle.setOnAction(e -> {
            boolean visible = !tocSearchWrap.isVisible();
            tocSearchWrap.setVisible(visible);
            tocSearchWrap.setManaged(visible);
            if (visible) {
                tocSearch.requestFocus();
            }
        });

        HBox tocHeader = new HBox(8, tocTitle, tocCount, tocSpacer, tocSearchToggle);
        tocHeader.setAlignment(Pos.CENTER_LEFT);
        tocHeader.getStyleClass().add("toc-header");

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
                progressDirty = true;
                loadChapter(real, 0);
            }
        });
        VBox.setVgrow(toc, Priority.ALWAYS);

        HBox tocFootBox = new HBox(tocFooter);
        tocFootBox.getStyleClass().add("toc-footer-wrap");

        tocBox.getStyleClass().add("toc-box");
        tocBox.setPadding(new Insets(0));
        tocWidth = Math.max(180, Math.min(460, ctx.settings.getInt(SettingsService.TOC_WIDTH, 236)));
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
        flow.setPadding(new Insets(12, PAGE_SIDE, 16, PAGE_SIDE));
        scroll.getStyleClass().add("reader-scroll");
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

        saveTimer.setOnFinished(e -> saveIfDirty());
        HBox main = new HBox(tocBox, resizeHandle);
        main.setFillHeight(true);

        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        runningHeader.setAlignment(Pos.CENTER_LEFT);
        runningHeader.getStyleClass().add("running-header");
        runningHeader.setPadding(new Insets(14, PAGE_SIDE, 6, PAGE_SIDE));
        runningHeader.getChildren().addAll(chapterLabel, headSpacer, pageLabel);

        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);
        runningFoot.setAlignment(Pos.CENTER_LEFT);
        runningFoot.getStyleClass().add("running-foot");
        runningFoot.setPadding(new Insets(2, PAGE_SIDE, 12, PAGE_SIDE));
        runningFoot.getChildren().addAll(footBook, footSpacer, percentLabel);

        VBox pageArea = new VBox(runningHeader, scroll, runningFoot);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        root.setFocusTraversable(true);
        root.getStyleClass().add("reader-root");
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
        root.setTop(toolbar);
        root.setLeft(main);
        root.setCenter(pageArea);
    }

    public void open(Book book, int chapterIndex, int charOffset) {
        saveIfDirty();
        saveTimer.stop();
        this.book = book;
        this.chapters = ctx.bookService.chapters(book.id);
        this.index = -1;
        this.currentChapter = null;
        this.progressDirty = false;
        this.pendingFraction = Math.max(0, Math.min(1, charOffset / 10000.0));
        this.pendingPage = -1;
        blocks.clear();
        pages.clear();
        pageIndex = 0;
        String author = book.author == null || book.author.isBlank() ? "未知作者" : book.author;
        footBook.setText(book.title + " · " + author);
        toolbarTitle.setText(book.title);
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
                    window.syncBookProgress(book);
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
        firstParagraphIndex = findFirstParagraph();
        flow.getChildren().clear();
        if (loading) {
            for (int i = 0; i < blocks.size(); i++) {
                String block = blocks.get(i);
                boolean first = i == firstParagraphIndex;
                flow.getChildren().add(block.isEmpty() ? blankSpacer()
                        : paragraphNode(first ? block.stripLeading() : ReaderStyles.indent(block), first));
            }
            flow.setOpacity(1);
        }
    }

    /** 第一个非空、且不是章节标题的自然段，作为内页起手段。 */
    private int findFirstParagraph() {
        for (int i = 0; i < blocks.size(); i++) {
            String block = blocks.get(i);
            if (block.isEmpty()) {
                continue;
            }
            if (ReaderStyles.isChapterTitle(block.stripLeading())) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private double lineGap() {
        return Math.max(0, (ctx.settings.lineSpacing() - 1) * readerFont.getSize());
    }

    private Region blankSpacer() {
        double height = readerFont.getSize() * 1.2;
        Region spacer = new Region();
        spacer.setMinHeight(height);
        spacer.setPrefHeight(height);
        spacer.setMaxHeight(height);
        return spacer;
    }

    /** 单个自然段 → 着色 TextFlow；text 传入的是已缩进文本，切页续段不会重复缩进。 */
    private TextFlow paragraphNode(String text, boolean first) {
        TextFlow line = new TextFlow();
        line.getStyleClass().add("reader-paragraph");
        if (first) {
            line.getStyleClass().add("reader-first");
        }
        line.setLineSpacing(lineGap());
        line.setMaxWidth(Double.MAX_VALUE);
        Font font = first
                ? Font.font("KaiTi", Math.min(32, ctx.settings.fontSize() + 1))
                : readerFont;
        ReaderStyles.fillRaw(line, text, font, ctx.settings.punctEnabled(),
                ReaderStyles.rules(ctx.settings),
                ReaderStyles.colors(ctx.settings, ctx.settings.theme()),
                Color.web(theme().ink));
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

    /** 键盘翻页：空格/PageDown/→/↓ 下一页，PageUp/←/↑/退格 上一页。 */
    private void handleKey(KeyEvent event) {
        if (event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
            return;
        }
        if (root.getScene() == null || root.getParent() == null) {
            return;
        }
        Node owner = root.getScene().getFocusOwner();
        if (owner instanceof TextInputControl || owner instanceof ComboBoxBase) {
            return;
        }
        boolean forward;
        switch (event.getCode()) {
            case SPACE, PAGE_DOWN, RIGHT, DOWN -> forward = true;
            case PAGE_UP, LEFT, UP, BACK_SPACE -> forward = false;
            default -> {
                return;
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastPageTurn < 150) {
            return;
        }
        lastPageTurn = now;
        event.consume();
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
                progressDirty = true;
                showPage(pageIndex + 1, true);
            } else if (canJump) {
                jumpChapter(true);
            }
        } else {
            if (pageIndex > 0) {
                progressDirty = true;
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

    /** 版心随窗口收窄：优先保证正文 620px，边距不足时先压缩留白，最小 24px。 */
    private void applyPageWidth() {
        double viewportWidth = scroll.getViewportBounds().getWidth();
        double side = PAGE_SIDE;
        if (viewportWidth > PAGE_WIDTH + PAGE_SIDE * 2) {
            side = Math.floor((viewportWidth - PAGE_WIDTH) / 2);
        }
        flow.setPadding(new Insets(12, side, 16, side));
        runningHeader.setPadding(new Insets(14, side, 6, side));
        runningFoot.setPadding(new Insets(2, side, 12, side));
    }

    /** 把整章切分成若干页：每页只保存该页的节点，页末不留半截段落，超长段落才按字符续页。 */
    private void paginate() {
        pages.clear();
        pageIndex = 0;
        if (blocks.isEmpty()) {
            updatePercent();
            return;
        }
        applyPageWidth();
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
        double gap = lineGap();
        double blankLine = readerFont.getSize() * 1.2;
        List<Node> page = new ArrayList<>();
        double used = 0;
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            String block = blocks.get(blockIndex);
            boolean first = blockIndex == firstParagraphIndex;
            if (block.isEmpty()) {
                double total = (page.isEmpty() ? 0 : gap) + blankLine;
                if (!page.isEmpty() && used + total > usable) {
                    pages.add(page);
                    page = new ArrayList<>();
                    used = 0;
                    total = blankLine;
                }
                page.add(blankSpacer());
                used += total;
                continue;
            }
            String text = first ? block.stripLeading() : ReaderStyles.indent(block);
            int offset = 0;
            while (offset < text.length()) {
                String rest = text.substring(offset);
                TextFlow node = paragraphNode(rest, first);
                double height = node.prefHeight(contentWidth);
                double total = (page.isEmpty() ? 0 : gap) + height;
                if (used + total <= usable) {
                    page.add(node);
                    used += total;
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
                int take = fittingCount(rest, contentWidth, usable, first);
                page.add(paragraphNode(rest.substring(0, take), first));
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
    private int fittingCount(String text, double width, double maxHeight, boolean first) {
        int low = 1;
        int high = text.length();
        int best = 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (paragraphNode(text.substring(0, mid), first).prefHeight(width) <= maxHeight) {
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
        for (int i = 0; i < blocks.size(); i++) {
            String block = blocks.get(i);
            boolean first = i == firstParagraphIndex;
            nodes.add(block.isEmpty() ? blankSpacer()
                    : paragraphNode(first ? block.stripLeading() : ReaderStyles.indent(block), first));
        }
        return nodes;
    }

    /** 整页替换：只把本页节点放进容器，因此不会出现上一页末尾残留在本页顶部。 */
    private void showPage(int index, boolean animate) {
        if (pages.isEmpty()) {
            pageLabel.setText("0 / 0");
            updatePercent();
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
        if (!loading && book != null && progressDirty) {
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
            progressDirty = true;
            loadChapter(index + 1, 0);
        } else {
            if (index <= 0) {
                return;
            }
            lastChapterJump = System.currentTimeMillis();
            progressDirty = true;
            loadChapter(index - 1, 1);
        }
    }

    private Ui.Theme theme() {
        return Ui.theme(ctx.settings.theme());
    }

    private void updatePercent() {
        if (pages.isEmpty()) {
            pageLabel.setText("0 / 0");
        } else {
            pageLabel.setText((pageIndex + 1) + " / " + pages.size());
        }
        if (book == null || chapters.isEmpty()) {
            percentLabel.setText("0.0%");
        } else {
            double inChapter = pages.isEmpty() ? 0 : (double) (pageIndex + 1) / pages.size();
            double overall = Math.min(1, (index + inChapter) / chapters.size());
            percentLabel.setText(String.format(java.util.Locale.ROOT, "%.1f%%", overall * 100));
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
        if (chapters.isEmpty()) {
            tocCount.setText("");
        } else {
            int chapterNo = index < 0 ? 1 : index + 1;
            tocCount.setText("第 " + chapterNo + " / " + chapters.size() + " 章");
        }
        updateTocFooter();
    }

    private void updateTocFooter() {
        if (book == null || chapters.isEmpty()) {
            tocFooter.setText("");
            return;
        }
        tocFooter.setText("已缓存 " + cachedCount + " 章");
    }

    /** 关闭窗口前立即落库，避免退出时丢失当前页。 */
    public void saveNow() {
        saveIfDirty();
    }

    private void saveIfDirty() {
        if (progressDirty) {
            saveProgress();
        }
    }

    private void saveProgress() {
        if (book == null || chapters.isEmpty() || index < 0) {
            return;
        }
        int percent = pages.size() <= 1 ? 0
                : (int) Math.round(pageIndex * 10000.0 / (pages.size() - 1));
        ctx.bookService.saveProgress(book.id, index, percent);
        progressDirty = false;
    }

    /** 阅读面板：字号、行距、字体、主题与着色，调整即时生效并持久化。 */
    private void openReaderPanel() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(window.stage());
        dialog.setTitle("阅读设置");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Ui.styleDialog(dialog, window.stage());

        Button prev = Ui.button("上一章", "ghost-button");
        prev.setOnAction(e -> previousChapter());
        Button next = Ui.button("下一章", "ghost-button");
        next.setOnAction(e -> nextChapter());
        HBox chapterRow = new HBox(8, prev, next);
        chapterRow.setAlignment(Pos.CENTER_LEFT);

        ComboBox<String> families = new ComboBox<>();
        families.getItems().setAll(Ui.fontFamilies());
        families.setValue(ctx.settings.fontFamily());
        families.setPrefWidth(260);

        Spinner<Integer> size = new Spinner<>(12, 32, ctx.settings.fontSize());
        Spinner<Double> spacing = new Spinner<>(1.2, 3.0, ctx.settings.lineSpacing(), 0.1);
        size.setEditable(true);
        spacing.setEditable(true);

        ComboBox<String> themeChoice = new ComboBox<>();
        themeChoice.getItems().addAll("纸白", "夜间", "护眼");
        themeChoice.setValue(themeLabel(ctx.settings.theme()));
        themeChoice.setPrefWidth(260);

        CheckBox highlight = new CheckBox("标点着色");
        highlight.setSelected(ctx.settings.punctEnabled());
        highlight.selectedProperty().addListener((observable, oldValue, newValue) -> {
            ctx.settings.put(SettingsService.PUNCT_ENABLED, String.valueOf(newValue));
            applyStyle();
        });
        themeChoice.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null) {
                return;
            }
            String key = themeKeyOf(newValue);
            ctx.settings.put(SettingsService.THEME, key);
            dialog.getDialogPane().setStyle(Ui.themeTokens(Ui.theme(key)));
            applyStyle();
            window.applyTheme();
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.add(new Label("章节"), 0, 0);
        grid.add(chapterRow, 1, 0);
        grid.add(new Label("字体"), 0, 1);
        grid.add(families, 1, 1);
        grid.add(new Label("字号"), 0, 2);
        grid.add(size, 1, 2);
        grid.add(new Label("行距（倍）"), 0, 3);
        grid.add(spacing, 1, 3);
        grid.add(new Label("主题"), 0, 4);
        grid.add(themeChoice, 1, 4);
        grid.add(new Label("标点着色"), 0, 5);
        grid.add(highlight, 1, 5);

        Button reset = Ui.button("恢复默认", "ghost-button");
        reset.setOnAction(e -> {
            families.setValue(SettingsService.DEFAULT_FONT_FAMILY);
            size.getValueFactory().setValue(18);
            spacing.getValueFactory().setValue(2.0);
            themeChoice.setValue("纸白");
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
        content.setPrefWidth(400);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private static String themeLabel(String key) {
        return switch (key == null ? "" : key) {
            case "night" -> "夜间";
            case "green" -> "护眼";
            default -> "纸白";
        };
    }

    private static String themeKeyOf(String label) {
        return switch (label == null ? "" : label) {
            case "夜间" -> "night";
            case "护眼" -> "green";
            default -> "paper";
        };
    }

    private void applyStyle() {
        int size = ctx.settings.fontSize();
        String family = Fonts.resolve(ctx.settings.fontFamily());
        Ui.Theme theme = theme();

        readerFont = family == null ? Font.font(size) : Font.font(family, size);
        // 段落被拆成独立 TextFlow，段间距也要跟随行距设置，否则只在段内换行时生效
        flow.setSpacing(lineGap());
        // 重新排版会重建分页，用当前页比例保住阅读位置
        pendingPage = -1;
        if (!pages.isEmpty()) {
            pendingFraction = pages.size() <= 1 ? 0 : (double) pageIndex / (pages.size() - 1);
        }
        // 一处覆盖主题令牌，正文、工具栏、目录、书眉与页脚都由 CSS 统一切换
        root.setStyle(Ui.themeTokens(theme));
        setContent(content);
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
        root.requestFocus();
    }

    public void previousChapter() {
        if (index > 0) {
            progressDirty = true;
            loadChapter(index - 1, 0);
        }
    }

    public void nextChapter() {
        if (index + 1 < chapters.size()) {
            progressDirty = true;
            loadChapter(index + 1, 0);
        }
    }
}
