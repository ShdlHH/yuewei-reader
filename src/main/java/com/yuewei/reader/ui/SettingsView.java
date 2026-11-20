package com.yuewei.reader.ui;

import com.yuewei.reader.application.AppContext;
import com.yuewei.reader.application.SettingsService;
import com.yuewei.reader.infrastructure.txt.PunctuationHighlighter.Role;
import com.yuewei.reader.infrastructure.txt.PunctuationHighlighter.Rules;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 设置：阅读样式与网络参数。 */
public class SettingsView implements AppView {

    private final AppContext ctx;
    private final MainWindow window;
    private final BorderPane root = new BorderPane();

    private final Spinner<Integer> fontSize = new Spinner<>(12, 32, 18);
    private final Spinner<Double> lineSpacing = new Spinner<>(1.2, 3.0, 2.0, 0.1);
    private final ComboBox<String> fontFamily = new ComboBox<>();
    private final ComboBox<String> theme = new ComboBox<>();
    private final Spinner<Integer> timeout = new Spinner<>(5, 60, 20);
    private final Spinner<Integer> searchConcurrency = new Spinner<>(1, 10, 5);
    private final Spinner<Integer> downloadConcurrency = new Spinner<>(1, 5, 2);
    private final Spinner<Integer> requestInterval = new Spinner<>(0, 5000, 300, 100);
    private final TextField userAgent = new TextField();
    private final Spinner<Integer> cacheLimit = new Spinner<>(100, 10240, 1024, 100);
    private final Label cacheUsage = Ui.label("", "muted");
    private final CheckBox syncEnabled = new CheckBox("启用 WebDAV 同步");
    private final TextField syncUrl = new TextField();
    private final TextField syncAccount = new TextField();
    private final PasswordField syncPassword = new PasswordField();
    private final TextField syncDir = new TextField();
    private final Label syncHint = Ui.label("", "muted");
    private final TextFlow previewFlow = new TextFlow();
    private final VBox previewCard = new VBox();
    private final CheckBox punctEnabled = new CheckBox("开启标点着色");
    private final List<PunctRule> punctRules = new ArrayList<>();
    private final Label fontHint = Ui.label("", "muted");

    /** 一条着色规则：开关 + 颜色 + 彩色示例。 */
    private static final class PunctRule {
        final Role role;
        final String colorKey;
        final String defaultColor;
        final CheckBox box;
        final ColorPicker picker;
        final Label sample;

        PunctRule(Role role, String colorKey, String defaultColor,
                  CheckBox box, ColorPicker picker, Label sample) {
            this.role = role;
            this.colorKey = colorKey;
            this.defaultColor = defaultColor;
            this.box = box;
            this.picker = picker;
            this.sample = sample;
        }
    }

    private static final String PREVIEW_TEXT = "“你终于来了。”他放下书，抬头问道：“外面下雨了吗？”\n"
            + "她笑了一下——没有回答（也许是不必回答）。";

    public SettingsView(AppContext ctx, MainWindow window) {
        this.ctx = ctx;
        this.window = window;
        build();
    }

