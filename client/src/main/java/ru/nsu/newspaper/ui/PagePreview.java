package ru.nsu.newspaper.ui;

import javafx.concurrent.Task;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import ru.nsu.newspaper.export.PdfExporter;
import ru.nsu.newspaper.model.Issue;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executors;

final class PagePreview extends VBox {
    private final ImageView image = new ImageView();
    private final Label status = new Label();
    private final PdfExporter exporter = new PdfExporter();
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "page-preview");
        thread.setDaemon(true);
        return thread;
    });
    private Task<byte[]> pending;

    PagePreview() {
        super(8);
        image.setPreserveRatio(true);
        image.fitWidthProperty().bind(widthProperty().subtract(6).map(width -> Math.clamp(width.doubleValue(), 0, 650)));
        status.managedProperty().bind(status.textProperty().isNotEmpty());
        getChildren().addAll(status, image);
    }

    void show(Issue issue, int pageIndex) {
        if (pending != null) pending.cancel();
        image.setImage(null);
        if (pageIndex < 0) {
            status.setText("");
            return;
        }
        status.setText("Rendering page preview…");
        Task<byte[]> task = new Task<>() {
            @Override protected byte[] call() throws Exception {
                var output = new ByteArrayOutputStream();
                ImageIO.write(exporter.previewPage(issue, pageIndex), "png", output);
                return output.toByteArray();
            }
        };
        task.setOnSucceeded(event -> {
            image.setImage(new Image(new ByteArrayInputStream(task.getValue())));
            status.setText("");
        });
        task.setOnFailed(event -> status.setText("Page preview unavailable: " + task.getException().getMessage()));
        pending = task;
        worker.execute(task);
    }

    void close() {
        if (pending != null) pending.cancel();
        worker.shutdownNow();
    }
}
