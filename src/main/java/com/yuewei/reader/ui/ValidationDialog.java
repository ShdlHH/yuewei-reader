package com.yuewei.reader.ui;

import com.yuewei.reader.application.ValidationReport;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Map;

/** 校验/调试弹窗：四段链路结果 + 解析字段 + 页面样本。 */
public final class ValidationDialog {

    private ValidationDialog() {
    }

    public static void show(Window owner, ValidationReport report) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("书源校验 · " + report.sourceName);
        dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(880);
        dialog.getDialogPane().setPrefHeight(620);

        Label summary = Ui.label(report.ok
                ? "四段链路全部通过 · 耗时 " + report.elapsedMs + " ms"
                : "校验失败 · " + report.firstFailure(),
                report.ok ? "ok" : "warn");
        Label keyword = Ui.label("测试关键词：" + report.keyword, "muted");
        HBox header = new HBox(12, summary, keyword);
        header.setPadding(new Insets(0, 0, 8, 0));

        TableView<ValidationReport.Stage> table = new TableView<>();
        TableColumn<ValidationReport.Stage, String> nameCol = new TableColumn<>("环节");
        nameCol.setPrefWidth(80);
        nameCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().name));
        TableColumn<ValidationReport.Stage, String> resultCol = new TableColumn<>("结果");
        resultCol.setPrefWidth(300);
        resultCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().message));
        TableColumn<ValidationReport.Stage, String> timeCol = new TableColumn<>("耗时");
        timeCol.setPrefWidth(80);
        timeCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                cell.getValue().skipped ? "—" : cell.getValue().millis + " ms"));
        TableColumn<ValidationReport.Stage, String> urlCol = new TableColumn<>("地址");
        urlCol.setPrefWidth(380);
        urlCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                cell.getValue().url == null ? "" : cell.getValue().url));
        table.getColumns().addAll(nameCol, resultCol, timeCol, urlCol);
        table.setItems(FXCollections.observableArrayList(report.stages));
        table.getSelectionModel().selectFirst();

        TextArea detail = new TextArea();
        Ui.setMonospace(detail);
        detail.setEditable(false);
        detail.setWrapText(true);
        table.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, stage) -> detail.setText(describe(stage)));

        SplitPane split = new SplitPane(table, detail);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.55);

        VBox content = new VBox(8, header, split);
        VBox.setVgrow(split, Priority.ALWAYS);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.show();
    }

    private static String describe(ValidationReport.Stage stage) {
        if (stage == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("环节：").append(stage.name);
        if (stage.skipped) {
            builder.append("\n结果：未执行（前序环节失败）");
        } else {
            builder.append("\nHTTP 状态：").append(stage.status)
                    .append("　耗时：").append(stage.millis).append(" ms");
        }
        builder.append("\n说明：").append(stage.message == null ? "" : stage.message);
        builder.append("\n地址：").append(stage.url == null ? "" : stage.url);
        if (!stage.fields.isEmpty()) {
            builder.append("\n\n解析字段：");
            for (Map.Entry<String, String> entry : stage.fields.entrySet()) {
                builder.append("\n  ").append(entry.getKey()).append(" = ").append(entry.getValue());
            }
        }
        if (stage.sample != null && !stage.sample.isBlank()) {
            builder.append("\n\n页面文本样本：\n").append(stage.sample);
        }
        return builder.toString();
    }
}
