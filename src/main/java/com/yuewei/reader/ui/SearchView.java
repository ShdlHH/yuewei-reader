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
    private final Label progressLabel = Ui.label("", "muted");
    private final ListView<String> progressList = new ListView<>();
    private final TableView<AggregatedResult> results = new TableView<>();
    private final ListView<SearchResult> sourceList = new ListView<>();
    private final Button addButton = Ui.button("加入书架", "primary-button");
    private final Label detailTitle = Ui.label("", "panel-title");
    private final Label detailMeta = Ui.label("", "muted");
    private final Label detailHint = Ui.label("选中一条结果后可加入书架", "muted");
    private final Label pageSub = Ui.label("", "page-sub");
    private final Label scopeHint = Ui.label("", "muted");

    public SearchView(AppContext ctx, MainWindow window) {
        this.ctx = ctx;
        this.window = window;
        build();
    }

    private void build() {
        keyword.setPromptText("书名或作者，回车搜索");
        keyword.setPrefHeight(32);
        keyword.setMaxWidth(520);
        keyword.setOnAction(e -> search());
        HBox.setHgrow(keyword, Priority.ALWAYS);

        searchButton.setPrefHeight(32);
        searchButton.setOnAction(e -> search());

        scope.setPrefWidth(176);
        scope.setPrefHeight(32);
        scope.setItems(FXCollections.observableArrayList(ALL));
        scope.setValue(ALL);

        Label pageTitle = Ui.label("搜索", "page-title");
        VBox titles = new VBox(4, pageTitle, pageSub);
        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        HBox header = new HBox(12, titles, headSpacer, progressLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("page-header");

        HBox toolbar = new HBox(8, keyword, searchButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("page-toolbar");

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
        results.setFixedCellSize(40);
        results.setPlaceholder(Ui.label("输入书名或作者开始搜索", "muted"));
        results.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> showSources(newValue));

        sourceList.setPlaceholder(Ui.label("", "muted"));
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
        sourceList.setPrefHeight(84);
        sourceList.setVisible(false);
        sourceList.setManaged(false);

        addButton.setDisable(true);
        addButton.setOnAction(e -> addToShelf());
        addButton.setPrefWidth(132);
        addButton.setMaxWidth(132);
        addButton.getStyleClass().remove("primary-button");
        addButton.getStyleClass().add("ghost-button");
        addButton.disableProperty().addListener((observable, oldValue, disabled) -> {
            addButton.getStyleClass().removeAll("primary-button", "ghost-button");
            addButton.getStyleClass().add(disabled ? "ghost-button" : "primary-button");
        });

        detailTitle.setVisible(false);
        detailTitle.setManaged(false);
        detailMeta.setVisible(false);
        detailMeta.setManaged(false);

        VBox detailInfo = new VBox(4, detailTitle, detailMeta, detailHint);
        HBox.setHgrow(detailInfo, Priority.ALWAYS);
        HBox detailRow = new HBox(12, detailInfo, addButton);
        detailRow.setAlignment(Pos.CENTER_LEFT);
        VBox detailCard = new VBox(8, detailRow, sourceList);
        detailCard.getStyleClass().add("search-detail");

        VBox resultCard = new VBox(results);
        VBox.setVgrow(results, Priority.ALWAYS);
        VBox mainColumn = new VBox(12, resultCard, detailCard);
        VBox.setVgrow(resultCard, Priority.ALWAYS);
        HBox.setHgrow(mainColumn, Priority.ALWAYS);

        progressList.setPlaceholder(Ui.label("", "muted"));
        progressList.setVisible(false);
        progressList.setManaged(false);
        progressList.setPrefHeight(200);
        VBox sideCard = new VBox(10, Ui.label("书源", "card-title"), scope, progressList, scopeHint);
        sideCard.getStyleClass().add("search-side");
        sideCard.setPrefWidth(200);
        sideCard.setMinWidth(200);
        sideCard.setMaxWidth(200);
        sideCard.setMaxHeight(Region.USE_PREF_SIZE);
        VBox.setVgrow(progressList, Priority.ALWAYS);

        HBox body = new HBox(12, mainColumn, sideCard);
        body.setAlignment(Pos.TOP_LEFT);
        body.setPadding(new Insets(0, Ui.SPACE_LIST, Ui.SPACE_LIST, Ui.SPACE_LIST));

        root.setTop(new VBox(header, toolbar));
        root.setCenter(body);
    }

    /** 进度列表只在有内容时出现，空状态只留结果区一条。 */
    private void setProgressVisible(boolean visible) {
        progressList.setVisible(visible);
        progressList.setManaged(visible);
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
        setProgressVisible(true);
        results.getItems().clear();
        sourceList.getItems().clear();
        showSources(null);
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
        setProgressVisible(!progressList.getItems().isEmpty());
    }

    private void showSources(AggregatedResult aggregated) {
        if (aggregated == null) {
            sourceList.getItems().clear();
            detailTitle.setText("");
            detailMeta.setText("");
            detailTitle.setVisible(false);
            detailTitle.setManaged(false);
            detailMeta.setVisible(false);
            detailMeta.setManaged(false);
            detailHint.setVisible(true);
            detailHint.setManaged(true);
            sourceList.setVisible(false);
            sourceList.setManaged(false);
            addButton.setDisable(true);
            return;
        }
        detailTitle.setText(aggregated.title);
        detailMeta.setText((aggregated.author == null ? "未知作者" : aggregated.author)
                + " · " + aggregated.sources.size() + " 个来源");
        detailTitle.setVisible(true);
        detailTitle.setManaged(true);
        detailMeta.setVisible(true);
        detailMeta.setManaged(true);
        detailHint.setVisible(false);
        detailHint.setManaged(false);
        sourceList.setVisible(true);
        sourceList.setManaged(true);
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
        int count = searchable.size();
        pageSub.setText(count + " 个书源 · 可搜索");
        scopeHint.setText(count == 0 ? "尚未添加书源" : "搜索时显示进度");
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
