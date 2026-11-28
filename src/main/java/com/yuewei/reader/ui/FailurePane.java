package com.yuewei.reader.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/** 统一失败态：一句人话原因 + 可执行动作 + 可折叠的技术详情。 */
public final class FailurePane {

    /** 一个可执行动作。primary 决定视觉主次。 */
    public record Action(String text, boolean primary, Runnable run) {
    }

    private FailurePane() {
    }

    public static Region build(String message, String detail, List<Action> actions) {
        Label title = Ui.label(message == null || message.isBlank() ? "加载失败" : message, "failure-title");
        title.setWrapText(true);
        title.setMaxWidth(460);

        HBox buttons = new HBox(8);
        buttons.setAlignment(Pos.CENTER);
        for (Action action : actions) {
            Button button = Ui.button(action.text(), action.primary() ? "primary-button" : "ghost-button");
            button.setOnAction(e -> action.run().run());
            buttons.getChildren().add(button);
        }

        Label detailLabel = new Label(detail == null ? "" : detail);
        detailLabel.getStyleClass().add("muted");
        detailLabel.setWrapText(true);
        detailLabel.setMaxWidth(460);
        detailLabel.setVisible(false);
        detailLabel.setManaged(false);

        Hyperlink toggle = new Hyperlink("详情");
        toggle.setFocusTraversable(false);
        toggle.setOnAction(e -> {
            boolean visible = !detailLabel.isVisible();
            detailLabel.setVisible(visible);
            detailLabel.setManaged(visible);
            toggle.setText(visible ? "收起" : "详情");
        });
        toggle.setVisible(detail != null && !detail.isBlank());
        toggle.setManaged(toggle.isVisible());

        VBox box = new VBox(10, title, buttons, toggle, detailLabel);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40, 24, 40, 24));
        box.getStyleClass().add("failure-pane");
        return box;
    }
}
