package com.yuewei.reader.ui;

import com.yuewei.reader.application.AppContext;
import com.yuewei.reader.application.SearchOutcome;
import com.yuewei.reader.domain.AggregatedResult;
import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.BookSource;
import com.yuewei.reader.domain.SearchResult;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 在线搜索：多源并行、结果按书名聚合、选择来源加入书架。 */
public class SearchView implements AppView {

    private static final String ALL = "全部可用书源";

    private final AppContext ctx;
    private final MainWindow window;
    private final BorderPane root = new BorderPane();

    private final TextField keyword = new TextField();
    private final ComboBox<String> scope = new ComboBox<>();
    private final Button searchButton = Ui.button("搜索", "primary-button");
    private final Label progressLabel = Ui.label("输入书名或作者，回车开始搜索", "muted");
    private final ListView<String> progressList = new ListView<>();
    private final TableView<AggregatedResult> results = new TableView<>();
    private final ListView<SearchResult> sourceList = new ListView<>();
    private final Button addButton = Ui.button("加入书架", "primary-button");
    private final Label detailTitle = Ui.label("", "panel-title");
    private final Label detailMeta = Ui.label("", "muted");

    public SearchView(AppContext ctx, MainWindow window) {
        this.ctx = ctx;
        this.window = window;
        build();
    }

