package ru.nsu.newspaper.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Window;
import ru.nsu.newspaper.model.Article;
import ru.nsu.newspaper.model.TextStyle;
import ru.nsu.newspaper.model.StyledRange;

public final class ArticleStyles {
    private ArticleStyles() {}

    static TextFlow preview(Article article) {
        TextFlow flow = new TextFlow();
        int offset = 0;
        for (var range : article.styledRanges()) {
            append(flow, article.text(), offset, range.start(), TextStyle.DEFAULT);
            append(flow, article.text(), range.start(), range.end(), range.style());
            offset = range.end();
        }
        append(flow, article.text(), offset, article.text().length(), TextStyle.DEFAULT);
        return flow;
    }

    private static void append(TextFlow flow, String value, int start, int end, TextStyle style) {
        if (start == end) return;
        Text text = new Text(value.substring(start, end));
        text.setFont(Font.font(Font.getDefault().getFamily(),
                style.fontWeight() == TextStyle.Weight.BOLD ? FontWeight.BOLD : FontWeight.NORMAL,
                style.fontSize() * 96.0 / 72));
        flow.getChildren().add(text);
    }

    public static Dialog<StyledRange> editor(Window owner, Article article) {
        Dialog<StyledRange> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Style article text");
        dialog.setResizable(true);
        TextArea selection = new TextArea(article.text());
        selection.setEditable(false);
        selection.setWrapText(true);
        selection.setPrefRowCount(8);
        TextField size = new TextField("12");
        ComboBox<TextStyle.Weight> weight = new ComboBox<>();
        weight.getItems().setAll(TextStyle.Weight.values());
        weight.setValue(TextStyle.Weight.NORMAL);
        Label error = new Label();
        error.getStyleClass().add("validation-error");
        ButtonType apply = new ButtonType("Apply style", ButtonBar.ButtonData.OK_DONE);
        VBox content = new VBox(8, new Label("Select the text to style"), selection,
                new Label("Font size (6–96 pt)"), size, new Label("Font weight"), weight, error);
        content.setPadding(new Insets(16));
        content.setPrefWidth(560);
        var pane = dialog.getDialogPane();
        pane.setContent(content);
        pane.getButtonTypes().addAll(apply, ButtonType.CANCEL);
        pane.lookupButton(apply).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                var range = selection.getSelection();
                var change = new StyledRange(range.getStart(), range.getEnd(),
                        new TextStyle(Integer.parseInt(size.getText().strip()), weight.getValue()));
                article.style(change.start(), change.end(), change.style());
                dialog.setResultConverter(button -> button == apply ? change : null);
            } catch (NumberFormatException exception) {
                error.setText("Enter a whole font size from 6 to 96 pt.");
                event.consume();
            } catch (IllegalArgumentException exception) {
                error.setText(exception.getMessage());
                event.consume();
            }
        });
        return dialog;
    }
}
