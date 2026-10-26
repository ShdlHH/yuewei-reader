package com.yuewei.reader.ui;

import com.yuewei.reader.application.ImportPreview;
import com.yuewei.reader.infrastructure.txt.ChapterSplitter;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.charset.Charset;

/** TXT 导入设置：确认书名、作者与分章方式。 */
public final class ImportDialog {

    /** 用户确认后的导入参数。 */
    public static final class Params {
        public Charset charset;
        public ChapterSplitter.Mode mode = ChapterSplitter.Mode.AUTO;
        public String regex = "";
        public String title;
        public String author;
    }

    private static final String MODE_AUTO = "自动识别章节标题";
    private static final String MODE_FIXED = "按固定字数切分（约 3000 字一段）";
    private static final String MODE_SINGLE = "整本作为一章";
    private static final String MODE_REGEX = "自定义章节标题正则";

    private ImportDialog() {
    }

    public static Params show(Window owner, ImportPreview preview) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("导入 TXT");
        dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));

        Label fileInfo = Ui.label(preview.fileName + " · " + Ui.humanSize(preview.fileSize)
                + " · 编码 " + (preview.charset == null ? "未知" : preview.charset.name()), "muted");
        Label splitInfo = Ui.label(preview.splittable()
                ? "识别到 " + preview.chapterCount + " 章"
                : "自动分章失败：" + preview.error, preview.splittable() ? "ok" : "warn");

        TextField title = new TextField(preview.defaultTitle);
        TextField author = new TextField("未知");
        ComboBox<String> mode = new ComboBox<>();
        mode.getItems().addAll(MODE_AUTO, MODE_FIXED, MODE_SINGLE, MODE_REGEX);
        mode.setValue(preview.splittable() ? MODE_AUTO : MODE_FIXED);
        TextField regex = new TextField("^第[0-9一二三四五六七八九十百千]+章.*");
        regex.disableProperty().bind(mode.valueProperty().isNotEqualTo(MODE_REGEX));
        regex.managedProperty().bind(regex.disableProperty().not());
        regex.visibleProperty().bind(regex.disableProperty().not());

        VBox previewBox = new VBox(4);
        if (!preview.chapterTitles.isEmpty()) {
            previewBox.getChildren().add(Ui.label("前几章：", "muted"));
            ListView<String> list = new ListView<>();
            list.getItems().addAll(preview.chapterTitles);
            list.setPrefHeight(120);
            previewBox.getChildren().add(list);
        }

        grid.add(fileInfo, 0, 0, 3, 1);
        grid.add(splitInfo, 0, 1, 3, 1);
        grid.add(new Label("书名"), 0, 2);
        grid.add(title, 1, 2, 2, 1);
        grid.add(new Label("作者"), 0, 3);
        grid.add(author, 1, 3, 2, 1);
        grid.add(new Label("分章方式"), 0, 4);
        grid.add(mode, 1, 4, 2, 1);
        grid.add(new Label("标题正则"), 0, 5);
        grid.add(regex, 1, 5, 2, 1);
        grid.add(previewBox, 0, 6, 3, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(520);

        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return null;
        }
        Params params = new Params();
        params.charset = preview.charset;
        params.title = title.getText();
        params.author = author.getText();
        params.regex = regex.getText();
        String selected = mode.getValue();
        if (MODE_FIXED.equals(selected)) {
            params.mode = ChapterSplitter.Mode.FIXED;
        } else if (MODE_SINGLE.equals(selected)) {
            params.mode = ChapterSplitter.Mode.SINGLE;
        } else if (MODE_REGEX.equals(selected)) {
            params.mode = ChapterSplitter.Mode.REGEX;
        } else {
            params.mode = ChapterSplitter.Mode.AUTO;
        }
        return params;
    }
}
