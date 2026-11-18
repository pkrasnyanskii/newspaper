package ru.nsu.newspaper.server;

import ru.nsu.newspaper.model.*;
import ru.nsu.newspaper.protocol.Role;
import ru.nsu.newspaper.storage.*;
import static ru.nsu.newspaper.protocol.Protocol.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class IssueServer implements AutoCloseable {
    private static final long LOCK_TTL = Duration.ofMinutes(2).toMillis();
    private static final long SESSION_TTL = Duration.ofMinutes(30).toMillis();
    private record Connected(Role role, long lastSeen) {}
    private record Receipt(UUID sessionId, Change change) {}
    private final UUID epoch = UUID.randomUUID();
    private final Clock clock;
    private final Path folder;
    private final Path recoveryFile;
    private final IssueStorage storage = new IssueStorage();
    private final RecoveryStorage recovery = new RecoveryStorage();
    private final Map<UUID, Connected> sessions = new HashMap<>();
    private final Map<UUID, EditLock> locks = new HashMap<>();
    private final Map<String, ImageAsset> images = new HashMap<>();
    private final Map<String, Long> versions = new HashMap<>();
    private final LinkedHashMap<UUID, Receipt> receipts = new LinkedHashMap<>();
    private final Deque<AppliedChange> history = new ArrayDeque<>();
    private final ScheduledExecutorService autosave = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "issue-recovery"); t.setDaemon(true); return t;
    });
    private final FileChannel ownerChannel;
    private final FileLock ownerLock;
    private Issue issue;
    private long revision;
    private boolean unsaved;
    private boolean recoveryPending;
    private String recoveryError;
    private String currentFile;

    public IssueServer(Path dataDirectory) throws IOException { this(dataDirectory, Clock.systemUTC()); }

    IssueServer(Path dataDirectory, Clock clock) throws IOException {
        this.clock = clock;
        Files.createDirectories(dataDirectory);
        folder = dataDirectory.resolve("issues");
        Files.createDirectories(folder);
        recoveryFile = dataDirectory.resolve("recovery.issue");
        ownerChannel = FileChannel.open(dataDirectory.resolve("server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = ownerChannel.tryLock();
            if (lock == null) throw new IOException("Another server is using this data directory");
        } catch (Exception e) {
            ownerChannel.close();
            throw new IOException("Cannot lock the server data directory", e);
        }
        ownerLock = lock;
        try {
            if (Files.exists(recoveryFile)) {
                var restored = recovery.restore(recoveryFile);
                issue = restored.issue();
                currentFile = restored.currentFile();
                if (currentFile != null) file(currentFile);
                unsaved = true;
                indexIssue();
            }
        } catch (Exception e) {
            ownerLock.release(); ownerChannel.close();
            throw new IOException("Cannot restore recovery.issue; the original file has been preserved", e);
        }
        autosave.scheduleWithFixedDelay(this::flushRecovery, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized Session connect(Role role) {
        Objects.requireNonNull(role, "Choose a role");
        expire();
        UUID id = UUID.randomUUID();
        sessions.put(id, new Connected(role, clock.millis()));
        return new Session(id, role, snapshot());
    }

    private Connected session(UUID id) {
        expire();
        Connected connection = sessions.get(id);
        if (connection == null) throw new ApiException(401, "Session expired. Reconnect to the server.");
        connection = new Connected(connection.role(), clock.millis());
        sessions.put(id, connection);
        locks.replaceAll((articleId, lock) -> lock.sessionId().equals(id)
                ? new EditLock(articleId, id, lock.role(), clock.millis() + LOCK_TTL) : lock);
        return connection;
    }

    private void expire() {
        long now = clock.millis();
        locks.values().removeIf(lock -> lock.expiresAt() <= now);
        sessions.values().removeIf(s -> now - s.lastSeen() >= SESSION_TTL);
    }

    private Snapshot snapshot() {
        return new Snapshot(epoch, revision, IssueData.from(issue), Map.copyOf(versions), List.copyOf(locks.values()),
                currentFile, unsaved, recoveryError);
    }

    public synchronized Snapshot getIssue(UUID sessionId) { session(sessionId); return snapshot(); }

    public synchronized Changes getChanges(UUID sessionId, UUID clientEpoch, long since) {
        session(sessionId);
        var changes = history.stream().filter(c -> c.revision() > since).toList();
        return new Changes(epoch, revision, changes,
                !epoch.equals(clientEpoch) || since != revision ? snapshot() : null,
                List.copyOf(locks.values()), recoveryError);
    }

    public synchronized Snapshot applyChange(UUID sessionId, Change change) throws IOException {
        var connection = session(sessionId);
        Objects.requireNonNull(change.requestId(), "Missing request id");
        Objects.requireNonNull(change.kind(), "Missing change kind");
        checkEpoch(change.epoch());
        if (!connection.role().canPerform(change.kind())) throw new ApiException(403, "This action is not available to " + connection.role());
        Receipt receipt = receipts.get(change.requestId());
        if (receipt != null) {
            if (!receipt.equals(new Receipt(sessionId, change))) throw new ApiException(409, "Request id was already used");
            return snapshot();
        }
        if (!Objects.equals(change.issueId(), issue == null ? null : issue.id())) throw new ApiException(409, "The open issue has changed. Reload it before editing.");
        Issue updated;
        UUID objectId = change.objectId();
        switch (change.kind()) {
            case CREATE_ISSUE -> {
                if (change.version() != revision) conflict();
                updated = JSON.treeToValue(change.data(), Details.class).create();
                objectId = updated.id();
            }
            case UPDATE_DETAILS -> {
                requireIssue();
                if (!issue.id().equals(objectId)) throw new ApiException(422, "Invalid issue id");
                checkVersion(objectId, change.version());
                updated = JSON.treeToValue(change.data(), Details.class).apply(issue);
            }
            case ADD_ARTICLE -> {
                requireIssue();
                if (objectId == null || objectId.equals(issue.id()) || versions.containsKey(objectId.toString()) || change.version() != 0) conflict();
                Article article = article(change);
                updated = issue.addArticle(article);
            }
            case UPDATE_ARTICLE -> {
                requireIssue(); checkVersion(objectId, change.version()); requireLock(sessionId, objectId);
                updated = issue.updateArticle(article(change));
            }
            case DELETE_ARTICLE -> {
                requireIssue(); checkVersion(objectId, change.version()); requireLock(sessionId, objectId);
                updated = issue.removeArticle(objectId);
            }
            default -> throw new ApiException(422, "Unsupported change");
        }
        storage.validate(updated);
        issue = updated;
        if (change.kind() == Kind.CREATE_ISSUE) {
            currentFile = null; locks.clear(); versions.clear(); images.clear();
        }
        revision++;
        versions.merge(objectId.toString(), 1L, Long::sum);
        if (change.kind() == Kind.DELETE_ARTICLE) locks.remove(objectId);
        unsaved = true;
        recoveryPending = true;
        history.addLast(new AppliedChange(revision, change.kind(), objectId));
        while (history.size() > 128) history.removeFirst();
        receipts.put(change.requestId(), new Receipt(sessionId, change));
        while (receipts.size() > 128) receipts.remove(receipts.keySet().iterator().next());
        return snapshot();
    }

    private Article article(Change change) throws IOException {
        ArticleData data = JSON.treeToValue(change.data(), ArticleData.class);
        if (!Objects.equals(data.id(), change.objectId())) throw new ApiException(422, "Article id does not match the change");
        return data.toArticle(hash -> {
            var image = images.get(hash);
            if (image == null) throw new ApiException(422, "Upload the article images before applying the article");
            return image;
        });
    }

    private void checkEpoch(UUID value) { if (!epoch.equals(value)) throw new ApiException(409, "The server restarted. Reload before editing."); }
    private void checkVersion(UUID id, long value) {
        if (id == null || !Objects.equals(versions.get(id.toString()), value)) conflict();
    }
    private void conflict() { throw new ApiException(409, "This object changed while you were editing. Reopen it to use the latest version."); }
    private void requireIssue() { if (issue == null) throw new ApiException(409, "No issue is open"); }
    private void requireLock(UUID sessionId, UUID articleId) {
        EditLock lock = locks.get(articleId);
        if (lock == null || !lock.sessionId().equals(sessionId)) throw new ApiException(409, "An edit lock is required. Reopen the article.");
    }

    public synchronized Snapshot lock(UUID sessionId, UUID articleId, UUID clientEpoch) {
        var connection = session(sessionId); checkEpoch(clientEpoch); requireIssue();
        if (!connection.role().canLockArticle()) throw new ApiException(403, "This role cannot edit articles");
        if (issue.articles().stream().noneMatch(a -> a.id().equals(articleId))) throw new ApiException(404, "Article no longer exists");
        EditLock existing = locks.get(articleId);
        if (existing != null && !existing.sessionId().equals(sessionId)) throw new ApiException(409, "Being edited by " + existing.role());
        locks.put(articleId, new EditLock(articleId, sessionId, connection.role(), clock.millis() + LOCK_TTL));
        return snapshot();
    }

    public synchronized Snapshot unlock(UUID sessionId, UUID articleId) {
        session(sessionId);
        EditLock lock = locks.get(articleId);
        if (lock != null && !lock.sessionId().equals(sessionId)) throw new ApiException(403, "Only the lock owner can release this article");
        locks.remove(articleId);
        return snapshot();
    }

    public synchronized void disconnect(UUID sessionId) {
        sessions.remove(sessionId);
        locks.values().removeIf(lock -> lock.sessionId().equals(sessionId));
    }

    public synchronized void upload(UUID sessionId, String hash, byte[] bytes) throws IOException {
        session(sessionId);
        ImageAsset image = ImageAsset.fromBytes(bytes);
        if (!hash(image).equals(hash)) throw new ApiException(422, "Image hash does not match its bytes");
        if (images.containsKey(hash)) return;
        long total = images.values().stream().mapToLong(i -> i.bytes().length).sum();
        if (total + bytes.length > MAX_IMAGE_BYTES) throw new ApiException(422, "Server image cache exceeds 128 MB; save and reopen the issue to remove unused uploads");
        images.put(hash, image);
    }

    public synchronized boolean hasImage(UUID sessionId, String hash) {
        session(sessionId); return images.containsKey(hash);
    }

    public synchronized byte[] image(UUID sessionId, String hash) {
        session(sessionId);
        ImageAsset image = images.get(hash);
        if (image == null) throw new ApiException(404, "Image not found");
        return image.bytes();
    }

    private void editor(UUID sessionId) {
        if (session(sessionId).role() != Role.EDITOR) throw new ApiException(403, "Only the Editor can manage issue files");
    }
    public synchronized List<String> files(UUID sessionId) throws IOException {
        editor(sessionId);
        try (var stream = Files.list(folder)) {
            return stream.filter(p -> Files.isRegularFile(p) && !Files.isSymbolicLink(p) && p.toString().endsWith(".issue"))
                    .map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
    private Path file(String name) {
        if (name == null || name.isBlank() || name.length() > 180 || name.contains("/") || name.contains("\\")
                || !name.endsWith(".issue") || name.startsWith(".") || name.chars().anyMatch(c -> c < 32)) {
            throw new ApiException(422, "Use a file name ending in .issue, without folders");
        }
        Path result = folder.resolve(name);
        if (Files.isSymbolicLink(result)) throw new ApiException(422, "Symbolic links are not issue files");
        return result;
    }
    private void checkFileRequest(FileRequest request) {
        checkEpoch(request.epoch());
        if (request.revision() != revision) throw new ApiException(409, "The issue changed. Review the latest state and try again.");
    }
    public synchronized Snapshot save(UUID sessionId, FileRequest request) throws IOException {
        editor(sessionId); checkFileRequest(request); requireIssue();
        Path target = file(request.name());
        if (Files.exists(target) && !request.overwrite()) throw new ApiException(409, "This file exists. Confirm replacement to save it.");
        storage.save(issue, target);
        currentFile = request.name(); unsaved = false;
        revision++;
        recoveryPending = true;
        flushRecovery();
        return snapshot();
    }
    public synchronized Snapshot open(UUID sessionId, FileRequest request) throws IOException {
        editor(sessionId); checkFileRequest(request);
        Issue loaded = storage.open(file(request.name()));
        issue = loaded; currentFile = request.name(); unsaved = false; locks.clear();
        revision++; indexIssue();
        recoveryPending = true;
        return snapshot();
    }
    private void indexIssue() {
        versions.clear(); images.clear();
        if (issue == null) return;
        versions.put(issue.id().toString(), revision);
        for (Article article : issue.articles()) {
            versions.put(article.id().toString(), revision);
            for (ImageAsset image : article.images()) images.put(hash(image), image);
        }
    }
    synchronized void flushRecovery() {
        if (!recoveryPending || issue == null) return;
        try {
            recovery.save(issue, recoveryFile, currentFile);
            recoveryPending = false; recoveryError = null;
        } catch (IOException e) {
            recoveryError = "Recovery save failed: " + e.getMessage();
            System.err.println(recoveryError);
        }
    }
    @Override public synchronized void close() throws IOException {
        autosave.shutdownNow();
        flushRecovery();
        ownerLock.release(); ownerChannel.close();
    }
}
