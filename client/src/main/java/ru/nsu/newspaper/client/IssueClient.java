package ru.nsu.newspaper.client;

import ru.nsu.newspaper.model.*;
import ru.nsu.newspaper.protocol.Role;
import ru.nsu.newspaper.protocol.Protocol;
import static ru.nsu.newspaper.protocol.Protocol.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Blocking transport; the desktop application runs it on its network executor. */
public final class IssueClient implements AutoCloseable {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI base;
    private final Role role;
    private volatile UUID sessionId;
    private volatile Snapshot snapshot;
    private volatile Issue issue;
    private final Map<String, ImageAsset> images = new HashMap<>();

    public IssueClient(String address, Role role) {
        URI value = URI.create(address.strip());
        if (!Set.of("http", "https").contains(value.getScheme()) || value.getHost() == null
                || value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null
                || !(value.getPath().isEmpty() || value.getPath().equals("/"))) {
            throw new IllegalArgumentException("Enter a server address such as http://localhost:7070");
        }
        base = value.resolve("/"); this.role = Objects.requireNonNull(role);
    }
    public synchronized void connect() throws IOException {
        Session result = json("POST", "sessions", new Connect(role), Session.class);
        sessionId = result.id();
        accept(result.snapshot());
    }
    public void poll() throws IOException {
        if (sessionId == null || snapshot == null) { connect(); return; }
        try {
            Changes result = json("GET", "changes?since=" + snapshot.revision() + "&epoch=" + snapshot.epoch(), null, Changes.class);
            if (!snapshot.epoch().equals(result.epoch())) { connect(); return; }
            if (result.snapshot() != null) accept(result.snapshot());
            else synchronized (this) {
                if (snapshot.revision() == result.revision() && snapshot.epoch().equals(result.epoch())) {
                    snapshot = new Snapshot(snapshot.epoch(), snapshot.revision(), snapshot.issue(), snapshot.versions(),
                            result.locks(), snapshot.currentFile(), snapshot.unsaved(), result.recoveryError());
                }
            }
        } catch (ServerException e) {
            if (e.status() != 401) throw e;
            connect();
        }
    }
    private synchronized void accept(Snapshot value) throws IOException {
        if (snapshot != null && snapshot.epoch().equals(value.epoch()) && value.revision() < snapshot.revision()) return;
        Map<String, ImageAsset> loaded = new HashMap<>();
        if (value.issue() != null) {
            for (ArticleData article : value.issue().articles()) {
                for (String hash : article.images()) {
                    ImageAsset image = images.get(hash);
                    if (image == null) image = ImageAsset.fromBytes(request("GET", "images/" + hash, null, false));
                    if (!hash(image).equals(hash)) throw new IOException("Image does not match its content hash");
                    loaded.put(hash, image);
                }
            }
        }
        Issue updated = value.issue() == null ? null : value.issue().toIssue(loaded::get);
        images.clear(); images.putAll(loaded);
        snapshot = value; issue = updated;
    }
    public Snapshot snapshot() { return snapshot; }
    public Issue issue() { return issue; }
    public UUID sessionId() { return sessionId; }
    public Role role() { return role; }

    public Change change(Kind kind, UUID objectId, long version, Object data) {
        return new Change(UUID.randomUUID(), snapshot.epoch(), issue == null ? null : issue.id(), objectId,
                version, kind, JSON.valueToTree(data));
    }
    public void send(Change change) throws IOException { accept(json("POST", "changes", change, Snapshot.class)); }
    public void create(Details details) throws IOException { send(change(Kind.CREATE_ISSUE, null, snapshot.revision(), details)); }
    public void details(Details details, long version) throws IOException { send(change(Kind.UPDATE_DETAILS, issue.id(), version, details)); }
    public void add(Article article) throws IOException {
        upload(article); send(change(Kind.ADD_ARTICLE, article.id(), 0, ArticleData.from(article)));
    }
    public void update(Article article, Change prepared) throws IOException { upload(article); send(prepared); }
    public void upload(Article article) throws IOException {
        for (ImageAsset image : article.images()) {
            String hash = hash(image);
            try { request("HEAD", "images/" + hash, null, false); }
            catch (ServerException e) {
                if (e.status() != 404) throw e;
                request("PUT", "images/" + hash, image.bytes(), false);
            }
        }
    }
    public void lock(UUID id) throws IOException { accept(json("POST", "locks/" + id, new LockRequest(snapshot.epoch()), Snapshot.class)); }
    public void unlock(UUID id) throws IOException { accept(json("DELETE", "locks/" + id, null, Snapshot.class)); }
    public List<String> files() throws IOException { return List.of(json("GET", "files", null, String[].class)); }
    public void save(String name, boolean overwrite) throws IOException {
        save(new FileRequest(snapshot.epoch(), snapshot.revision(), name, overwrite));
    }
    public void save(FileRequest request) throws IOException { accept(json("POST", "files/save", request, Snapshot.class)); }
    public void open(String name) throws IOException {
        open(new FileRequest(snapshot.epoch(), snapshot.revision(), name, false));
    }
    public void open(FileRequest request) throws IOException { accept(json("POST", "files/open", request, Snapshot.class)); }
    public void refresh() throws IOException { accept(json("GET", "issue", null, Snapshot.class)); }

    private <T> T json(String method, String path, Object body, Class<T> type) throws IOException {
        return JSON.readValue(request(method, path, body == null ? null : JSON.writeValueAsBytes(body), true), type);
    }
    private byte[] request(String method, String path, byte[] data, boolean json) throws IOException {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(path.startsWith("images/") ? 300 : 3));
        if (sessionId != null) builder.header(SESSION_HEADER, sessionId.toString());
        if (data != null) builder.header("Content-Type", json ? "application/json" : "application/octet-stream");
        builder.method(method, data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(data));
        try {
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                String message = "Server returned " + response.statusCode();
                try { message = JSON.readValue(response.body(), Protocol.Error.class).message(); }
                catch (IOException ignored) { }
                throw new ServerException(response.statusCode(), message);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Request interrupted", e);
        }
    }
    public void disconnect() throws IOException {
        if (sessionId != null) { request("DELETE", "sessions/current", null, true); sessionId = null; }
    }
    @Override public void close() { http.shutdownNow(); }
}