    private void build() {
        fontFamily.getItems().setAll(Ui.fontFamilies());
        fontFamily.setPrefWidth(150);
        fontFamily.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (empty || item == null || "系统默认".equals(item)) {
                    setStyle("");
                } else {
                    setStyle("-fx-font-family:'" + item + "';");
                }
            }
        });

        theme.getItems().addAll("纸白", "夜间", "护眼");
        theme.setPrefWidth(220);

        userAgent.setPrefWidth(360);

        GridPane readerGrid = grid();
        int row = 0;
        HBox sizeRow = new HBox(8, fontSize, lineSpacing);
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        readerGrid.add(new Label("字号 / 行距"), 0, row);
        readerGrid.add(sizeRow, 1, row++);
        readerGrid.add(new Label("字体"), 0, row);
        Button importFont = Ui.button("导入", "ghost-button");
        importFont.setOnAction(e -> importFont());
        HBox fontRow = new HBox(10, fontFamily, importFont);
        fontRow.setAlignment(Pos.CENTER_LEFT);
        readerGrid.add(fontRow, 1, row++);
        readerGrid.add(new Label("主题"), 0, row);
        readerGrid.add(theme, 1, row++);
        readerGrid.add(Ui.label("主题同时影响主界面与阅读页", "muted"), 1, row++);
        readerGrid.add(fontHint, 1, row++);
        GridPane.setColumnSpan(fontHint, 1);
        fontHint.setWrapText(true);
        fontHint.setMaxWidth(360);
        styleGrid(readerGrid);

        previewFlow.getStyleClass().add("preview-page");
        previewFlow.setPadding(new Insets(0));
        previewFlow.setPrefWidth(560);

        VBox punctBox = new VBox(8);
        punctEnabled.selectedProperty().addListener((observable, oldValue, newValue) -> {
            for (PunctRule rule : punctRules) {
                rule.box.setDisable(!newValue);
                rule.picker.setDisable(!newValue);
            }
            updatePreview();
        });
        punctEnabled.setSelected(true);
        punctBox.getChildren().add(punctEnabled);

        GridPane punctGrid = grid();
        punctGrid.setVgap(8);
        int punctRow = 0;
        addRule(punctGrid, punctRow++, "对话（引号内）", Role.DIALOGUE,
                SettingsService.PUNCT_DIALOGUE_COLOR, SettingsService.DEFAULT_PUNCT_DIALOGUE_COLOR,
                true, "“你来了。”");
        addRule(punctGrid, punctRow++, "句号", Role.STOP,
                SettingsService.PUNCT_STOP_COLOR, SettingsService.DEFAULT_PUNCT_STOP_COLOR,
                true, "他合上书。");
        addRule(punctGrid, punctRow++, "问号", Role.ASK,
                SettingsService.PUNCT_ASK_COLOR, SettingsService.DEFAULT_PUNCT_ASK_COLOR,
                true, "外面下雨了吗？");
        addRule(punctGrid, punctRow++, "感叹号", Role.EXCL,
                SettingsService.PUNCT_EXCL_COLOR, SettingsService.DEFAULT_PUNCT_EXCL_COLOR,
                true, "别回头！");
        addRule(punctGrid, punctRow++, "省略号 / 破折号", Role.ELLIPSIS,
                SettingsService.PUNCT_ELLIPSIS_COLOR, SettingsService.DEFAULT_PUNCT_ELLIPSIS_COLOR,
                false, "她笑了一下——没有回答……");
        addRule(punctGrid, punctRow, "括号（含内容）", Role.PAREN,
                SettingsService.PUNCT_PAREN_COLOR, SettingsService.DEFAULT_PUNCT_PAREN_COLOR,
                false, "（也许是不必回答）");
        punctBox.getChildren().add(punctGrid);

        fontSize.valueProperty().addListener((observable, oldValue, newValue) -> updatePreview());
        lineSpacing.valueProperty().addListener((observable, oldValue, newValue) -> updatePreview());
        fontFamily.valueProperty().addListener((observable, oldValue, newValue) -> {
            refreshFontHint();
            updatePreview();
        });
        theme.valueProperty().addListener((observable, oldValue, newValue) -> updatePreview());

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
        styleGrid(networkGrid);

        GridPane storageGrid = grid();
        row = 0;
        storageGrid.add(new Label("数据目录"), 0, row);
        storageGrid.add(Ui.label(ctx.dataDir.toString(), "muted"), 1, row++);
        storageGrid.add(new Label("缓存占用"), 0, row);
        storageGrid.add(cacheUsage, 1, row++);
        storageGrid.add(new Label("缓存上限（MB）"), 0, row);
        storageGrid.add(cacheLimit, 1, row++);
        cacheLimit.valueProperty().addListener((observable, oldValue, newValue) -> refreshCacheUsage());
        styleGrid(storageGrid);

        Button cleanCache = Ui.button("清理全部缓存", "ghost-button");
        cleanCache.setOnAction(e -> cleanCache());
        HBox storageActions = new HBox(10, cleanCache,
                Ui.label("仅清理在线书正文缓存；本地导入的 TXT 不受影响。", "muted"));
        storageActions.setAlignment(Pos.CENTER_LEFT);

        syncUrl.setPrefWidth(360);
        syncAccount.setPrefWidth(220);
        syncPassword.setPrefWidth(220);
        syncDir.setPrefWidth(160);
        syncDir.setPromptText("yuewei");
        syncUrl.setPromptText("https://dav.jianguoyun.com/dav/");

        GridPane syncGrid = grid();
        row = 0;
        syncGrid.add(new Label("WebDAV 地址"), 0, row);
        syncGrid.add(syncUrl, 1, row++);
        syncGrid.add(new Label("账号"), 0, row);
        syncGrid.add(syncAccount, 1, row++);
        syncGrid.add(new Label("密码 / 应用密码"), 0, row);
        syncGrid.add(syncPassword, 1, row++);
        syncGrid.add(new Label("同步目录"), 0, row);
        syncGrid.add(syncDir, 1, row++);
        syncGrid.add(new Label("启用"), 0, row);
        syncGrid.add(syncEnabled, 1, row++);
        syncGrid.add(syncHint, 1, row);
        syncHint.setWrapText(true);
        syncHint.setMaxWidth(360);

        Button syncNowButton = Ui.button("立即同步", "ghost-button");
        syncNowButton.setOnAction(e -> saveSyncAndRun());
        Label syncTip = Ui.label("进度按书写入 bookProgress/书名_作者.json，手机端把 Legado 的 WebDAV 目录设为同一目录即可互通。", "muted");
        syncTip.setWrapText(true);
        syncTip.setMaxWidth(520);
        HBox syncActions = new HBox(10, syncNowButton, syncTip);
        syncActions.setAlignment(Pos.CENTER_LEFT);
        syncActions.setMaxWidth(760);

        Button save = Ui.button("保存设置", "primary-button");
        save.setOnAction(e -> save());
        Button resetAll = Ui.button("恢复默认", "ghost-button");
        resetAll.setOnAction(e -> resetDefaults());

        Label pageTitle = Ui.label("设置", "settings-page-title");
        Label pageSub = Ui.label("阅读、标点与网络偏好", "settings-page-sub");
        VBox pageTitles = new VBox(4, pageTitle, pageSub);
        Region pageSpacer = new Region();
        HBox.setHgrow(pageSpacer, Priority.ALWAYS);
        HBox pageHead = new HBox(12, pageTitles, pageSpacer, resetAll, save);
        pageHead.setAlignment(Pos.CENTER_LEFT);
        pageHead.getStyleClass().add("settings-header");

        Label punctHint = Ui.label("关闭总开关或取消某条规则，对应文字恢复墨色；夜间与护眼主题会自动调整明度。", "muted");
        punctHint.setWrapText(true);
        punctHint.setMaxWidth(520);
        Label networkHint = Ui.label("修改网络参数后立即生效；下载并发对新建任务生效。", "muted");
        Label syncNote = Ui.label("启动时自动拉取远端更新的进度；返回书架、切换章节和退出程序时会自动上传。"
                + "密码只保存在本地数据库，不会写进同步文件。", "muted");
        syncNote.setWrapText(true);
        syncNote.setMaxWidth(560);

        previewCard.getChildren().setAll(previewFlow);
        previewCard.getStyleClass().add("preview-card");
        VBox previewSection = new VBox(8, Ui.label("预览", "settings-card-title"), previewCard);

        VBox content = new VBox(16,
                pageHead,
                card("阅读", readerGrid),
                previewSection,
                card("标点着色", punctBox, punctHint),
                card("网络", networkGrid, networkHint),
                card("存储", storageGrid, storageActions),
                card("同步（WebDAV）", syncGrid, syncActions, syncNote));
        content.setPadding(new Insets(Ui.SPACE_PAGE, Ui.SPACE_PAGE + 4,
                Ui.SPACE_PAGE + 4, Ui.SPACE_PAGE + 4));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        root.setCenter(scroll);
        load();
    }

    private VBox card(String title, Node... children) {
        VBox box = new VBox(12);
        box.getStyleClass().add("settings-card");
        box.getChildren().add(Ui.label(title, "settings-card-title"));
        box.getChildren().addAll(children);
        return box;
    }

    private void styleGrid(GridPane grid) {
        ColumnConstraints labelColumn = new ColumnConstraints(128);
        ColumnConstraints controlColumn = new ColumnConstraints(220);
        grid.getColumnConstraints().addAll(labelColumn, controlColumn);
    }

    private void resetDefaults() {
        fontSize.getValueFactory().setValue(18);
        lineSpacing.getValueFactory().setValue(2.0);
        fontFamily.setValue(SettingsService.DEFAULT_FONT_FAMILY);
        theme.setValue("纸白");
        punctEnabled.setSelected(true);
        boolean[] defaults = {true, true, true, true, false, false};
        for (int i = 0; i < punctRules.size() && i < defaults.length; i++) {
            punctRules.get(i).box.setSelected(defaults[i]);
        }
        updatePreview();
        window.status("已恢复默认，保存后生效");
    }

    private GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(Ui.SPACE_FORM + 2);
        grid.setVgap(Ui.SPACE_FORM);
        return grid;
    }

    private void addRule(GridPane grid, int row, String label, Role role, String colorKey,
                         String defaultColor, boolean on, String sampleText) {
        CheckBox box = new CheckBox(label);
        box.setSelected(on);
        box.setPrefWidth(160);
        ColorPicker picker = new ColorPicker(Color.web(defaultColor));
        picker.getStyleClass().add("punct-color-picker");
        picker.setPrefWidth(56);
        picker.setMinWidth(56);
        picker.setMaxWidth(56);
        Label sample = new Label(sampleText);
        sample.setFont(Font.font(13));

        PunctRule rule = new PunctRule(role, colorKey, defaultColor, box, picker, sample);
        punctRules.add(rule);
        box.selectedProperty().addListener((observable, oldValue, newValue) -> updatePreview());
        picker.valueProperty().addListener((observable, oldValue, newValue) -> updatePreview());

        grid.add(box, 0, row);
        grid.add(picker, 1, row);
        grid.add(sample, 2, row);
    }

    private void load() {
        SettingsService settings = ctx.settings;
        fontSize.getValueFactory().setValue(settings.fontSize());
        lineSpacing.getValueFactory().setValue(settings.lineSpacing());
        String family = settings.fontFamily();
        if (!fontFamily.getItems().contains(family)) {
            fontFamily.getItems().add(family);
        }
        fontFamily.setValue(family);
        String themeKey = settings.theme();
        theme.setValue(switch (themeKey) {
            case "night" -> "夜间";
            case "green" -> "护眼";
            default -> "纸白";
        });

        punctEnabled.setSelected(settings.punctEnabled());
        boolean[] switches = {settings.punctDialogue(), settings.punctStop(), settings.punctAsk(),
                settings.punctExcl(), settings.punctEllipsis(), settings.punctParen()};
        String[] colors = {
                settings.punctColor(SettingsService.PUNCT_DIALOGUE_COLOR,
                        SettingsService.DEFAULT_PUNCT_DIALOGUE_COLOR),
                settings.punctColor(SettingsService.PUNCT_STOP_COLOR,
                        SettingsService.DEFAULT_PUNCT_STOP_COLOR),
                settings.punctColor(SettingsService.PUNCT_ASK_COLOR,
                        SettingsService.DEFAULT_PUNCT_ASK_COLOR),
                settings.punctColor(SettingsService.PUNCT_EXCL_COLOR,
                        SettingsService.DEFAULT_PUNCT_EXCL_COLOR),
                settings.punctColor(SettingsService.PUNCT_ELLIPSIS_COLOR,
                        SettingsService.DEFAULT_PUNCT_ELLIPSIS_COLOR),
                settings.punctColor(SettingsService.PUNCT_PAREN_COLOR,
                        SettingsService.DEFAULT_PUNCT_PAREN_COLOR)};
        for (int i = 0; i < punctRules.size() && i < switches.length; i++) {
            punctRules.get(i).box.setSelected(switches[i]);
            punctRules.get(i).picker.setValue(Color.web(colors[i]));
        }

        timeout.getValueFactory().setValue(settings.timeout());
        searchConcurrency.getValueFactory().setValue(settings.searchConcurrency());
        downloadConcurrency.getValueFactory().setValue(settings.downloadConcurrency());
        requestInterval.getValueFactory().setValue(settings.requestInterval());
        userAgent.setText(settings.userAgent());
        cacheLimit.getValueFactory().setValue(settings.cacheLimitMb());
        syncEnabled.setSelected(settings.syncEnabled());
        syncUrl.setText(settings.syncUrl());
        syncAccount.setText(settings.syncAccount());
        syncPassword.setText(settings.syncPassword());
        syncDir.setText(settings.syncDir());
        refreshSyncHint();
        refreshCacheUsage();
        refreshFontHint();
        updatePreview();
    }

    /** 实时预览：字体、字号、行距、主题与着色规则都和阅读界面共用同一套渲染。 */
    private void updatePreview() {
        String family = Fonts.resolve(fontFamily.getValue());
        Integer sizeValue = fontSize.getValue();
        Double spacingValue = lineSpacing.getValue();
        int size = sizeValue == null ? 18 : sizeValue;
        double spacing = spacingValue == null ? 1.8 : spacingValue;
        Font previewFont = family == null ? Font.font(size) : Font.font(family, size);
        previewFlow.setLineSpacing(Math.max(0, (spacing - 1) * size));

        String background;
        String foreground;
        Ui.Theme theme = Ui.theme(themeKey());
        background = theme.background;
        foreground = theme.ink;
        previewCard.setStyle("-fx-background-color:" + background + ";");

        Map<Role, Color> colors = previewColors(themeKey());
        Color ink = Color.web(foreground);
        ReaderStyles.fill(previewFlow, PREVIEW_TEXT, previewFont, punctEnabled.isSelected(),
                previewRules(), colors, ink);
        for (PunctRule rule : punctRules) {
            rule.sample.setFont(Font.font(family == null ? Font.getDefault().getFamily() : family, 13));
            rule.sample.setStyle("-fx-text-fill:" + toHex(colors.getOrDefault(rule.role, ink)) + ";");
        }
    }

    private Rules previewRules() {
        return new Rules(on(Role.DIALOGUE), on(Role.STOP), on(Role.ASK),
                on(Role.EXCL), on(Role.ELLIPSIS), on(Role.PAREN));
    }

    private boolean on(Role role) {
        if (!punctEnabled.isSelected()) {
            return false;
        }
        for (PunctRule rule : punctRules) {
            if (rule.role == role) {
                return rule.box.isSelected();
            }
        }
        return false;
    }

    private Map<Role, Color> previewColors(String themeKey) {
        Map<Role, Color> colors = new EnumMap<>(Role.class);
        for (PunctRule rule : punctRules) {
            Color value = rule.picker.getValue() == null
                    ? Color.web(rule.defaultColor)
                    : rule.picker.getValue();
            colors.put(rule.role, ReaderStyles.adjust(value, themeKey));
        }
        return colors;
    }

    private String colorOf(Role role) {
        for (PunctRule rule : punctRules) {
            if (rule.role == role) {
                return toHex(rule.picker.getValue() == null
                        ? Color.web(rule.defaultColor)
                        : rule.picker.getValue());
            }
        }
        return "";
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    /** 字体提示：默认字体缺失时明确告知回退方案与导入入口。 */
    private void refreshFontHint() {
        String family = fontFamily.getValue();
        if (family == null || family.isBlank() || "系统默认".equals(family)) {
            fontHint.setText("将使用系统默认字体。");
        } else if (Fonts.available(family)) {
            fontHint.setText("已加载「" + family + "」。");
        } else {
            fontHint.setText("未检测到「" + family + "」，正文暂用宋体回退。"
                    + "可点击“导入字体文件”，或把字体文件放进数据目录的 fonts 文件夹后重启。");
        }
    }

    private void importFont() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导入字体文件");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("字体文件", "*.ttf", "*.otf", "*.ttc"));
        File file = chooser.showOpenDialog(window.stage());
        if (file == null) {
            return;
        }
        try {
            String family = Fonts.install(file);
            if (family == null) {
                Ui.info("无法识别这个字体文件，请换一个 ttf / otf / ttc 文件。");
                return;
            }
            if (!fontFamily.getItems().contains(family)) {
                fontFamily.getItems().add(family);
            }
            fontFamily.setValue(family);
            window.status("已导入字体：" + family + "，保存设置后应用到阅读界面");
        } catch (IOException e) {
            Ui.info("导入字体失败：" + e.getMessage());
        }
    }

    private String themeKey() {
        return switch (theme.getValue() == null ? "纸白" : theme.getValue()) {
            case "夜间" -> "night";
            case "护眼" -> "green";
            default -> "paper";
        };
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
                ? SettingsService.DEFAULT_FONT_FAMILY
                : fontFamily.getValue().trim();
        ctx.settings.saveReader(fontSize.getValue(), lineSpacing.getValue(), family, themeKey());
        ctx.settings.savePunct(punctEnabled.isSelected(), on(Role.DIALOGUE), on(Role.STOP),
                on(Role.ASK), on(Role.EXCL), on(Role.ELLIPSIS), on(Role.PAREN));
        ctx.settings.savePunctColors(colorOf(Role.DIALOGUE), colorOf(Role.STOP), colorOf(Role.ASK),
                colorOf(Role.EXCL), colorOf(Role.ELLIPSIS), colorOf(Role.PAREN));
        String ua = userAgent.getText();
        if (ua == null || ua.isBlank()) {
            ua = ctx.settings.userAgent();
        }
        ctx.settings.saveNetwork(timeout.getValue(), searchConcurrency.getValue(),
                downloadConcurrency.getValue(), requestInterval.getValue(), ua);
        ctx.settings.saveStorage(cacheLimit.getValue());
        ctx.settings.applyTo(ctx.http);
        saveSyncSettings();
        window.applyReaderStyle();
        updatePreview();
        refreshCacheUsage();
        window.status("设置已保存");
        Ui.info("设置已保存。阅读样式已立即应用到阅读界面。");
    }

    private void saveSyncSettings() {
        ctx.settings.saveSync(syncEnabled.isSelected(), syncUrl.getText(), syncAccount.getText(),
                syncPassword.getText(), syncDir.getText());
        refreshSyncHint();
    }

    private void saveSyncAndRun() {
        saveSyncSettings();
        window.syncNow(true);
    }

    /** 同步完成后由主窗口调用，刷新“上次同步”提示。 */
    public void refreshSyncHint() {
        syncHint.setText(ctx.settings.lastSyncText());
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
