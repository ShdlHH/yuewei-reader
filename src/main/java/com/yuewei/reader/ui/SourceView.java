package com.yuewei.reader.ui;

import com.yuewei.reader.application.AppContext;
import com.yuewei.reader.application.ImportReport;
import com.yuewei.reader.domain.BookSource;
import com.yuewei.reader.domain.SourceStatus;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** 书源管理：导入、编辑、启停、校验（含调试面板）与导出。 */
public class SourceView implements AppView {

    private final AppContext ctx;
    private final MainWindow window;
    private final BorderPane root = new BorderPane();
    private final TableView<BookSource> table = new TableView<>();
    private final Label summary = Ui.label("", "muted");

    public SourceView(AppContext ctx, MainWindow window) {
        this.ctx = ctx;
        this.window = window;
        build();
    }

    private void build() {
        TableColumn<BookSource, Boolean> enabledCol = new TableColumn<>("启用");
        enabledCol.setPrefWidth(62);
        enabledCol.setCellValueFactory(cell -> new SimpleBooleanProperty(cell.getValue().enabled));
        enabledCol.setCellFactory(column -> new TableCell<>() {
            private final CheckBox box = new CheckBox();

            {
                box.setOnAction(e -> {
                    BookSource source = getTableView().getItems().get(getIndex());
                    source.enabled = box.isSelected();
                    ctx.sourceService.setEnabled(source.id, source.enabled);
                    window.status((source.enabled ? "已启用 " : "已停用 ") + source.name);
                });
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    box.setSelected(item);
                    setGraphic(box);
                }
            }
        });

        TableColumn<BookSource, String> nameCol = column("名称", 170,
                source -> source.name);
        TableColumn<BookSource, String> groupCol = column("分组", 90,
                source -> source.group == null ? "" : source.group);
        TableColumn<BookSource, String> supportCol = column("兼容性", 110,
                source -> switch (source.support) {
                    case FULL -> "完整支持";
                    case PARTIAL -> "部分支持";
                    case UNSUPPORTED -> "不支持";
                });
        TableColumn<BookSource, String> statusCol = column("状态", 90,
                source -> statusText(source.status));
        TableColumn<BookSource, String> checkCol = column("最近校验", 120,
                source -> Ui.formatTime(source.lastCheck));
        TableColumn<BookSource, String> errorCol = column("最近错误", 280,
                source -> source.lastError == null ? "" : source.lastError);

