package ru.nsu.newspaper.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Window;
import ru.nsu.newspaper.model.Article;
import ru.nsu.newspaper.model.Issue;

import java.util.UUID;
import java.util.function.Consumer;

/** Article list and preview. Draft edits stay in a modal editor until applied. */
public final class ArticlesPane extends ScrollPane {
    private final ListView<Article> list = new ListView<>();
    private final Label count = new Label("0 articles");
    private final Label title = new Label();
    private final Label author = new Label();
    private final Label length = new Label();
    private final TextArea body = new TextArea();
    private final FlowPane gallery = new FlowPane(12, 12);
    private final VBox details;
    private final Label empty = new Label("Create an article or select one from the list.");
    private final Button add = new Button("New article");
    private final Window owner;
    private final Consumer<Issue> onChange;
    private Issue issue;

    public ArticlesPane(Window owner, Consumer<Issue> onChange) {
        this.owner = owner;
        this.onChange = onChange;
        add.setId("new-article");
        add.getStyleClass().add("primary-button");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(event -> new ArticleEditor(owner, null).showAndWait().ifPresent(article -> {
            onChange.accept(issue.addArticle(article));
            select(article.id());
        }));
        count.getStyleClass().add("section-heading");
        list.setId("article-list");
        list.setMinSize(0, 180);
        list.setPrefSize(0, 260);
        list.setFixedCellSize(72);
        list.setPlaceholder(new Label("No articles yet"));
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(Article article, boolean empty) {
                super.updateItem(article, empty);
                setText(empty || article == null ? null : article.title() + "\n"
                        + (article.author().isEmpty() ? "No author specified" : article.author()));
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) -> showArticle(selected));
        list.setOnMouseClicked(event -> { if (event.getClickCount() == 2) editSelected(); });
        list.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) editSelected(); });
        VBox listCard = new VBox(16, count, add, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        styleCard(listCard);
        listCard.setMinHeight(320);

        title.setId("article-preview-title");
        title.getStyleClass().add("article-heading");
        title.setWrapText(true);
        author.getStyleClass().add("muted");
        author.setWrapText(true);
        body.setId("article-preview-body");
        body.setEditable(false);
        body.setWrapText(true);
        body.setMinSize(0, 180);
        body.setPrefRowCount(12);
        body.getStyleClass().add("article-preview");
        length.getStyleClass().add("muted");
        gallery.setMinWidth(0);
        Button edit = new Button("Edit article");
        edit.setId("edit-article");
        edit.setOnAction(event -> editSelected());
        Button delete = new Button("Delete article");
        delete.setId("delete-article");
        delete.getStyleClass().add("danger-button");
        delete.setOnAction(event -> deleteSelected());
        for (Button button : new Button[]{edit, delete}) button.setMinWidth(Region.USE_PREF_SIZE);
        FlowPane actions = new FlowPane(10, 10, edit, delete);
        details = new VBox(14, title, author, actions, body, length, gallery);
        details.setMinWidth(0);
        VBox.setVgrow(body, Priority.ALWAYS);
        empty.setWrapText(true);
        empty.getStyleClass().add("muted");
        VBox previewCard = new VBox(empty, details);
        VBox.setVgrow(details, Priority.ALWAYS);
        styleCard(previewCard);

        GridPane workspace = new GridPane();
        workspace.setId("articles-workspace");
        workspace.setPadding(new Insets(24));
        workspace.setHgap(20);
        workspace.setVgap(20);
        workspace.setMinWidth(0);
        workspace.widthProperty().addListener((observable, old, width) ->
                arrange(workspace, listCard, previewCard, width.doubleValue() >= 760));
        arrange(workspace, listCard, previewCard, true);
        setContent(workspace);
        setFitToWidth(true);
        setFitToHeight(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().add("workspace");
        setIssue(null);
    }

    public void setIssue(Issue issue) {
        UUID selected = list.getSelectionModel().getSelectedItem() == null ? null
                : list.getSelectionModel().getSelectedItem().id();
        this.issue = issue;
        list.getItems().setAll(issue == null ? java.util.List.of() : issue.articles());
        add.setDisable(issue == null);
        int articleCount = list.getItems().size();
        count.setText(articleCount + (articleCount == 1 ? " article" : " articles"));
        select(selected);
        if (list.getSelectionModel().isEmpty()) list.getSelectionModel().selectFirst();
        if (list.getItems().isEmpty()) showArticle(null);
        empty.setText(issue == null ? "Create or open an issue to start adding articles."
                : "Create an article or select one from the list.");
    }

    private void select(UUID id) {
        if (id == null) return;
        list.getItems().stream().filter(article -> article.id().equals(id)).findFirst()
                .ifPresent(article -> list.getSelectionModel().select(article));
    }

    private void showArticle(Article article) {
        details.setVisible(article != null);
        details.setManaged(article != null);
        empty.setVisible(article == null);
        empty.setManaged(article == null);
        if (article == null) return;
        title.setText(article.title());
        author.setText(article.author().isEmpty() ? "No author specified" : "By " + article.author());
        body.setText(article.text());
        body.positionCaret(0);
        int characters = article.text().codePointCount(0, article.text().length());
        int imageCount = article.images().size();
        length.setText(characters + " characters · " + imageCount + (imageCount == 1 ? " image" : " images"));
        gallery.getChildren().clear();
        article.images().forEach(image -> gallery.getChildren().add(ArticleEditor.thumbnail(image, 140, 90)));
        gallery.setManaged(!article.images().isEmpty());
    }

    private void editSelected() {
        Article selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        new ArticleEditor(owner, selected).showAndWait().ifPresent(edited -> {
            if (!edited.equals(selected)) onChange.accept(issue.updateArticle(edited));
        });
    }

    private void deleteSelected() {
        Article selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        ButtonType delete = new ButtonType("Delete article", ButtonBar.ButtonData.OK_DONE);
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete “" + selected.title() + "” from this issue?", delete, ButtonType.CANCEL);
        confirmation.initOwner(owner);
        confirmation.setTitle("Delete article");
        confirmation.setHeaderText("Delete this article?");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == delete) {
            onChange.accept(issue.removeArticle(selected.id()));
        }
    }

    private void styleCard(VBox card) {
        card.setMinWidth(0);
        card.setPadding(new Insets(20));
        card.getStyleClass().add("issue-card");
    }

    private void arrange(GridPane grid, VBox listCard, VBox previewCard, boolean wide) {
        int columns = wide ? 2 : 1;
        if (grid.getColumnConstraints().size() == columns) return;
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        grid.getRowConstraints().clear();
        for (int i = 0; i < columns; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setMinWidth(0);
            column.setPercentWidth(wide ? (i == 0 ? 32 : 68) : 100);
            grid.getColumnConstraints().add(column);
        }
        RowConstraints row = new RowConstraints();
        row.setVgrow(wide ? Priority.ALWAYS : Priority.NEVER);
        grid.getRowConstraints().add(row);
        grid.add(listCard, 0, 0);
        grid.add(previewCard, wide ? 1 : 0, wide ? 0 : 1);
        if (!wide) {
            RowConstraints previewRow = new RowConstraints();
            previewRow.setVgrow(Priority.ALWAYS);
            grid.getRowConstraints().add(previewRow);
        }
    }
}
