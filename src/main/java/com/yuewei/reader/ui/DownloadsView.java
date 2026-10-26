package com.yuewei.reader.ui;

import com.yuewei.reader.application.AppContext;
import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.DownloadRangeType;
import com.yuewei.reader.domain.DownloadState;
import com.yuewei.reader.domain.DownloadTask;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;

/** 下载任务：新建、暂停、继续、取消与进度查看。 */
public class DownloadsView implements AppView {

    private final AppContext ctx;
    private final MainWindow window;
    private final BorderPane root = new BorderPane();
    private final TableView<DownloadTask> table = new TableView<>();
    private final Label summary = Ui.label("", "muted");
    private final Button pauseButton = Ui.button("暂停", "ghost-button");
    private final Button resumeButton = Ui.button("继续", "ghost-button");
    private final Button cancelButton = Ui.button("取消", "ghost-button");

    public DownloadsView(AppContext ctx, MainWindow window) {
        this.ctx = ctx;
        this.window = window;
        build();
        ctx.downloadService.addListener((task, note) -> Platform.runLater(() -> {
            refresh();
            if (note != null && !note.isBlank()) {
                window.status(task.bookTitle + "：" + note);
            }
        }));
    }

    private void build() {
        TableColumn<DownloadTask, String> titleCol = column("书名", 220, task -> task.bookTitle);
        TableColumn<DownloadTask, String> rangeCol = column("范围", 120, task -> rangeText(task));
        TableColumn<DownloadTask, String> stateCol = column("状态", 100,
                task -> task.state.label);
        TableColumn<DownloadTask, String> progressCol = column("进度", 140,
                task -> "成功 " + task.successCount + " · 失败 " + task.failCount);
        TableColumn<DownloadTask, String> failedCol = column("失败章节", 180,
                task -> task.failedIndexes.isEmpty() ? "" : joinIndexes(task.failedIndexes));
        TableColumn<DownloadTask, String> updatedCol = column("更新时间", 120,
                task -> Ui.formatTime(task.updatedAt == null ? task.createdAt : task.updatedAt));

        table.getColumns().addAll(titleCol, rangeCol, stateCol, progressCol, failedCol, updatedCol);
        table.setPlaceholder(Ui.label("暂无下载任务。可在书架右键「全部下载」，或点击左上角「新建下载」。", "muted"));
        table.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> updateButtons());

