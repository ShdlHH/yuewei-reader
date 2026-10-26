package com.yuewei.reader.ui;

import com.yuewei.reader.application.AppContext;
import com.yuewei.reader.application.SettingsService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** 设置：阅读样式与网络参数。 */
public class SettingsView implements AppView {

    private final AppContext ctx;
    private final MainWindow window;
    private final BorderPane root = new BorderPane();

    private final Spinner<Integer> fontSize = new Spinner<>(12, 32, 18);
    private final Spinner<Double> lineSpacing = new Spinner<>(1.2, 3.0, 1.8, 0.1);
    private final ComboBox<String> fontFamily = new ComboBox<>();
    private final ComboBox<String> theme = new ComboBox<>();
    private final Spinner<Integer> timeout = new Spinner<>(5, 60, 20);
    private final Spinner<Integer> searchConcurrency = new Spinner<>(1, 10, 5);
    private final Spinner<Integer> downloadConcurrency = new Spinner<>(1, 5, 2);
    private final Spinner<Integer> requestInterval = new Spinner<>(0, 5000, 300, 100);
    private final TextField userAgent = new TextField();
    private final Spinner<Integer> cacheLimit = new Spinner<>(100, 10240, 1024, 100);
    private final Label cacheUsage = Ui.label("", "muted");

    public SettingsView(AppContext ctx, MainWindow window) {
        this.ctx = ctx;
        this.window = window;
        build();
    }

    private void build() {
        fontFamily.getItems().addAll("系统默认", "微软雅黑", "宋体", "楷体", "等线");
        fontFamily.setEditable(true);
        fontFamily.setPrefWidth(220);

        theme.getItems().addAll("纸白", "夜间", "护眼");
        theme.setPrefWidth(220);

        userAgent.setPrefWidth(520);

        GridPane readerGrid = grid();
        int row = 0;
        readerGrid.add(new Label("正文字号"), 0, row);
        readerGrid.add(fontSize, 1, row++);
        readerGrid.add(new Label("行距"), 0, row);
        readerGrid.add(lineSpacing, 1, row++);
        readerGrid.add(new Label("字体"), 0, row);
        readerGrid.add(fontFamily, 1, row++);
        readerGrid.add(new Label("主题"), 0, row);
        readerGrid.add(theme, 1, row++);

        GridPane networkGrid = grid();
        row = 0;
        networkGrid.add(new Label("请求超时（秒）"), 0, row);
        networkGrid.add(timeout, 1, row++);
        networkGrid.add(new Label("搜索并发"), 0, row);
        networkGrid.add(searchConcurrency, 1, row++);
        networkGrid.add(new Label("下载并发"), 0, row);
        networkGrid.add(downloadConcurrency, 1, row++);
        networkGrid.add(new Label("请求间隔（毫秒）"), 0, row);
        networkGrid.add(requestInterval, 1, row++);
        networkGrid.add(new Label("User-Agent"), 0, row);
        networkGrid.add(userAgent, 1, row++);

        GridPane storageGrid = grid();
        row = 0;
        storageGrid.add(new Label("数据目录"), 0, row);
        storageGrid.add(Ui.label(ctx.dataDir.toString(), "muted"), 1, row++);
        storageGrid.add(new Label("缓存占用"), 0, row);
        storageGrid.add(cacheUsage, 1, row++);
        storageGrid.add(new Label("缓存上限（MB）"), 0, row);
        storageGrid.add(cacheLimit, 1, row++);
        cacheLimit.valueProperty().addListener((observable, oldValue, newValue) -> refreshCacheUsage());

        Button cleanCache = Ui.button("清理全部缓存", "ghost-button");
        cleanCache.setOnAction(e -> cleanCache());
        HBox storageActions = new HBox(10, cleanCache,
                Ui.label("仅清理在线书正文缓存；本地导入的 TXT 不受影响。", "muted"));
        storageActions.setAlignment(Pos.CENTER_LEFT);

        Button save = Ui.button("保存设置", "primary-button");
        save.setOnAction(e -> save());

        HBox actions = new HBox(10, save, Ui.label("设置保存在本地数据目录，重启后仍生效。", "muted"));
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(4, 0, 0, 0));

        VBox content = new VBox(12,
                Ui.label("阅读", "section-title"), readerGrid,
                new Separator(),
                Ui.label("网络", "section-title"), networkGrid,
                Ui.label("修改网络参数后立即生效；下载并发对新建任务生效。", "muted"),
                new Separator(),
                Ui.label("存储", "section-title"), storageGrid,
                storageActions,
                new Separator(),
                actions);
        content.setPadding(new Insets(20, 24, 24, 24));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        root.setCenter(scroll);
        load();
    }

    private GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        return grid;
    }

    private void load() {
        SettingsService settings = ctx.settings;
        fontSize.getValueFactory().setValue(settings.fontSize());
        lineSpacing.getValueFactory().setValue(settings.lineSpacing());
        fontFamily.setValue(settings.fontFamily());
        String themeKey = settings.theme();
        theme.setValue(switch (themeKey) {
            case "night" -> "夜间";
            case "green" -> "护眼";
            default -> "纸白";
        });
        timeout.getValueFactory().setValue(settings.timeout());
        searchConcurrency.getValueFactory().setValue(settings.searchConcurrency());
        downloadConcurrency.getValueFactory().setValue(settings.downloadConcurrency());
        requestInterval.getValueFactory().setValue(settings.requestInterval());
        userAgent.setText(settings.userAgent());
        cacheLimit.getValueFactory().setValue(settings.cacheLimitMb());
        refreshCacheUsage();
    }

    private void refreshCacheUsage() {
        long used = ctx.bookService.totalCacheSize();
        long limit = (long) cacheLimit.getValue() * 1024 * 1024;
        String text = Ui.humanSize(used) + " / " + cacheLimit.getValue() + " MB";
        if (used > limit) {
            text += " · 已超出上限，建议清理";
        }
        cacheUsage.setText(text);
    }

    private void cleanCache() {
        if (!Ui.confirm("清理全部缓存", "将删除所有在线书已下载的正文，之后需要联网重新获取。"
                + "本地导入的 TXT 不受影响。继续吗？")) {
            return;
        }
        ctx.bookService.clearOnlineCache();
        refreshCacheUsage();
        window.status("已清理在线书缓存");
        Ui.info("在线书缓存已清理。");
    }

    private void save() {
        String family = fontFamily.getValue() == null || fontFamily.getValue().isBlank()
                ? "系统默认"
                : fontFamily.getValue().trim();
        String themeKey = switch (theme.getValue() == null ? "纸白" : theme.getValue()) {
            case "夜间" -> "night";
            case "护眼" -> "green";
            default -> "paper";
        };
        ctx.settings.saveReader(fontSize.getValue(), lineSpacing.getValue(), family, themeKey);
        String ua = userAgent.getText();
        if (ua == null || ua.isBlank()) {
            ua = ctx.settings.userAgent();
        }
        ctx.settings.saveNetwork(timeout.getValue(), searchConcurrency.getValue(),
                downloadConcurrency.getValue(), requestInterval.getValue(), ua);
        ctx.settings.saveStorage(cacheLimit.getValue());
        ctx.settings.applyTo(ctx.http);
        refreshCacheUsage();
        window.status("设置已保存");
        Ui.info("设置已保存。阅读样式会在下次打开阅读器时生效。");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        load();
    }
}
