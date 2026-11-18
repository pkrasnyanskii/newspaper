package ru.nsu.newspaper.ui;

import javafx.event.ActionEvent;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Screen;
import javafx.stage.Window;
import ru.nsu.newspaper.model.Article;
import ru.nsu.newspaper.model.ImageAsset;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ArticleEditor extends Dialog<Article> {
    private final TextField title = new TextField();
    private final TextField author = new TextField();
    private final TextArea body = new TextArea();
    private final List<ImageAsset> images = new ArrayList<>();
    private final FlowPane gallery = new FlowPane(12, 12);
    private final Label validation = new Label();
    private final ScrollPane scroll = new ScrollPane();
    private final Article original;
    private final java.util.UUID draftId;
    private final Label connectionStatus = new Label();
    private VBox content;
    private Button applyButton;
    private java.util.function.Consumer<Article> submit;
    private boolean pending;

    public ArticleEditor(Window owner, Article original) {
        this.original = original;
        draftId = original == null ? java.util.UUID.randomUUID() : original.id();
        initOwner(owner);
        setTitle(original == null ? "New article" : "Edit article");
        setResizable(true);
        title.setId("article-title");
        author.setId("article-author");
        body.setId("article-body");
        title.setPromptText("Article title (required)");
        author.setPromptText("Author name");
        body.setPromptText("Write the article here...");
        body.setWrapText(true);
        body.setPrefRowCount(9);
        body.setMinHeight(180);
        if (original != null) {
            title.setText(original.title());
            author.setText(original.author());
            body.setText(original.text());
            images.addAll(original.images());
        }

        Label heading = new Label(original == null ? "Write an article" : "Edit article");
        heading.getStyleClass().add("section-heading");
        Label hint = new Label("Apply sends your changes to the shared issue. Cancel discards this draft.");
        hint.setWrapText(true);
        hint.getStyleClass().add("muted");
        Button addImages = new Button("Add images...");
        addImages.setId("add-article-images");
        addImages.setOnAction(event -> chooseImages());
        Label imageHint = new Label("Optional · PNG or JPEG · up to 20 MB / 25 MP per image");
        imageHint.setWrapText(true);
        imageHint.getStyleClass().add("muted");
        validation.setId("article-validation");
        validation.getStyleClass().add("validation-error");
        validation.setWrapText(true);
        validation.setVisible(false);
        validation.setManaged(false);
        title.textProperty().addListener((observable, old, value) -> {
            if (!value.isBlank() && validation.getText().startsWith("Enter an article title")) showValidation("");
        });
        gallery.setMinWidth(0);
        refreshImages();

        content = new VBox(16, heading, hint, field("Title *", title), validation, field("Author", author),
                field("Article text", body), new VBox(8, addImages, imageHint, gallery));
        content.setPadding(new Insets(24));
        content.setMinWidth(0);
        scroll.setContent(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("workspace");
        scroll.setMinSize(0, 0);
        connectionStatus.setWrapText(true);
        connectionStatus.getStyleClass().add("validation-error");
        VBox shell = new VBox(10, connectionStatus, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        shell.setMinSize(0, 0);
        getDialogPane().setContent(shell);
        getDialogPane().setPrefSize(740, 720);
        getDialogPane().setMinSize(0, 0);
        getDialogPane().getStylesheets().add(Objects.requireNonNull(
                ArticleEditor.class.getResource("/ru/nsu/newspaper/app.css")).toExternalForm());
        ButtonType apply = new ButtonType(original == null ? "Add article" : "Apply changes", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        applyButton = (Button) getDialogPane().lookupButton(apply);
        applyButton.setId("apply-article");
        applyButton.getStyleClass().add("primary-button");
        applyButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (title.getText().isBlank()) {
                showValidation("Enter an article title. Your text is still here.");
                title.requestFocus();
                scroll.setVvalue(0);
                event.consume();
            } else if (submit != null) {
                event.consume();
                submit.accept(article());
            }
        });
        setResultConverter(button -> button == apply ? article() : null);
        setOnCloseRequest(event -> { if (pending) event.consume(); });
        setOnShown(event -> Platform.runLater(() -> {
            if (!isShowing()) return;
            Stage window = (Stage) getDialogPane().getScene().getWindow();
            window.setMinWidth(360);
            window.setMinHeight(320);
            var bounds = Screen.getScreensForRectangle(owner.getX(), owner.getY(), 1, 1).getFirst().getVisualBounds();
            window.setWidth(Math.min(740, bounds.getWidth() - 32));
            window.setHeight(Math.min(760, bounds.getHeight() - 32));
            title.requestFocus();
        }));
    }

    public void onSubmit(java.util.function.Consumer<Article> action) { submit = action; }

    public void setWritable(boolean writable, String message) {
        title.setEditable(writable);
        author.setEditable(writable);
        body.setEditable(writable);
        content.lookupAll(".button").forEach(button -> button.setDisable(!writable));
        applyButton.setDisable(!writable);
        connectionStatus.setText(message);
        connectionStatus.setManaged(!message.isBlank());
    }

    public void setPending(boolean pending) {
        this.pending = pending;
        getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(pending);
    }

    public void showFailure(String message) { showValidation(message); }

    private Article article() {
        return original == null
                ? new Article(draftId, title.getText(), author.getText(), body.getText(), images)
                : original.withContent(title.getText(), author.getText(), body.getText(), images);
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

    private void chooseImages() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add article images");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "PNG and JPEG images", "*.png", "*.jpg", "*.jpeg", "*.PNG", "*.JPG", "*.JPEG"));
        List<File> selected = chooser.showOpenMultipleDialog(getDialogPane().getScene().getWindow());
        if (selected == null) return;
        importImages(selected);
    }

    private void importImages(List<File> selected) {
        List<String> failures = new ArrayList<>();
        for (File file : selected) {
            try (var input = Files.newInputStream(file.toPath())) {
                ImageAsset image = ImageAsset.fromBytes(input.readNBytes(ImageAsset.MAX_BYTES + 1));
                if (!images.contains(image)) images.add(image);
            } catch (IOException e) {
                failures.add(file.getName() + ": " + e.getMessage());
            }
        }
        refreshImages();
        showValidation(String.join("\n", failures));
    }

    private void refreshImages() {
        gallery.getChildren().clear();
        for (int i = 0; i < images.size(); i++) {
            ImageAsset asset = images.get(i);
            ImageView preview = thumbnail(asset, 140, 90);
            Button remove = new Button("Remove image " + (i + 1));
            remove.getStyleClass().add("small-button");
            remove.setOnAction(event -> { images.remove(asset); refreshImages(); });
            VBox card = new VBox(8, preview, remove);
            card.getStyleClass().add("image-card");
            card.setPadding(new Insets(10));
            gallery.getChildren().add(card);
        }
    }

    private void showValidation(String message) {
        validation.setText(message);
        validation.setVisible(!message.isEmpty());
        validation.setManaged(!message.isEmpty());
        if (!message.isEmpty()) scroll.setVvalue(0);
    }

    static ImageView thumbnail(ImageAsset asset, double width, double height) {
        Image image = new Image(new ByteArrayInputStream(asset.bytes()), width, height, true, true);
        ImageView view = new ImageView(image);
        view.setFitWidth(width);
        view.setFitHeight(height);
        view.setPreserveRatio(true);
        return view;
    }
}
