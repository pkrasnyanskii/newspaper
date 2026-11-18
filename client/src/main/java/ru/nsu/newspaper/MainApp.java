package ru.nsu.newspaper;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import ru.nsu.newspaper.client.*;
import ru.nsu.newspaper.model.*;
import ru.nsu.newspaper.protocol.Role;
import ru.nsu.newspaper.ui.*;
import static ru.nsu.newspaper.protocol.Protocol.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class MainApp extends Application {
    private final ScheduledExecutorService network = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "issue-client"); t.setDaemon(true); return t;
    });
    private final TextField title = new TextField();
    private final TextField number = new TextField("1");
    private final DatePicker date = new DatePicker(LocalDate.now());
    private final ComboBox<PageFormat> format = new ComboBox<>();
    private final Label status = new Label();
    private final Label draftStatus = new Label();
    private final Button create = new Button("New issue");
    private final Button open = new Button("Open on server...");
    private final Button save = new Button("Save on server...");
    private final Button apply = new Button("Apply details");
    private final Button discard = new Button("Discard draft");
    private final Button disconnect = new Button("Disconnect");
    private volatile IssueClient client;
    private Stage stage;
    private ArticlesPane articles;
    private Tab articlesTab;
    private Snapshot shown;
    private Issue issue;
    private UUID sessionId;
    private boolean online;
    private boolean busy;
    private boolean loading;
    private boolean detailsDirty;
    private long detailsVersion;
    private UUID detailsIssue;
    private UUID detailsEpoch;
    private ArticleEditor editor;
    private UUID editingId;
    private UUID editingIssue;
    private UUID editingEpoch;
    private long editingVersion;
    private volatile boolean stopped;

    private record View(Snapshot snapshot, Issue issue, UUID sessionId) {}
    @FunctionalInterface private interface IO<T> { T run() throws Exception; }

    @Override public void start(Stage stage) {
        this.stage = stage;
        format.getItems().setAll(PageFormat.values()); format.setValue(PageFormat.A4);
        title.setId("issue-title"); number.setId("issue-number"); date.setId("issue-date"); format.setId("issue-format");
        ChangeListener<Object> edited = (o, old, value) -> {
            if (!loading && shown != null) {
                if (!detailsDirty) {
                    detailsVersion = issue == null ? 0 : shown.version(issue.id());
                    detailsIssue = issue == null ? null : issue.id();
                    detailsEpoch = shown.epoch();
                }
                detailsDirty = true; refreshAccess();
            }
        };
        title.textProperty().addListener(edited); number.textProperty().addListener(edited);
        date.valueProperty().addListener(edited); date.getEditor().textProperty().addListener(edited);
        format.valueProperty().addListener(edited);
        stage.setMinWidth(360); stage.setMinHeight(360);
        stage.setOnCloseRequest(event -> { if (!confirmDraft()) event.consume(); });
        connectionScreen();
        stage.show();
        network.scheduleWithFixedDelay(this::poll, 2, 2, TimeUnit.SECONDS);
    }

    private Map<String, String> parameters() {
        return getParameters() == null ? Map.of() : getParameters().getNamed();
    }

    private void connectionScreen() {
        stage.setTitle("Newspaper — Connect");
        TextField address = new TextField(parameters().getOrDefault("connect", "http://localhost:7070"));
        address.setId("server-address");
        ComboBox<Role> role = new ComboBox<>(); role.getItems().setAll(Role.values());
        try { role.setValue(Role.valueOf(parameters().getOrDefault("role", "EDITOR"))); }
        catch (IllegalArgumentException e) { role.setValue(Role.EDITOR); }
        role.setId("connection-role");
        Label message = new Label("Connect to the server to work on the shared issue."); message.setWrapText(true);
        Label heading = new Label("Newspaper"); heading.getStyleClass().add("app-heading");
        Button connect = new Button("Connect"); connect.setId("connect-button"); connect.setDefaultButton(true);
        connect.getStyleClass().add("primary-button");
        connect.setOnAction(event -> {
            IssueClient candidate;
            try { candidate = new IssueClient(address.getText(), role.getValue()); }
            catch (RuntimeException e) { message.setText(e.getMessage()); return; }
            connect.setDisable(true); message.setText("Connecting...");
            network.execute(() -> {
                try {
                    candidate.connect(); client = candidate;
                    View view = view();
                    Platform.runLater(() -> { if (!stopped) { online = true; workspace(); render(view); } });
                } catch (Exception e) {
                    candidate.close();
                    Platform.runLater(() -> { connect.setDisable(false); message.setText("Cannot connect: " + e.getMessage()); });
                }
            });
        });
        VBox card = new VBox(18, heading, message, field("Server address", address), field("Role", role), connect);
        card.setPadding(new Insets(24)); card.setMaxWidth(600); card.getStyleClass().add("issue-card");
        VBox box = new VBox(card); box.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(box); scroll.setFitToWidth(true);
        scene(scroll, 680, 470);
    }

    private void workspace() {
        Label heading = new Label("Newspaper · " + client.role()); heading.getStyleClass().add("app-heading"); heading.setWrapText(true);
        create.setId("new-issue"); open.setId("open-issue"); save.setId("save-issue"); apply.setId("apply-details");
        create.setOnAction(event -> createIssue()); open.setOnAction(event -> openIssue()); save.setOnAction(event -> saveIssue());
        apply.setOnAction(event -> applyDetails()); discard.setOnAction(event -> { detailsDirty = false; loadDetails(); refreshAccess(); });
        disconnect.setOnAction(event -> {
            if (!confirmDraft()) return;
            submit(() -> { try { client.disconnect(); } catch (java.io.IOException ignored) { } return null; }, ignored -> {
                client.close(); client = null; shown = null; issue = null; detailsDirty = false; articles = null;
                connectionScreen();
            }, this::error);
        });
        FlowPane actions = new FlowPane(10, 10, create, open, save, disconnect);
        for (Node node : actions.getChildren()) ((Button) node).setMinWidth(Region.USE_PREF_SIZE);
        VBox header = new VBox(12, heading, actions); header.setPadding(new Insets(16, 24, 16, 24));
        articles = new ArticlesPane(stage, new ArticlesPane.Actions() {
            public void create() { showEditor(null); }
            public void edit(Article article) { editArticle(article); }
            public void delete(Article article) { deleteArticle(article); }
        });
        articlesTab = new Tab("Articles", articles);
        TabPane tabs = new TabPane(new Tab("Issue details", issueDetails()), articlesTab);
        tabs.setId("workspace-tabs"); tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); tabs.setMinSize(0, 0);
        status.setId("issue-status"); status.setWrapText(true); status.setMaxWidth(Double.MAX_VALUE);
        status.setPadding(new Insets(12, 24, 12, 24)); status.getStyleClass().add("status-bar");
        scene(new BorderPane(tabs, header, null, status, null), 1040, 780);
        heading.visibleProperty().bind(stage.getScene().heightProperty().greaterThanOrEqualTo(440));
        heading.managedProperty().bind(heading.visibleProperty());
        stage.getScene().getAccelerators().put(KeyCombination.keyCombination("Shortcut+S"), () -> { if (!save.isDisabled()) saveIssue(); });
    }

    private void scene(javafx.scene.Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(Objects.requireNonNull(MainApp.class.getResource("app.css")).toExternalForm());
        stage.setScene(scene);
    }
    private ScrollPane issueDetails() {
        Label help = new Label(client.role() == Role.LAYOUT_DESIGNER
                ? "Shared issue preview. Text styling and page layout are not implemented yet."
                : "The Editor manages the issue details and server files. The Journalist manages articles and images.");
        help.setWrapText(true); help.getStyleClass().add("muted");
        GridPane metadata = new GridPane(); metadata.setHgap(16); metadata.setVgap(16); metadata.setMinWidth(0);
        Node[] fields = {field("Issue number", number), field("Issue date", date), field("Page format", format)};
        arrange(metadata, fields, 3);
        metadata.widthProperty().addListener((o, old, width) -> arrange(metadata, fields, width.doubleValue() < 560 ? 1 : 3));
        draftStatus.setWrapText(true); draftStatus.getStyleClass().add("muted");
        VBox card = new VBox(18, help, field("Publication title", title), metadata, new FlowPane(10, 10, apply, discard), draftStatus);
        card.setPadding(new Insets(24)); card.setMinWidth(0); card.getStyleClass().add("issue-card");
        VBox content = new VBox(card); content.setPadding(new Insets(24)); content.setMinWidth(0);
        ScrollPane scroll = new ScrollPane(content); scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("workspace"); return scroll;
    }
    private VBox field(String caption, Region control) {
        Label label = new Label(caption); label.setLabelFor(control); control.setMinWidth(0); control.setMaxWidth(Double.MAX_VALUE);
        VBox field = new VBox(7, label, control); field.setMinWidth(0); return field;
    }
    private void arrange(GridPane grid, Node[] fields, int count) {
        if (grid.getColumnConstraints().size() == count) return;
        grid.getChildren().clear(); grid.getColumnConstraints().clear();
        for (int i = 0; i < count; i++) {
            ColumnConstraints column = new ColumnConstraints(); column.setMinWidth(0); column.setPercentWidth(100.0 / count);
            grid.getColumnConstraints().add(column);
        }
        for (int i = 0; i < fields.length; i++) grid.add(fields[i], i % count, i / count);
    }

    private View view() { return view(client); }
    private View view(IssueClient active) {
        synchronized (active) { return new View(active.snapshot(), active.issue(), active.sessionId()); }
    }
    private void poll() {
        IssueClient active = client;
        if (active == null || stopped) return;
        try {
            active.poll(); View value = view(active);
            Platform.runLater(() -> { if (!stopped && client == active && articles != null) { online = true; render(value); } });
        } catch (Exception e) {
            Platform.runLater(() -> { if (client == active) { online = false; refreshAccess(); } });
        }
    }
    private <T> void submit(IO<T> operation, Consumer<T> success, Consumer<Exception> failure) {
        if (busy || client == null) return;
        busy = true; refreshAccess();
        network.execute(() -> {
            try {
                T result = operation.run(); View value = view();
                Platform.runLater(() -> {
                    if (stopped) return;
                    busy = false; online = true; render(value); success.accept(result); refreshAccess();
                });
            } catch (Exception e) {
                boolean reachable = e instanceof ServerException;
                if (reachable) { try { client.refresh(); } catch (Exception ignored) { reachable = false; } }
                View value = view(); boolean connected = reachable;
                Platform.runLater(() -> {
                    if (stopped) return;
                    busy = false; online = connected; render(value); failure.accept(e); refreshAccess();
                });
            }
        });
    }
    private void render(View value) {
        if (value.snapshot() == null || articles == null) return;
        if (shown != null && shown.epoch().equals(value.snapshot().epoch()) && value.snapshot().revision() < shown.revision()) return;
        boolean replaced = shown == null || !shown.epoch().equals(value.snapshot().epoch())
                || !Objects.equals(issue == null ? null : issue.id(), value.issue() == null ? null : value.issue().id());
        boolean contentChanged = shown == null || shown.revision() != value.snapshot().revision() || replaced;
        shown = value.snapshot(); issue = value.issue(); sessionId = value.sessionId();
        if (!detailsDirty) loadDetails();
        if (contentChanged) articles.setIssue(issue);
        articlesTab.setText("Articles (" + (issue == null ? 0 : issue.articles().size()) + ")");
        refreshAccess();
    }
    private void loadDetails() {
        loading = true;
        try {
            title.setText(issue == null ? "" : issue.cover().title());
            number.setText(issue == null ? "1" : Integer.toString(issue.issueNumber()));
            date.setValue(issue == null ? LocalDate.now() : issue.publicationDate());
            format.setValue(issue == null ? PageFormat.A4 : issue.pageFormat());
        } finally { loading = false; }
    }
    private void refreshAccess() {
        if (client == null || articles == null || shown == null) return;
        boolean editDetails = online && !busy && client.role() == Role.EDITOR;
        for (var control : new Control[]{title, number, date, format}) control.setDisable(!editDetails);
        create.setDisable(!editDetails); open.setDisable(!editDetails); save.setDisable(!editDetails || issue == null || detailsDirty);
        discard.setDisable(!detailsDirty || busy);
        disconnect.setDisable(busy);
        boolean conflict = detailsDirty && (!Objects.equals(detailsEpoch, shown.epoch())
                || !Objects.equals(detailsIssue, issue == null ? null : issue.id())
                || issue != null && detailsVersion != shown.version(issue.id()));
        apply.setDisable(!editDetails || issue == null || !detailsDirty || conflict);
        draftStatus.setText(conflict ? "Issue details changed on the server. Discard this draft to reload them."
                : detailsDirty ? "Details are a local draft. Apply them before saving the issue." : "");
        articles.setAccess(client.role(), online && !busy, sessionId, shown.locks());
        String connection = online ? (busy ? "Working..." : "Connected") : "Connection lost · Read-only · Reconnecting...";
        status.setText(connection + " · " + client.role() + " · " + (issue == null ? "No issue is open"
                : Objects.toString(shown.currentFile(), "Untitled issue") + (shown.unsaved() ? " · Changes pending file save" : " · Saved"))
                + (shown.recoveryError() == null ? "" : " · " + shown.recoveryError()));
        stage.setTitle("Newspaper — " + client.role() + (online ? "" : " — Offline"));
        if (editor != null) {
            boolean sameIssue = issue != null && Objects.equals(editingIssue, issue.id()) && editingEpoch.equals(shown.epoch());
            boolean ownsLock = editingId == null || shown.locks().stream().anyMatch(lock ->
                    lock.articleId().equals(editingId) && lock.sessionId().equals(sessionId)) && shown.version(editingId) == editingVersion;
            boolean allowed = online && !busy && sameIssue && ownsLock;
            editor.setPending(busy);
            editor.setWritable(allowed, allowed ? "" : !online ? "Connection lost. Your draft stays here; editing is paused."
                    : busy ? "Sending..." : "The issue or edit lock changed. Copy any text you need, then cancel and reopen the article. This draft has not been sent.");
        }
    }

    private Details details() {
        date.commitValue();
        if (date.getValue() == null) throw new IllegalArgumentException("Choose an issue date");
        Details value = new Details(Integer.parseInt(number.getText().strip()), date.getValue().toString(), format.getValue(), title.getText().strip());
        value.create(); return value;
    }
    private Change change(Kind kind, UUID objectId, long version, Object data) {
        return new Change(UUID.randomUUID(), shown.epoch(), issue == null ? null : issue.id(), objectId, version, kind, JSON.valueToTree(data));
    }
    private void createIssue() {
        try {
            Details value = details();
            if (issue != null && !confirm("Create a new shared issue?", "This replaces the issue for every connected user. Save the current issue first if you need it.")) return;
            Change command = change(Kind.CREATE_ISSUE, null, shown.revision(), value);
            submit(() -> { client.send(command); return null; }, result -> { detailsDirty = false; loadDetails(); }, this::error);
        } catch (RuntimeException e) { error(e); }
    }
    private void applyDetails() {
        try {
            Change command = change(Kind.UPDATE_DETAILS, issue.id(), detailsVersion, details());
            submit(() -> { client.send(command); return null; }, result -> { detailsDirty = false; loadDetails(); }, this::error);
        } catch (RuntimeException e) { error(e); }
    }
    private void saveIssue() {
        TextInputDialog dialog = new TextInputDialog(shown.currentFile() == null ? "issue-" + issue.issueNumber() + ".issue" : shown.currentFile());
        dialog.initOwner(stage); dialog.setTitle("Save on server"); dialog.setHeaderText("File name in the server's issues folder");
        dialog.showAndWait().ifPresent(name -> {
            String target = name.endsWith(".issue") ? name : name + ".issue";
            boolean overwrite = Objects.equals(target, shown.currentFile());
            FileRequest request = new FileRequest(shown.epoch(), shown.revision(), target, overwrite);
            submit(() -> { client.save(request); return null; }, result -> {}, e -> {
                if (e instanceof ServerException refusal && refusal.status() == 409 && e.getMessage().contains("file exists")
                        && confirm("Replace file?", "Replace " + target + " on the server?")) {
                    FileRequest replacement = new FileRequest(shown.epoch(), shown.revision(), target, true);
                    submit(() -> { client.save(replacement); return null; }, result -> {}, this::error);
                } else error(e);
            });
        });
    }
    private void openIssue() {
        if (!confirmDraft()) return;
        submit(() -> client.files(), files -> {
            if (files.isEmpty()) { error(new Exception("No .issue files in the server's issues folder yet.")); return; }
            ChoiceDialog<String> dialog = new ChoiceDialog<>(files.getFirst(), files);
            dialog.initOwner(stage); dialog.setTitle("Open on server"); dialog.setHeaderText("Choose the shared issue");
            dialog.showAndWait().ifPresent(name -> {
                if (issue != null && !confirm("Replace the shared issue?", "All connected users will switch to " + name + ". Save the current issue first if you need it.")) return;
                FileRequest request = new FileRequest(shown.epoch(), shown.revision(), name, false);
                submit(() -> { client.open(request); return null; }, result -> { detailsDirty = false; loadDetails(); }, this::error);
            });
        }, this::error);
    }
    private void editArticle(Article article) {
        submit(() -> { client.lock(article.id()); return null; }, result ->
                issue.articles().stream().filter(a -> a.id().equals(article.id())).findFirst().ifPresent(this::showEditor), this::error);
    }
    private void showEditor(Article original) {
        editingId = original == null ? null : original.id();
        editingIssue = issue.id(); editingEpoch = shown.epoch(); editingVersion = editingId == null ? 0 : shown.version(editingId);
        UUID lockedId = editingId;
        editor = new ArticleEditor(stage, original);
        ArticleEditor dialog = editor;
        dialog.onSubmit(article -> {
            Change command = new Change(UUID.randomUUID(), editingEpoch, editingIssue, article.id(), editingVersion,
                    original == null ? Kind.ADD_ARTICLE : Kind.UPDATE_ARTICLE, JSON.valueToTree(ArticleData.from(article)));
            submit(() -> { client.update(article, command); return null; }, result -> dialog.close(), e -> dialog.showFailure(e.getMessage()));
        });
        dialog.setOnHidden(event -> {
            editor = null; editingId = null;
            if (lockedId != null && client != null) {
                submit(() -> { client.unlock(lockedId); return null; }, result -> {}, failure -> {});
            }
        });
        refreshAccess(); dialog.showAndWait();
    }
    private void deleteArticle(Article article) {
        if (!confirm("Delete article?", "Delete “" + article.title() + "” from the shared issue?")) return;
        Change command = change(Kind.DELETE_ARTICLE, article.id(), shown.version(article.id()), null);
        submit(() -> {
            client.lock(article.id());
            try { client.send(command); }
            finally { try { client.unlock(article.id()); } catch (Exception ignored) { } }
            return null;
        }, result -> {}, this::error);
    }
    private boolean confirmDraft() {
        return !detailsDirty || confirm("Discard details draft?", "The issue details you typed have not been applied to the server.");
    }
    private boolean confirm(String heading, String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(stage); alert.setHeaderText(heading); return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
    private void error(Exception e) {
        Alert alert = new Alert(Alert.AlertType.ERROR, Objects.toString(e.getMessage(), "Operation failed"), ButtonType.OK);
        alert.initOwner(stage); alert.setHeaderText("Could not complete the action"); alert.showAndWait();
    }
    @Override public void stop() {
        stopped = true;
        network.execute(() -> {
            if (client != null) { try { client.disconnect(); } catch (Exception ignored) { } client.close(); }
        });
        network.shutdown();
    }
}
