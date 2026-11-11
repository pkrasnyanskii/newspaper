package ru.nsu.newspaper;

import javafx.application.Application;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import ru.nsu.newspaper.model.Issue;
import ru.nsu.newspaper.model.PageFormat;
import ru.nsu.newspaper.storage.IssueStorage;
import ru.nsu.newspaper.ui.ArticlesPane;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;

public final class MainApp extends Application {
    private final IssueStorage storage = new IssueStorage();
    private final TextField titleField = new TextField();
    private final TextField numberField = new TextField("1");
    private final DatePicker datePicker = new DatePicker(LocalDate.now());
    private final ComboBox<PageFormat> formatBox = new ComboBox<>();
    private final Label status = new Label();
    private final Button save = new Button("Save");
    private final Button saveAs = new Button("Save as...");
    private final Tab articlesTab = new Tab("Articles (0)");
    private Issue currentIssue;
    private Path currentFile;
    private Stage stage;
    private ArticlesPane articles;
    private boolean dirty;
    private boolean loading;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        formatBox.getItems().setAll(PageFormat.values());
        formatBox.setValue(PageFormat.A4);
        titleField.setPromptText("Publication title");
        titleField.setId("issue-title");
        numberField.setId("issue-number");
        datePicker.setId("issue-date");
        formatBox.setId("issue-format");
        ChangeListener<Object> edited = (observable, oldValue, newValue) -> {
            if (!loading && currentIssue != null) { dirty = true; refreshStatus(); }
        };
        titleField.textProperty().addListener(edited);
        numberField.textProperty().addListener(edited);
        datePicker.valueProperty().addListener(edited);
        datePicker.getEditor().textProperty().addListener(edited);
        formatBox.valueProperty().addListener(edited);

        Label heading = new Label("Newspaper");
        heading.getStyleClass().add("app-heading");
        heading.setWrapText(true);
        Button create = new Button("New issue");
        Button open = new Button("Open...");
        create.setId("new-issue");
        open.setId("open-issue");
        save.setId("save-issue");
        create.setOnAction(event -> createIssue());
        open.setOnAction(event -> openIssue());
        save.setOnAction(event -> saveIssue(false));
        saveAs.setOnAction(event -> saveIssue(true));
        save.getStyleClass().add("primary-button");
        for (Button button : new Button[]{create, open, save, saveAs}) button.setMinWidth(Region.USE_PREF_SIZE);
        FlowPane actions = new FlowPane(10, 10, create, open, save, saveAs);
        actions.setMinWidth(0);
        VBox header = new VBox(12, heading, actions);
        header.setPadding(new Insets(16, 24, 16, 24));

        articles = new ArticlesPane(stage, updated -> {
            currentIssue = updated;
            dirty = true;
            refreshArticles();
            refreshStatus();
        });
        articlesTab.setContent(articles);
        TabPane tabs = new TabPane(new Tab("Issue details", issueDetails()), articlesTab);
        tabs.setId("workspace-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setMinSize(0, 0);

        status.setId("issue-status");
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        status.setPadding(new Insets(12, 24, 12, 24));
        status.getStyleClass().add("status-bar");
        BorderPane root = new BorderPane(tabs, header, null, status, null);
        Scene scene = new Scene(root, 1040, 780);
        heading.visibleProperty().bind(scene.heightProperty().greaterThanOrEqualTo(440));
        heading.managedProperty().bind(heading.visibleProperty());
        scene.getStylesheets().add(Objects.requireNonNull(MainApp.class.getResource("app.css")).toExternalForm());
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+S"), () -> saveIssue(false));
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+Shift+S"), () -> saveIssue(true));
        primaryStage.setMinWidth(360);
        primaryStage.setMinHeight(360);
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(event -> { if (!confirmUnsavedChanges()) event.consume(); });
        refreshStatus();
        primaryStage.show();
    }

    private ScrollPane issueDetails() {
        Label formHeading = new Label("Issue details");
        formHeading.getStyleClass().add("section-heading");
        Label help = new Label("Fill in the details and choose New issue to start. Then open the Articles tab to add content.");
        help.getStyleClass().add("muted");
        help.setWrapText(true);
        GridPane metadata = new GridPane();
        metadata.setHgap(16);
        metadata.setVgap(16);
        metadata.setMinWidth(0);
        Node[] fields = {field("Issue number", numberField), field("Issue date", datePicker), field("Page format", formatBox)};
        arrangeFields(metadata, fields, 3);
        metadata.widthProperty().addListener((observable, oldWidth, newWidth) ->
                arrangeFields(metadata, fields, newWidth.doubleValue() < 560 ? 1 : 3));
        VBox form = new VBox(18, formHeading, help, field("Publication title", titleField), metadata);
        form.setMinWidth(0);
        form.setPadding(new Insets(24));
        form.getStyleClass().add("issue-card");
        VBox content = new VBox(form);
        content.setPadding(new Insets(24));
        content.setMinWidth(0);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("workspace");
        return scroll;
    }

    private VBox field(String caption, Region control) {
        Label label = new Label(caption);
        label.setLabelFor(control);
        control.setMinWidth(0);
        control.setMaxWidth(Double.MAX_VALUE);
        VBox field = new VBox(7, label, control);
        field.setMinWidth(0);
        return field;
    }

