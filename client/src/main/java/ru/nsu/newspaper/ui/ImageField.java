package ru.nsu.newspaper.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import ru.nsu.newspaper.model.ImageAsset;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class ImageField extends VBox {
    private final HBox preview = new HBox();
    private final Label error = new Label();
    private final Button remove = new Button("Remove image");
    private final Runnable changed;
    private ImageAsset image;

    public ImageField(String caption, Runnable changed) {
        super(8);
        this.changed = changed;
        Button choose = new Button(caption);
        choose.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(caption);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "PNG and JPEG images", "*.png", "*.jpg", "*.jpeg", "*.PNG", "*.JPG", "*.JPEG"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
            File file = chooser.showOpenDialog(getScene().getWindow());
            if (file == null) return;
            try (var input = Files.newInputStream(file.toPath())) {
                setImage(ImageAsset.fromBytes(input.readNBytes(ImageAsset.MAX_BYTES + 1)));
                changed.run();
            } catch (IOException e) {
                error.setText(e.getMessage());
            }
        });
        remove.setOnAction(event -> {
            setImage(null);
            this.changed.run();
        });
        error.setWrapText(true);
        error.getStyleClass().add("validation-error");
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        getChildren().addAll(new HBox(8, choose, remove), error, preview);
        setImage(null);
    }

    public ImageAsset image() {
        return image;
    }

    public void setImage(ImageAsset image) {
        this.image = image;
        error.setText("");
        preview.getChildren().clear();
        if (image != null) preview.getChildren().add(ArticleEditor.thumbnail(image, 240, 150));
        remove.setDisable(image == null);
    }
}