        Button create = Ui.button("新建下载", "primary-button");
        create.setOnAction(e -> create());
        Button refresh = Ui.button("刷新", "ghost-button");
        refresh.setOnAction(e -> refresh());
        pauseButton.setOnAction(e -> pause());
        resumeButton.setOnAction(e -> resume());
        cancelButton.setOnAction(e -> cancel());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, create, pauseButton, resumeButton, cancelButton, refresh, spacer, summary);
        toolbar.getStyleClass().add("toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(12, 16, 12, 16));

        root.setTop(toolbar);
        root.setCenter(table);
        BorderPane.setMargin(table, new Insets(0, 16, 16, 16));
        updateButtons();
        refresh();
    }

    private TableColumn<DownloadTask, String> column(String title, double width,
                                                     java.util.function.Function<DownloadTask, String> getter) {
        TableColumn<DownloadTask, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> {
            String value = getter.apply(cell.getValue());
            return new ReadOnlyStringWrapper(value == null ? "" : value);
        });
        return column;
    }

    private String rangeText(DownloadTask task) {
        return switch (task.rangeType) {
            case ALL -> "全部章节";
            case UNCACHED -> "仅未缓存";
            case RANGE -> (task.fromIndex == null ? 1 : task.fromIndex + 1) + " - "
                    + (task.toIndex == null ? "末章" : String.valueOf(task.toIndex + 1));
        };
    }

    private String joinIndexes(List<Integer> indexes) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < indexes.size() && i < 12; i++) {
            if (i > 0) {
                builder.append("、");
            }
            builder.append(indexes.get(i) + 1);
        }
        if (indexes.size() > 12) {
            builder.append(" …共 ").append(indexes.size()).append(" 章");
        }
        return builder.toString();
    }

    public void refresh() {
        DownloadTask selected = table.getSelectionModel().getSelectedItem();
        List<DownloadTask> tasks = ctx.downloadService.tasks();
        table.setItems(FXCollections.observableArrayList(tasks));
        if (selected != null) {
            for (DownloadTask task : tasks) {
                if (task.id == selected.id) {
                    table.getSelectionModel().select(task);
                    break;
                }
            }
        }
        int running = (int) tasks.stream().filter(task -> task.state == DownloadState.RUNNING).count();
        int queued = (int) tasks.stream().filter(task -> task.state == DownloadState.QUEUED).count();
        summary.setText("共 " + tasks.size() + " 个任务 · 进行中 " + running + " · 排队 " + queued);
        updateButtons();
    }

    private void updateButtons() {
        DownloadTask task = table.getSelectionModel().getSelectedItem();
        boolean canPause = task != null
                && (task.state == DownloadState.RUNNING || task.state == DownloadState.QUEUED);
        boolean canResume = task != null
                && (task.state == DownloadState.PAUSED || task.state == DownloadState.PARTIAL_FAILED);
        boolean canCancel = task != null
                && (task.state == DownloadState.RUNNING || task.state == DownloadState.QUEUED
                || task.state == DownloadState.PAUSED);
        pauseButton.setDisable(!canPause);
        resumeButton.setDisable(!canResume);
        resumeButton.setText(task != null && task.state == DownloadState.PARTIAL_FAILED ? "重试失败" : "继续");
        cancelButton.setDisable(!canCancel);
    }

    private void pause() {
        DownloadTask task = table.getSelectionModel().getSelectedItem();
        if (task != null) {
            ctx.downloadService.pause(task.id);
            refresh();
        }
    }

    private void resume() {
        DownloadTask task = table.getSelectionModel().getSelectedItem();
        if (task == null) {
            return;
        }
        try {
            ctx.downloadService.resume(task.id);
            refresh();
        } catch (RuntimeException e) {
            Ui.error("继续下载失败", Ui.message(e));
        }
    }

    private void cancel() {
        DownloadTask task = table.getSelectionModel().getSelectedItem();
        if (task != null) {
            ctx.downloadService.cancel(task.id);
            refresh();
        }
    }

    private void create() {
        List<Book> online = new ArrayList<>();
        for (Book book : ctx.bookService.shelf(null, com.yuewei.reader.domain.SourceType.ONLINE, "recent")) {
            online.add(book);
        }
        if (online.isEmpty()) {
            Ui.error("书架里还没有在线书。请先搜索并加入书架。");
            return;
        }
        ChoiceDialog<Book> choice = new ChoiceDialog<>(online.get(0), online);
        choice.setTitle("新建下载");
        choice.setHeaderText(null);
        choice.setContentText("选择书籍");
        choice.initOwner(window.stage());
        Book book = choice.showAndWait().orElse(null);
        if (book == null) {
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("下载《" + book.title + "》");
        dialog.initOwner(window.stage());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ToggleGroup group = new ToggleGroup();
        RadioButton all = new RadioButton("全部章节");
        RadioButton uncached = new RadioButton("仅未缓存章节");
        RadioButton range = new RadioButton("指定章节范围");
        all.setToggleGroup(group);
        uncached.setToggleGroup(group);
        range.setToggleGroup(group);
        group.selectToggle(uncached);

        Spinner<Integer> from = new Spinner<>(1, Math.max(1, book.chapterCount), 1);
        Spinner<Integer> to = new Spinner<>(1, Math.max(1, book.chapterCount),
                Math.max(1, book.chapterCount));
        from.disableProperty().bind(range.selectedProperty().not());
        to.disableProperty().bind(range.selectedProperty().not());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        grid.add(all, 0, 0, 3, 1);
        grid.add(uncached, 0, 1, 3, 1);
        grid.add(range, 0, 2, 3, 1);
        grid.add(new Label("从第"), 0, 3);
        grid.add(from, 1, 3);
        grid.add(new Label("章 到第"), 2, 3);
        grid.add(to, 3, 3);
        grid.add(new Label("章"), 4, 3);
        dialog.getDialogPane().setContent(grid);

        if (dialog.showAndWait().orElse(null) != ButtonType.OK) {
            return;
        }
        DownloadRangeType type = DownloadRangeType.UNCACHED;
        Integer fromIndex = null;
        Integer toIndex = null;
        if (all.isSelected()) {
            type = DownloadRangeType.ALL;
        } else if (range.isSelected()) {
            type = DownloadRangeType.RANGE;
            fromIndex = from.getValue() - 1;
            toIndex = to.getValue() - 1;
            if (fromIndex > toIndex) {
                Ui.error("起始章节不能大于结束章节");
                return;
            }
        }
        try {
            ctx.downloadService.start(book, type, fromIndex, toIndex);
            refresh();
            window.status("已创建下载任务：" + book.title);
        } catch (RuntimeException e) {
            Ui.error("创建下载任务失败", Ui.message(e));
        }
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
