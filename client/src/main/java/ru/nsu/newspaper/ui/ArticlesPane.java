package ru.nsu.newspaper.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Window;
import ru.nsu.newspaper.model.Article;
import ru.nsu.newspaper.model.Issue;

import java.util.UUID;
import java.util.List;
import ru.nsu.newspaper.protocol.Protocol.EditLock;
import ru.nsu.newspaper.protocol.Role;

/** Article list and preview. Draft edits stay in a modal editor until applied. */
public final class ArticlesPane extends ScrollPane {
    private final ListView<Article> list = new ListView<>();
    private final Label count = new Label("0 articles");
    private final Label title = new Label();
    private final Label author = new Label();
    private final Label length = new Label();
    private final ScrollPane body = new ScrollPane();
    private final Button style = new Button("Style text...");
    private final FlowPane gallery = new FlowPane(12, 12);
    private final VBox details;
    private final Label empty = new Label("Create an article or select one from the list.");
    private final Button add = new Button("New article");
    private final Window owner;
    public interface Actions {
        void create();
        void edit(Article article);
        void delete(Article article);
        void style(Article article);
    }
    private final Actions actions;
    private final Button edit = new Button("Edit article");
    private final Button delete = new Button("Delete article");
    private final Label lockStatus = new Label();
    private List<EditLock> locks = List.of();
    private UUID sessionId;
    private boolean writable;
    private boolean styling;
    private Issue issue;

    public ArticlesPane(Window owner, Actions actions) {
        this.owner = owner;
        this.actions = actions;
        add.setId("new-article");
        add.getStyleClass().add("primary-button");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(event -> { if (writable && issue != null) actions.create(); });
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
        body.setFitToWidth(true);
        body.setMinSize(0, 180);
        body.setPrefHeight(260);
        body.getStyleClass().add("article-preview");
        length.getStyleClass().add("muted");
        gallery.setMinWidth(0);
        edit.setId("edit-article");
        edit.setOnAction(event -> editSelected());
        delete.setId("delete-article");
        delete.getStyleClass().add("danger-button");
        delete.setOnAction(event -> deleteSelected());
        for (Button button : new Button[]{edit, delete, style}) button.setMinWidth(Region.USE_PREF_SIZE);
        style.setOnAction(event -> {
            Article selected = list.getSelectionModel().getSelectedItem();
            if (selected != null && !style.isDisabled()) actions.style(selected);
        });
        FlowPane buttons = new FlowPane(10, 10, edit, delete, style);
        lockStatus.setId("article-lock-status");
        lockStatus.setWrapText(true);
        lockStatus.getStyleClass().add("muted");
        details = new VBox(14, title, author, lockStatus, buttons, body, length, gallery);
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
        add.setDisable(issue == null || !writable);
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
        updateAccess();
        if (article == null) return;
        title.setText(article.title());
        author.setText(article.author().isEmpty() ? "No author specified" : "By " + article.author());
        body.setContent(ArticleStyles.preview(article));
        body.setVvalue(0);
        int characters = article.text().codePointCount(0, article.text().length());
        int imageCount = article.images().size();
        length.setText(characters + " characters · " + imageCount + (imageCount == 1 ? " image" : " images"));
        gallery.getChildren().clear();
        article.images().forEach(image -> gallery.getChildren().add(ArticleEditor.thumbnail(image, 140, 90)));
        gallery.setManaged(!article.images().isEmpty());
    }

    public void setAccess(Role role, boolean online, UUID sessionId, List<EditLock> locks) {
        this.writable = online && role == Role.JOURNALIST;
        this.styling = online && role == Role.LAYOUT_DESIGNER;
        this.sessionId = sessionId;
        this.locks = locks;
        updateAccess();
    }

    private void updateAccess() {
        Article selected = list.getSelectionModel().getSelectedItem();
        EditLock lock = selected == null ? null : locks.stream()
                .filter(value -> value.articleId().equals(selected.id())).findFirst().orElse(null);
        boolean heldByOther = lock != null && !lock.sessionId().equals(sessionId);
        lockStatus.setText(lock == null ? "" : "Being edited by " + lock.role()
                + (heldByOther ? "" : " (you)"));
        lockStatus.setManaged(lock != null);
        add.setDisable(!writable || issue == null);
        edit.setDisable(!writable || selected == null || heldByOther);
        delete.setDisable(!writable || selected == null || heldByOther);
        style.setDisable(!styling || selected == null || heldByOther || selected.text().isEmpty());
    }

    private void editSelected() {
        Article selected = list.getSelectionModel().getSelectedItem();
        if (selected != null && !edit.isDisabled()) actions.edit(selected);
    }

    private void deleteSelected() {
        Article selected = list.getSelectionModel().getSelectedItem();
        if (selected != null && !delete.isDisabled()) actions.delete(selected);
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