        table.getColumns().addAll(enabledCol, nameCol, groupCol, supportCol, statusCol, checkCol, errorCol);
        table.setPlaceholder(Ui.label("还没有书源，点击「导入文件」或「粘贴导入」", "muted"));
        table.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> refreshSummary());

        Button importFile = Ui.button("导入文件", "primary-button");
        importFile.setOnAction(e -> importFile());
        Button paste = Ui.button("粘贴导入", "ghost-button");
        paste.setOnAction(e -> importText());
        Button create = Ui.button("新建", "ghost-button");
        create.setOnAction(e -> create());
        Button edit = Ui.button("编辑", "ghost-button");
        edit.setOnAction(e -> edit());
        Button validate = Ui.button("校验 / 调试", "ghost-button");
        validate.setOnAction(e -> validate());
        Button remove = Ui.button("删除", "ghost-button");
        remove.setOnAction(e -> delete());
        Button enableAll = Ui.button("全部启用", "ghost-button");
        enableAll.setOnAction(e -> setAllEnabled(true));
        Button disableAll = Ui.button("全部停用", "ghost-button");
        disableAll.setOnAction(e -> setAllEnabled(false));
        Button export = Ui.button("导出", "ghost-button");
        export.setOnAction(e -> export());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(8, importFile, paste, create, edit, validate, remove,
                enableAll, disableAll, export, spacer, summary);
        toolbar.getStyleClass().add("toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(12, 16, 12, 16));

        root.setTop(toolbar);
        root.setCenter(table);
        BorderPane.setMargin(table, new Insets(0, 16, 16, 16));
        refresh();
    }

    private TableColumn<BookSource, String> column(String title, double width,
                                                   java.util.function.Function<BookSource, String> getter) {
        TableColumn<BookSource, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(getter.apply(cell.getValue())));
        return column;
    }

    public void refresh() {
        List<BookSource> all = ctx.sourceService.findAll();
        table.setItems(FXCollections.observableArrayList(all));
        refreshSummary();
    }

    private void refreshSummary() {
        int total = table.getItems().size();
        long enabled = table.getItems().stream().filter(source -> source.enabled).count();
        long ok = table.getItems().stream().filter(source -> source.status == SourceStatus.OK).count();
        summary.setText("共 " + total + " 个 · 启用 " + enabled + " 个 · 正常 " + ok + " 个");
    }

    private String statusText(SourceStatus status) {
        return switch (status) {
            case UNCHECKED -> "未校验";
            case CHECKING -> "校验中";
            case OK -> "正常";
            case ERROR -> "异常";
            case PARTIAL -> "部分支持";
            case UNSUPPORTED -> "不支持";
        };
    }

    private BookSource selected() {
        BookSource source = table.getSelectionModel().getSelectedItem();
        if (source == null) {
            Ui.error("请先选择一个书源");
        }
        return source;
    }

    private void importFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择书源 JSON");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("书源文件", "*.json"));
        File file = chooser.showOpenDialog(window.stage());
        if (file == null) {
            return;
        }
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            importJson(json, false, file.getName());
        } catch (Exception e) {
            Ui.error("读取文件失败", Ui.message(e));
        }
    }

    private void importText() {
        TextArea area = new TextArea(ctx.sourceService.template());
        Ui.setMonospace(area);
        area.setPrefRowCount(16);
        CheckBox overwrite = new CheckBox("覆盖相同地址的已有书源");
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("粘贴导入书源");
        dialog.initOwner(window.stage());
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8,
                Ui.label("粘贴 Legado 3.0 书源 JSON（对象或数组）", "muted"), area, overwrite);
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(680);
        if (dialog.showAndWait().orElse(null) != javafx.scene.control.ButtonType.OK) {
            return;
        }
        importJson(area.getText(), overwrite.isSelected(), "粘贴内容");
    }

    private void importJson(String json, boolean overwrite, String label) {
        window.status("正在导入书源…");
        Ui.async("import-sources", () -> ctx.sourceService.importJson(json, overwrite), report -> {
            refresh();
            window.status("书源导入完成");
            showReport(label, report);
        }, error -> {
            Ui.error("导入失败", Ui.message(error));
            window.status("书源导入失败");
        });
    }

    private void create() {
        TextArea area = new TextArea(ctx.sourceService.template());
        Ui.setMonospace(area);
        area.setPrefRowCount(18);
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("新建书源");
        dialog.initOwner(window.stage());
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8,
                Ui.label("按 Legado 3.0 格式填写，保存后请运行校验。", "muted"), area);
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(680);
        if (dialog.showAndWait().orElse(null) != javafx.scene.control.ButtonType.OK) {
            return;
        }
        String json = area.getText();
        window.status("正在保存书源…");
        Ui.async("save-source", () -> ctx.sourceService.importJson(json, false), report -> {
            refresh();
            window.status("书源已保存");
            showReport("新建书源", report);
        }, error -> Ui.error("保存失败", Ui.message(error)));
    }

    private void edit() {
        BookSource source = selected();
        if (source == null) {
            return;
        }
        TextArea area = new TextArea(source.ruleJson);
        Ui.setMonospace(area);
        area.setPrefRowCount(20);
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("编辑书源 · " + source.name);
        dialog.initOwner(window.stage());
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8,
                Ui.label("保存后状态会回到「未校验」，请重新校验。", "muted"), area);
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(700);
        if (dialog.showAndWait().orElse(null) != javafx.scene.control.ButtonType.OK) {
            return;
        }
        String json = area.getText();
        window.status("正在保存书源…");
        Ui.async("save-source", () -> {
            ctx.sourceService.saveEdited(source.id, json);
            return null;
        }, ignored -> {
            refresh();
            window.status("书源已保存");
        }, error -> Ui.error("保存失败", Ui.message(error)));
    }

    private void validate() {
        BookSource source = selected();
        if (source == null) {
            return;
        }
        String keyword = Ui.input("校验书源", "输入测试关键词（默认“斗罗大陆”）", "斗罗大陆");
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        window.status("正在校验 " + source.name + "…");
        Ui.async("validate-source", () -> ctx.validationService.validate(source, keyword.trim()), report -> {
            refresh();
            window.status("校验完成");
            ValidationDialog.show(window.stage(), report);
        }, error -> {
            refresh();
            Ui.error("校验失败", Ui.message(error));
            window.status("校验失败");
        });
    }

    private void delete() {
        BookSource source = selected();
        if (source == null) {
            return;
        }
        int books = ctx.sourceService.countBooks(source.id);
        String message = "确定删除书源「" + source.name + "」吗？";
        if (books > 0) {
            message += "\n\n已有 " + books + " 本在线书来自该书源，删除后这些书会显示“书源缺失”，已下载的章节仍可阅读。";
        }
        if (!Ui.confirm("删除书源", message)) {
            return;
        }
        ctx.sourceService.delete(source.id);
        ctx.downloadService.pauseBySource(source.id);
        refresh();
        window.status("已删除书源：" + source.name);
    }

    private void setAllEnabled(boolean enabled) {
        for (BookSource source : table.getItems()) {
            if (source.support == com.yuewei.reader.domain.SupportLevel.UNSUPPORTED) {
                continue;
            }
            ctx.sourceService.setEnabled(source.id, enabled);
        }
        refresh();
        window.status(enabled ? "已启用全部书源" : "已停用全部书源");
    }

    private void export() {
        String json = ctx.sourceService.exportJson();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出书源");
        chooser.setInitialFileName("yuewei-sources.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("书源文件", "*.json"));
        File file = chooser.showSaveDialog(window.stage());
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
            window.status("已导出到 " + file.getAbsolutePath());
            Ui.info("已导出 " + table.getItems().size() + " 个书源");
        } catch (Exception e) {
            Ui.error("导出失败", Ui.message(e));
        }
    }

    private void showReport(String label, ImportReport report) {
        StringBuilder builder = new StringBuilder(report.summary()).append("\n");
        for (ImportReport.Item item : report.items) {
            builder.append("\n").append(item.action).append(" · ")
                    .append(item.name).append("（").append(item.url).append("）");
            if (item.reason != null && !item.reason.isBlank()) {
                builder.append("\n    ").append(item.reason);
            }
        }
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.setTitle(label);
        alert.setHeaderText(report.summary());
        alert.setContentText(builder.toString());
        alert.getDialogPane().setPrefWidth(560);
        alert.show();
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