    private void build() {
        keyword.setPromptText("书名或作者，回车搜索");
        keyword.setPrefWidth(280);
        keyword.setOnAction(e -> search());
        searchButton.setOnAction(e -> search());

        scope.setPrefWidth(200);
        scope.setItems(FXCollections.observableArrayList(ALL));
        scope.setValue(ALL);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(10, keyword, scope, searchButton, spacer, progressLabel);
        toolbar.getStyleClass().add("toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(12, 16, 12, 16));

        TableColumn<AggregatedResult, String> titleCol = resultColumn("书名", 230,
                result -> result.title);
        TableColumn<AggregatedResult, String> authorCol = resultColumn("作者", 120,
                result -> result.author);
        TableColumn<AggregatedResult, String> lastCol = resultColumn("最新章节", 220,
                result -> result.lastChapter);
        TableColumn<AggregatedResult, String> sourceCol = resultColumn("来源", 200,
                AggregatedResult::sourceSummary);
        results.getColumns().addAll(titleCol, authorCol, lastCol, sourceCol);
        results.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        results.setPlaceholder(Ui.label("搜索结果会显示在这里", "muted"));
        results.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> showSources(newValue));

        VBox progressBox = new VBox(6, Ui.label("书源进度", "panel-title"), progressList);
        progressBox.setPadding(new Insets(10));
        progressBox.setPrefWidth(240);
        progressBox.setMinWidth(200);
        progressList.setPlaceholder(Ui.label("尚未搜索", "muted"));

        sourceList.setPlaceholder(Ui.label("选择左侧结果查看来源", "muted"));
        sourceList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SearchResult item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.sourceName + " · " + (item.lastChapter == null ? "—" : item.lastChapter));
                }
            }
        });

        addButton.setDisable(true);
        addButton.setOnAction(e -> addToShelf());
        addButton.setMaxWidth(Double.MAX_VALUE);

        VBox detailBox = new VBox(8, detailTitle, detailMeta, sourceList, addButton);
        detailBox.setPadding(new Insets(10));
        detailBox.setPrefWidth(320);
        VBox.setVgrow(sourceList, Priority.ALWAYS);

        BorderPane body = new BorderPane();
        body.setCenter(results);
        body.setRight(detailBox);
        BorderPane.setMargin(detailBox, new Insets(0, 0, 0, 8));

        SplitPane main = new SplitPane(progressBox, body);
        main.setDividerPositions(0.2);

        root.setTop(toolbar);
        root.setCenter(main);
        BorderPane.setMargin(main, new Insets(0, 16, 16, 16));

        addButton.setOnAction(e -> addToShelf());
    }

    private TableColumn<AggregatedResult, String> resultColumn(
            String title, double width, java.util.function.Function<AggregatedResult, String> getter) {
        TableColumn<AggregatedResult, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> {
            String value = getter.apply(cell.getValue());
            return new ReadOnlyStringWrapper(value == null ? "" : value);
        });
        return column;
    }

    public void focusKeyword() {
        keyword.requestFocus();
    }

    private void search() {
        String text = keyword.getText() == null ? "" : keyword.getText().trim();
        if (text.isEmpty()) {
            Ui.error("请输入书名或作者");
            return;
        }
        List<BookSource> chosen = chosenSources();
        if (chosen.isEmpty()) {
            Ui.error("没有可用书源。请先在「书源」页导入并校验通过。");
            return;
        }
        searchButton.setDisable(true);
        progressList.getItems().clear();
        results.getItems().clear();
        sourceList.getItems().clear();
        detailTitle.setText("");
        detailMeta.setText("");
        addButton.setDisable(true);
        progressLabel.setText("正在搜索 " + chosen.size() + " 个书源…");
        window.status("正在搜索「" + text + "」…");

        Ui.async("search",
                () -> ctx.searchService.search(text, chosen, 1,
                        progress -> Platform.runLater(() -> progressList.getItems().add(progress.toString()))),
                outcome -> {
                    searchButton.setDisable(false);
                    applyOutcome(outcome);
                },
                error -> {
                    searchButton.setDisable(false);
                    progressLabel.setText("搜索失败");
                    Ui.error("搜索失败", Ui.message(error));
                });
    }

    private void applyOutcome(SearchOutcome outcome) {
        List<AggregatedResult> aggregated = outcome.aggregate();
        results.setItems(FXCollections.observableArrayList(aggregated));
        if (!outcome.progress.isEmpty()) {
            progressList.getItems().setAll(outcome.progress.stream()
                    .map(SearchOutcome.SourceProgress::toString).toList());
        }
        int ok = outcome.progress.size() - outcome.failures.size();
        String summary = aggregated.size() + " 条结果 · " + ok + " 个书源成功";
        if (!outcome.failures.isEmpty()) {
            summary += " · " + outcome.failures.size() + " 个失败";
        }
        progressLabel.setText(summary + " · " + outcome.elapsedMs + " ms");
        window.status("搜索完成：" + summary);
        if (aggregated.isEmpty() && !outcome.failures.isEmpty()) {
            progressList.getItems().addAll(outcome.failures);
        }
    }

    private void showSources(AggregatedResult aggregated) {
        if (aggregated == null) {
            sourceList.getItems().clear();
            detailTitle.setText("");
            detailMeta.setText("");
            addButton.setDisable(true);
            return;
        }
        detailTitle.setText(aggregated.title);
        detailMeta.setText((aggregated.author == null ? "未知作者" : aggregated.author)
                + " · " + aggregated.sources.size() + " 个来源");
        sourceList.setItems(FXCollections.observableArrayList(aggregated.sources));
        sourceList.getSelectionModel().selectFirst();
        addButton.setDisable(false);
    }

    private void addToShelf() {
        SearchResult result = sourceList.getSelectionModel().getSelectedItem();
        if (result == null) {
            Ui.error("请选择要加入的来源");
            return;
        }
        BookSource source = ctx.sourceService.findById(result.sourceId);
        if (source == null) {
            Ui.error("这条结果对应的书源已被删除");
            return;
        }
        addButton.setDisable(true);
        window.status("正在获取《" + result.title + "》的目录…");
        Ui.async("add-to-shelf", () -> {
            BookDetail detail = ctx.onlineBookService.fetchDetail(source, result);
            return ctx.onlineBookService.addToShelf(source, detail);
        }, book -> {
            addButton.setDisable(false);
            window.refreshShelf();
            window.status("已加入书架：《" + book.title + "》");
            Ui.info("已加入书架：《" + book.title + "》，共 " + book.chapterCount + " 章");
        }, error -> {
            addButton.setDisable(false);
            window.status("加入书架失败");
            Ui.error("加入书架失败", Ui.message(error));
        });
    }

    private List<BookSource> chosenSources() {
        List<BookSource> all = ctx.sourceService.findSearchable();
        String selection = scope.getValue();
        if (selection == null || ALL.equals(selection)) {
            return all;
        }
        if (selection.startsWith("分组：")) {
            String group = selection.substring(3);
            List<BookSource> list = new ArrayList<>();
            for (BookSource source : all) {
                if (group.equals(source.group == null ? "" : source.group)) {
                    list.add(source);
                }
            }
            return list;
        }
        List<BookSource> list = new ArrayList<>();
        for (BookSource source : all) {
            if (source.name.equals(selection)) {
                list.add(source);
            }
        }
        return list;
    }

    private void refreshScope() {
        String current = scope.getValue();
        Set<String> items = new LinkedHashSet<>();
        items.add(ALL);
        List<BookSource> searchable = ctx.sourceService.findSearchable();
        for (BookSource source : searchable) {
            if (source.group != null && !source.group.isBlank()) {
                items.add("分组：" + source.group);
            }
        }
        for (BookSource source : searchable) {
            items.add(source.name);
        }
        scope.setItems(FXCollections.observableArrayList(items));
        scope.setValue(items.contains(current) ? current : ALL);
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        refreshScope();
    }
}