    private void arrangeFields(GridPane grid, Node[] fields, int columns) {
        if (grid.getColumnConstraints().size() == columns) return;
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        for (int i = 0; i < columns; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setMinWidth(0);
            column.setPercentWidth(100.0 / columns);
            column.setHgrow(Priority.ALWAYS);
            column.setFillWidth(true);
            grid.getColumnConstraints().add(column);
        }
        for (int i = 0; i < fields.length; i++) grid.add(fields[i], i % columns, i / columns);
    }

    private void createIssue() {
        try {
            Issue created = Issue.create(number(), date(), pageFormat(), titleField.getText().trim());
            if (!confirmUnsavedChanges()) return;
            currentIssue = created;
            currentFile = null;
            dirty = true;
            refreshArticles();
            refreshStatus();
        } catch (IllegalArgumentException | DateTimeParseException e) {
            error("Cannot create issue", e.getMessage());
        }
    }

    private void openIssue() {
        File selected = chooser("Open issue").showOpenDialog(stage);
        if (selected == null || !confirmUnsavedChanges()) return;
        loadIssue(selected.toPath());
    }

    private void loadIssue(Path file) {
        try {
            Issue loaded = storage.open(file);
            currentIssue = loaded;
            currentFile = file;
            loading = true;
            try {
                titleField.setText(loaded.cover().title());
                numberField.setText(Integer.toString(loaded.issueNumber()));
                datePicker.setValue(loaded.publicationDate());
                formatBox.setValue(loaded.pageFormat());
            } finally { loading = false; }
            dirty = false;
            refreshArticles();
            refreshStatus();
        } catch (IOException e) {
            error("Cannot open issue", e.getMessage());
        }
    }

    private boolean saveIssue(boolean chooseNewFile) {
        if (currentIssue == null) return false;
        try {
            Issue updated = currentIssue.withDetails(number(), date(), pageFormat(), titleField.getText().trim());
            Path target = currentFile;
            if (chooseNewFile || target == null) {
                FileChooser chooser = chooser("Save issue");
                chooser.setInitialFileName("issue-" + updated.issueNumber() + ".issue");
                File selected = chooser.showSaveDialog(stage);
                if (selected == null) return false;
                target = selected.toPath();
                if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".issue")) {
                    target = target.resolveSibling(target.getFileName() + ".issue");
                    // The native dialog only confirmed overwriting the originally entered name.
                    if (Files.exists(target) && !confirmReplacement(target)) return false;
                }
            }
            storage.save(updated, target);
            currentIssue = updated;
            currentFile = target;
            dirty = false;
            refreshStatus();
            return true;
        } catch (IllegalArgumentException | DateTimeParseException | IOException e) {
            error("Cannot save issue", e.getMessage());
            return false;
        }
    }

    private boolean confirmUnsavedChanges() {
        if (!dirty) return true;
        ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        ButtonType saveChanges = new ButtonType("Save", ButtonBar.ButtonData.YES);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Save changes to the current issue before continuing?", saveChanges, discard, ButtonType.CANCEL);
        alert.initOwner(stage);
        alert.setTitle("Unsaved changes");
        alert.setHeaderText("This issue has unsaved changes");
        ButtonType result = alert.showAndWait().orElse(ButtonType.CANCEL);
        return result == discard || result == saveChanges && saveIssue(false);
    }

    private boolean confirmReplacement(Path target) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Replace " + target.getFileName() + "?", ButtonType.YES, ButtonType.CANCEL);
        alert.initOwner(stage);
        alert.setHeaderText("A file with this name already exists");
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.YES;
    }

    private void refreshArticles() {
        articles.setIssue(currentIssue);
        articlesTab.setText("Articles (" + (currentIssue == null ? 0 : currentIssue.articles().size()) + ")");
    }

    private void refreshStatus() {
        save.setDisable(currentIssue == null);
        saveAs.setDisable(currentIssue == null);
        String name = currentFile == null ? "Untitled issue" : currentFile.getFileName().toString();
        status.setText(currentIssue == null ? "No issue is open" : (dirty ? "Unsaved changes · " : "Saved · ") + name);
        status.setTooltip(new Tooltip(currentFile == null ? status.getText() : currentFile.toAbsolutePath().toString()));
        stage.setTitle((dirty ? "* " : "") + "Newspaper" + (currentIssue == null ? "" : " — " + name));
    }

    private FileChooser chooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Issue files (*.issue)", "*.issue"));
        if (currentFile != null && Files.isDirectory(currentFile.toAbsolutePath().getParent())) {
            chooser.setInitialDirectory(currentFile.toAbsolutePath().getParent().toFile());
        }
        return chooser;
    }

    private int number() {
        try { return Integer.parseInt(numberField.getText().trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Issue number must be a positive integer", e); }
    }

    private LocalDate date() {
        datePicker.commitValue();
        if (datePicker.getValue() == null) throw new IllegalArgumentException("Choose an issue date");
        return datePicker.getValue();
    }

    private PageFormat pageFormat() {
        if (formatBox.getValue() == null) throw new IllegalArgumentException("Choose a page format");
        return formatBox.getValue();
    }

    private void error(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void main(String[] args) { launch(args); }
}
