package com.yuewei.reader.ui;

import com.yuewei.reader.application.AppContext;
import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.Chapter;
import com.yuewei.reader.domain.ContentState;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

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
    private final ScrollPane scroll = new ScrollPane();
    private final TextFlow flow = new TextFlow();
    private final Text text = new Text();
    private final PauseTransition saveTimer = new PauseTransition(Duration.millis(700));

    private Book book;
    private List<Chapter> chapters = List.of();
    private int index;
    private boolean loading;
    private boolean selecting;
    private double pendingFraction = -1;

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
        });

        Button smaller = Ui.button("A－", "ghost-button");
        smaller.setOnAction(e -> changeFontSize(-1));
        Button larger = Ui.button("A＋", "ghost-button");
        larger.setOnAction(e -> changeFontSize(1));
        Button theme = Ui.button("主题", "ghost-button");
        theme.setOnAction(e -> cycleTheme());

        Button prev = Ui.button("上一章", "ghost-button");
        prev.setOnAction(e -> previousChapter());
        Button next = Ui.button("下一章", "ghost-button");
        next.setOnAction(e -> nextChapter());

        toolbar.getStyleClass().add("reader-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8, 14, 8, 14));
        toolbar.getChildren().addAll(back, bookLabel, chapterLabel, spacer, prev, next,
                tocToggle, smaller, larger, theme, percentLabel);

        Label tocTitle = Ui.label("目录", "toc-title");
        toc.getStyleClass().add("toc-list");
        toc.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Chapter item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("toc-cached");
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.title);
                    if (item.state == ContentState.CACHED) {
                        getStyleClass().add("toc-cached");
                    }
                }
            }
        });
        toc.getSelectionModel().selectedIndexProperty().addListener((observable, oldValue, newValue) -> {
            if (!selecting && newValue != null && newValue.intValue() >= 0 && newValue.intValue() != index) {
                loadChapter(newValue.intValue(), -1);
            }
        });
        VBox.setVgrow(toc, Priority.ALWAYS);
        tocBox.getStyleClass().add("toc-box");
        tocBox.setPadding(new Insets(10));
        tocBox.setPrefWidth(250);
        tocBox.setMinWidth(250);
        tocBox.getChildren().addAll(tocTitle, toc);

        flow.getStyleClass().add("reader-flow");
        flow.setPadding(new Insets(18, 36, 60, 36));
        flow.getChildren().add(text);
        scroll.setFitToWidth(true);
        scroll.setContent(flow);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        saveTimer.setOnFinished(e -> saveProgress());
        scroll.vvalueProperty().addListener((observable, oldValue, newValue) -> {
            updatePercent();
            if (!loading && book != null) {
                saveTimer.playFromStart();
            }
        });

        root.setTop(toolbar);
        root.setLeft(tocBox);
        root.setCenter(scroll);
    }

    public void open(Book book, int chapterIndex, int charOffset) {
        saveProgress();
        this.book = book;
        this.chapters = ctx.bookService.chapters(book.id);
        this.index = -1;
        this.pendingFraction = Math.max(0, Math.min(1, charOffset / 10000.0));
        bookLabel.setText(book.title);
        toc.getItems().setAll(chapters);
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
            text.setText("这本书还没有章节。");
            return;
        }
        int next = Math.max(0, Math.min(chapters.size() - 1, target));
        index = next;
        Chapter chapter = chapters.get(next);
        selecting = true;
        toc.getSelectionModel().select(next);
        toc.scrollTo(Math.max(0, next - 3));
        selecting = false;
        chapterLabel.setText(chapter.title);
        if (fraction >= 0) {
            pendingFraction = fraction;
        }
        loading = true;
        text.setText("正在加载章节…");
        Ui.async("read-chapter",
                () -> ctx.onlineBookService.loadContent(book, chapter),
                content -> {
                    loading = false;
                    text.setText(content);
                    double target2 = pendingFraction < 0 ? 0 : pendingFraction;
                    pendingFraction = -1;
                    Platform.runLater(() -> {
                        scroll.setVvalue(target2);
                        updatePercent();
                    });
                },
                error -> {
                    loading = false;
                    String message = Ui.message(error);
                    text.setText("本章加载失败：" + message + "\n\n可以在书源页校验规则，或先下载本书离线阅读。");
                    window.status("章节加载失败：" + message);
                });
    }

    private void updatePercent() {
        int percent = (int) Math.round(scroll.getVvalue() * 100);
        percentLabel.setText(percent + "%");
    }

    private void saveProgress() {
        if (book == null || chapters.isEmpty() || index < 0) {
            return;
        }
        int percent = (int) Math.round(scroll.getVvalue() * 10000);
        ctx.bookService.saveProgress(book.id, index, percent);
    }

    private void changeFontSize(int delta) {
        int size = Math.max(12, Math.min(32, ctx.settings.fontSize() + delta));
        ctx.settings.put(com.yuewei.reader.application.SettingsService.FONT_SIZE, String.valueOf(size));
        applyStyle();
    }

    private void cycleTheme() {
        String current = ctx.settings.theme();
        String next = switch (current) {
            case "night" -> "green";
            case "green" -> "paper";
            default -> "night";
        };
        ctx.settings.put(com.yuewei.reader.application.SettingsService.THEME, next);
        applyStyle();
    }

    private void applyStyle() {
        int size = ctx.settings.fontSize();
        double spacing = ctx.settings.lineSpacing();
        String family = ctx.settings.fontFamily();
        Theme theme = THEMES.getOrDefault(ctx.settings.theme(), THEMES.get("paper"));

        StringBuilder style = new StringBuilder()
                .append("-fx-font-size:").append(size).append("px;")
                .append("-fx-fill:").append(theme.foreground).append(";");
        if (family != null && !family.isBlank() && !"系统默认".equals(family)) {
            style.append("-fx-font-family:'").append(family).append("';");
        }
        text.setStyle(style.toString());
        flow.setLineSpacing(Math.max(0, (spacing - 1) * size));
        flow.setStyle("-fx-background-color:" + theme.background + ";");
        scroll.setStyle("-fx-background:" + theme.background + "; -fx-background-color:" + theme.background + ";");
        toolbar.setStyle("-fx-background-color:" + theme.bar + ";");
        tocBox.setStyle("-fx-background-color:" + theme.bar + ";");
        root.setStyle("-fx-background-color:" + theme.background + ";");
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
