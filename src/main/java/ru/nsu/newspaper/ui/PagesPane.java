package ru.nsu.newspaper.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import ru.nsu.newspaper.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class PagesPane extends ScrollPane {
    private final Window owner;
    private final Consumer<Issue> changed;
    private final ComboBox<Choice<Page>> pages = new ComboBox<>();
    private final ComboBox<Choice<Advertisement>> ads = new ComboBox<>();
    private final ComboBox<Choice<PageTemplate>> templates = new ComboBox<>();
    private final ListView<Choice<Page.Placement>> blocks = new ListView<>();
    private final Label pageInfo = new Label();
    private final Label empty = new Label();
    private final Button addPage = new Button("Add page");
    private final VBox pageControls;
    private final PagePreview preview = new PagePreview();
    private Issue issue;
    private boolean refreshing;

    public PagesPane(Window owner, Consumer<Issue> changed) {
        this.owner = owner;
        this.changed = changed;
        addPage.setOnAction(event -> {
            changed.accept(issue.addPage());
            pages.getSelectionModel().selectLast();
        });
        Button removePage = button("Remove page", () -> {
            if (confirm("Remove the selected page and its placements?")) changed.accept(issue.removePage(page().id()));
        });
        Button layout = button("Columns and margins...", this::editLayout);
        Button saveTemplate = button("Save as template...", this::saveTemplate);
        Button applyTemplate = button("Apply template", () ->
                apply(() -> issue.applyTemplate(page().id(), templates.getValue().value().id())));
        applyTemplate.disableProperty().bind(templates.valueProperty().isNull());
        templates.setPromptText("Choose a template");
        Button article = button("Place article...", this::placeArticle);
        Button image = button("Place image...", () -> editBlock(null, null));
        Button addAd = button("New advertisement...", () -> editAdvertisement(null));
        Button editAd = button("Edit advertisement...", () -> editAdvertisement(ads.getValue().value()));
        Button removeAd = button("Delete advertisement", () -> {
            if (confirm("Delete the selected advertisement?")) {
                changed.accept(issue.removeAdvertisement(ads.getValue().value().id()));
            }
        });
        editAd.disableProperty().bind(ads.valueProperty().isNull());
        removeAd.disableProperty().bind(ads.valueProperty().isNull());
        ads.setPromptText("Choose an advertisement");
        Button editBlock = button("Edit placement...", () -> {
            Page.Placement selected = blocks.getSelectionModel().getSelectedItem().value();
            editBlock(selected.elementId(), selected);
        });
        Button unplace = button("Remove block from page", () ->
                changed.accept(issue.unplace(blocks.getSelectionModel().getSelectedItem().value().elementId())));
        for (Button button : List.of(editBlock, unplace)) {
            button.disableProperty().bind(blocks.getSelectionModel().selectedItemProperty().isNull());
        }
        blocks.setPrefHeight(124);
        blocks.setPlaceholder(new Label("No blocks placed"));
        pages.setPromptText("Choose a page");
        pages.valueProperty().addListener((observable, old, value) -> {
            if (!refreshing) showPage();
        });
        pageInfo.setWrapText(true);
        pageControls = new VBox(10, pageInfo, row(layout, removePage), row(templates, applyTemplate, saveTemplate),
                row(article, image, addAd), row(ads, editAd, removeAd), blocks, row(editBlock, unplace));
        pageControls.setMinWidth(0);
        preview.setMinWidth(0);
        GridPane workspace = new GridPane();
        workspace.setHgap(20);
        workspace.setVgap(18);
        workspace.widthProperty().addListener((observable, old, width) ->
                arrange(workspace, width.doubleValue() >= 760));
        arrange(workspace, true);
        workspace.visibleProperty().bind(pages.valueProperty().isNotNull());
        workspace.managedProperty().bind(workspace.visibleProperty());
        empty.visibleProperty().bind(workspace.visibleProperty().not());
        empty.managedProperty().bind(empty.visibleProperty());
        article.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> issue == null || issue.articles().isEmpty(), pages.getItems()));
        VBox content = new VBox(14, row(pages, addPage), empty, workspace);
        content.setPadding(new Insets(24));
        content.getStyleClass().add("issue-card");
        setContent(content);
        setFitToWidth(true);
        getStyleClass().add("workspace");
        setIssue(null);
    }

    public void setIssue(Issue updated) {
        UUID selectedPage = page() == null ? null : page().id();
        UUID selectedAd = ads.getValue() == null ? null : ads.getValue().value().id();
        UUID selectedTemplate = templates.getValue() == null ? null : templates.getValue().value().id();
        issue = updated;
        refreshing = true;
        try {
            addPage.setDisable(issue == null);
            pages.getItems().setAll(pageChoices());
            templates.getItems().setAll(issue == null ? List.of() : issue.templates().stream()
                    .map(template -> new Choice<>(template.name(), template)).toList());
            ads.getItems().setAll(issue == null ? List.of() : issue.advertisements().stream()
                    .map(ad -> new Choice<>(adName(ad), ad)).toList());
            templates.setValue(templates.getItems().stream().filter(choice -> choice.value().id().equals(selectedTemplate))
                    .findFirst().orElse(null));
            ads.setValue(ads.getItems().stream().filter(choice -> choice.value().id().equals(selectedAd))
                    .findFirst().orElse(null));
            pages.setValue(pages.getItems().stream().filter(choice -> choice.value().id().equals(selectedPage)).findFirst()
                    .orElseGet(() -> pages.getItems().isEmpty() ? null : pages.getItems().getFirst()));
        } finally {
            refreshing = false;
        }
        showPage();
    }

    private void arrange(GridPane workspace, boolean wide) {
        int columns = wide ? 2 : 1;
        if (workspace.getColumnConstraints().size() == columns) return;
        workspace.getChildren().clear();
        workspace.getColumnConstraints().clear();
        for (int i = 0; i < columns; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setMinWidth(0);
            column.setPercentWidth(wide ? (i == 0 ? 52 : 48) : 100);
            workspace.getColumnConstraints().add(column);
        }
        workspace.add(pageControls, 0, 0);
        workspace.add(preview, wide ? 1 : 0, wide ? 0 : 1);
    }

    private List<Choice<Page>> pageChoices() {
        var choices = new ArrayList<Choice<Page>>();
        if (issue != null) {
            for (int i = 0; i < issue.pages().size(); i++) choices.add(new Choice<>("Page " + (i + 1), issue.pages().get(i)));
        }
        return choices;
    }

    private Page page() {
        return pages.getValue() == null ? null : pages.getValue().value();
    }

    private void showPage() {
        Page page = page();
        if (page == null) {
            empty.setText(issue == null ? "Create or open an issue." : "Add a page to start laying out the issue.");
            blocks.getItems().clear();
            preview.show(null, -1);
            return;
        }
        Page.Box bounds = page.printableArea(issue.pageFormat());
        pageInfo.setText(issue.pageHeader(page) + " · " + page.layout().columns() + " column(s) · "
                + bounds.width() + " × " + bounds.height() + " mm printable area");
        blocks.getItems().setAll(page.placements().stream().map(item ->
                new Choice<>(blockName(item) + " · " + item.box().x() + ", " + item.box().y() + " mm", item)).toList());
        preview.show(issue, issue.pages().indexOf(page));
    }

    private String blockName(Page.Placement item) {
        if (item.image() != null) return "Standalone image";
        return issue.articles().stream().filter(article -> article.id().equals(item.elementId())).findFirst()
                .map(Article::title).orElseGet(() -> issue.advertisements().stream()
                        .filter(ad -> ad.id().equals(item.elementId())).findFirst().map(PagesPane::adName).orElseThrow());
    }

    private static String adName(Advertisement ad) {
        String summary = ad.text().lines().findFirst().orElse("");
        if (summary.isBlank()) return "Advertisement · Image";
        int end = summary.offsetByCodePoints(0, Math.min(44, summary.codePointCount(0, summary.length())));
        return "Advertisement · " + summary.substring(0, end) + (end < summary.length() ? "…" : "");
    }

    private void editLayout() {
        Page.Layout current = page().layout();
        TextField columns = new TextField(Integer.toString(current.columns()));
        TextField top = number(current.margins().top());
        TextField right = number(current.margins().right());
        TextField bottom = number(current.margins().bottom());
        TextField left = number(current.margins().left());
        TextField header = new TextField(current.headerFooterText());
        edit("Page layout", error -> issue.updateLayout(page().id(),
                new Page.Layout(Integer.parseInt(columns.getText().strip()),
                        new Page.Margins(value(top), value(right), value(bottom), value(left)), header.getText())),
                field("Text columns", columns), field("Top margin (mm)", top), field("Right margin (mm)", right),
                field("Bottom margin (mm)", bottom), field("Left margin (mm)", left), field("Header text", header));
    }

    private void saveTemplate() {
        TextField name = new TextField();
        edit("Save page template", error -> issue.saveTemplate(page().id(), name.getText()), field("Name", name));
    }

    private void placeArticle() {
        ComboBox<Choice<Article>> articles = new ComboBox<>();
        articles.getItems().setAll(issue.articles().stream().map(a -> new Choice<>(a.title(), a)).toList());
        articles.getSelectionModel().selectFirst();
        BoxFields box = new BoxFields(defaultBox(page()));
        edit("Place article", error -> {
            Page.Box placement = box.in(page(), error);
            return placement == null ? null : issue.place(page().id(), articles.getValue().value().id(), placement);
        }, field("Article", articles), box);
    }

    private void editBlock(UUID id, Page.Placement existing) {
        boolean standalone = existing == null || existing.image() != null;
        ImageField image = new ImageField("Choose image...", () -> {});
        if (standalone && existing != null) image.setImage(existing.image());
        BoxFields box = new BoxFields(existing == null ? defaultBox(page()) : existing.box());
        edit(standalone ? "Place standalone image" : "Edit placement", error -> {
            Page.Box placement = box.in(page(), error);
            if (placement == null) return null;
            if (!standalone) return issue.place(page().id(), id, placement);
            if (image.image() == null) throw new IllegalArgumentException("Choose an image.");
            return issue.putImage(page().id(), id == null ? UUID.randomUUID() : id, placement, image.image());
        }, standalone ? new VBox(8, image, box) : box);
    }

    private void editAdvertisement(Advertisement existing) {
        ComboBox<Choice<Page>> target = new ComboBox<>();
        target.getItems().setAll(pageChoices());
        target.getItems().stream().filter(choice -> choice.value().id().equals(page().id())).findFirst()
                .ifPresent(target.getSelectionModel()::select);
        Page.Box current = defaultBox(page());
        if (existing != null) {
            for (Choice<Page> choice : target.getItems()) {
                for (Page.Placement item : choice.value().placements()) {
                    if (item.elementId().equals(existing.id())) {
                        current = item.box();
                        target.getSelectionModel().select(choice);
                    }
                }
            }
        }
        TextArea text = new TextArea(existing == null ? "" : existing.text());
        text.setPrefRowCount(3);
        text.setWrapText(true);
        ImageField image = new ImageField("Choose advertisement image...", () -> {});
        if (existing != null) image.setImage(existing.image());
        BoxFields box = new BoxFields(current);
        edit(existing == null ? "New advertisement" : "Edit advertisement", error -> {
            Advertisement ad = new Advertisement(existing == null ? UUID.randomUUID() : existing.id(),
                    text.getText(), image.image());
            Page selected = target.getValue().value();
            Page.Box placement = box.in(selected, error);
            return placement == null ? null : issue.putAdvertisement(ad, selected.id(), placement);
        }, field("Advertisement text", text), image, field("Page", target), box);
    }

    private Page.Box defaultBox(Page page) {
        Page.Box printable = page.printableArea(issue.pageFormat());
        return new Page.Box(printable.x(), printable.y(), Math.min(90, printable.width()), Math.min(60, printable.height()));
    }

    private void edit(String title, Function<Label, Issue> save, Node... fields) {
        Dialog<Issue> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setResizable(true);
        var pane = dialog.getDialogPane();
        pane.setPrefWidth(510);
        Label error = validation();
        VBox content = new VBox(10, fields);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(530, Math.max(265, 90 + 80 * fields.length)));
        content.setPadding(new Insets(12));
        VBox wrapper = new VBox(8, scroll, error);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        pane.setContent(wrapper);
        pane.getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);
        pane.lookupButton(ButtonType.APPLY).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                error.setText("");
                Issue result = save.apply(error);
                if (result == null) {
                    event.consume();
                } else {
                    dialog.setResultConverter(button -> button == ButtonType.APPLY ? result : null);
                }
            } catch (IllegalArgumentException exception) {
                error.setText(exception instanceof NumberFormatException
                        ? "Enter valid numbers in the layout fields." : exception.getMessage());
                event.consume();
            }
        });
        dialog.showAndWait().ifPresent(changed);
    }

    private void apply(Supplier<Issue> change) {
        try {
            changed.accept(change.get());
        } catch (IllegalArgumentException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage());
            alert.initOwner(owner);
            alert.showAndWait();
        }
    }

    private boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private static Button button(String title, Runnable click) {
        Button button = new Button(title);
        button.setOnAction(event -> click.run());
        return button;
    }

    private static VBox field(String title, Control control) {
        Label label = new Label(title);
        label.setLabelFor(control);
        return new VBox(4, label, control);
    }

    private static FlowPane row(Node... nodes) {
        return new FlowPane(8, 8, nodes);
    }

    private static TextField number(double value) {
        return new TextField(Double.toString(value));
    }

    private static double value(TextField field) {
        return Double.parseDouble(field.getText().strip());
    }

    private static Label validation() {
        Label error = new Label();
        error.setWrapText(true);
        error.getStyleClass().add("validation-error");
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        return error;
    }

    private final class BoxFields extends VBox {
        private final TextField x;
        private final TextField y;
        private final TextField width;
        private final TextField height;

        BoxFields(Page.Box box) {
            super(6);
            x = number(box.x());
            y = number(box.y());
            width = number(box.width());
            height = number(box.height());
            for (TextField field : List.of(x, y, width, height)) field.setPrefColumnCount(6);
            getChildren().addAll(row(field("X (mm)", x), field("Y (mm)", y),
                    field("Width (mm)", width), field("Height (mm)", height)));
        }

        Page.Box in(Page page, Label error) {
            Page.Box box = new Page.Box(value(x), value(y), value(width), value(height));
            return switch (page.checkPlacement(issue.pageFormat(), box)) {
                case FITS -> box;
                case OUTSIDE_PRINTABLE_AREA -> {
                    error.setText(page.printableBoundsMessage(issue.pageFormat()));
                    yield null;
                }
            };
        }
    }

    private record Choice<T>(String label, T value) {
        @Override public String toString() { return label; }
    }
}
