package ru.nsu.newspaper.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nsu.newspaper.client.*;
import ru.nsu.newspaper.model.*;
import ru.nsu.newspaper.protocol.Role;
import ru.nsu.newspaper.storage.IssueStorage;
import static ru.nsu.newspaper.protocol.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

class CollaborationTest {
    @TempDir Path directory;
    private static final Details DETAILS = new Details(7, "2026-11-18", PageFormat.A4, "Campus newspaper");
    private IssueClient client(HttpIssueServer server, Role role) throws IOException {
        IssueClient client = new IssueClient("http://127.0.0.1:" + server.port(), role);
        client.connect(); return client;
    }
    private static ImageAsset image() throws IOException {
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", buffer);
        return ImageAsset.fromBytes(buffer.toByteArray());
    }
    private static ServerException refusal(int code, org.junit.jupiter.api.function.Executable action) {
        ServerException exception = assertThrows(ServerException.class, action);
        assertEquals(code, exception.status(), exception.getMessage()); return exception;
    }

    @Test void threeRolesShareArticlesImagesAndServerFiles() throws Exception {
        try (var server = new HttpIssueServer(directory, "127.0.0.1", 0);
             var editor = client(server, Role.EDITOR); var journalist = client(server, Role.JOURNALIST);
             var designer = client(server, Role.LAYOUT_DESIGNER)) {
            editor.create(DETAILS); journalist.poll(); designer.poll();
            Article article = Article.create("Новости кампуса", "Богдан", "Текст статьи", List.of(image()));
            journalist.add(article);
            editor.poll(); designer.poll();
            assertEquals(journalist.issue(), editor.issue());
            assertEquals(journalist.issue(), designer.issue());
            assertArrayEquals(article.images().getFirst().bytes(), designer.issue().articles().getFirst().images().getFirst().bytes());
            editor.save("campus.issue", false);
            assertEquals(editor.issue(), new IssueStorage().open(directory.resolve("issues/campus.issue")));
            assertEquals(List.of("campus.issue"), editor.files());
            editor.create(new Details(8, "2026-11-19", PageFormat.A3, "Next issue"));
            editor.open("campus.issue"); journalist.poll(); designer.poll();
            assertEquals(editor.issue(), designer.issue());
            assertEquals(article.id(), journalist.issue().articles().getFirst().id());
        }
    }

    @Test void serverChecksPermissionsEvenWhenClientBypassesUi() throws Exception {
        try (var server = new HttpIssueServer(directory, "127.0.0.1", 0);
             var editor = client(server, Role.EDITOR); var journalist = client(server, Role.JOURNALIST);
             var designer = client(server, Role.LAYOUT_DESIGNER)) {
            refusal(403, () -> journalist.create(DETAILS));
            editor.create(DETAILS); journalist.poll(); designer.poll();
            Article article = Article.create("Title", "", "", List.of());
            refusal(403, () -> editor.add(article));
            refusal(403, () -> designer.add(article));
            refusal(403, () -> journalist.save("forbidden.issue", false));
            refusal(403, () -> journalist.open("forbidden.issue"));
            refusal(403, journalist::files);
            journalist.add(article); editor.poll();
            refusal(403, () -> editor.lock(article.id()));
            assertFalse(Files.exists(directory.resolve("issues/forbidden.issue")));
        }
    }

    @Test void lockRefusesSecondClientAndPreventsEditingWithoutOwnership() throws Exception {
        try (var server = new HttpIssueServer(directory, "127.0.0.1", 0);
             var editor = client(server, Role.EDITOR); var journalist = client(server, Role.JOURNALIST);
             var designer = client(server, Role.LAYOUT_DESIGNER)) {
            editor.create(DETAILS); journalist.poll();
            Article article = Article.create("Original", "", "", List.of()); journalist.add(article); designer.poll();
            var update = journalist.change(Kind.UPDATE_ARTICLE, article.id(), journalist.snapshot().version(article.id()),
                    ArticleData.from(article.withContent("Updated", "", "", List.of())));
            refusal(409, () -> journalist.send(update));
            journalist.lock(article.id()); designer.poll();
            assertEquals(Role.JOURNALIST, designer.snapshot().locks().getFirst().role());
            refusal(409, () -> designer.lock(article.id()));
            refusal(403, () -> designer.unlock(article.id()));
            journalist.send(update); journalist.unlock(article.id());
            designer.lock(article.id());
            assertEquals("Updated", designer.issue().articles().getFirst().title());
            designer.disconnect(); journalist.poll();
            assertTrue(journalist.snapshot().locks().isEmpty());
        }
    }

    @Test void independentChangesDoNotOverwriteEachOtherAndStaleArticleIsRejected() throws Exception {
        try (var server = new HttpIssueServer(directory, "127.0.0.1", 0);
             var editor = client(server, Role.EDITOR); var journalist = client(server, Role.JOURNALIST)) {
            editor.create(DETAILS); journalist.poll();
            Article article = Article.create("Original", "", "", List.of()); journalist.add(article); editor.poll();
            journalist.lock(article.id());
            var stale = journalist.change(Kind.UPDATE_ARTICLE, article.id(), journalist.snapshot().version(article.id()), ArticleData.from(article));
            var update = journalist.change(Kind.UPDATE_ARTICLE, article.id(), journalist.snapshot().version(article.id()),
                    ArticleData.from(article.withContent("Revised article", "", "", List.of())));
            long detailsVersion = editor.snapshot().version(editor.issue().id());
            try (var tasks = Executors.newFixedThreadPool(2)) {
                var one = tasks.submit(() -> { editor.details(new Details(9, "2026-11-20", PageFormat.A3, "New title"), detailsVersion); return null; });
                var two = tasks.submit(() -> { journalist.send(update); return null; });
                one.get(); two.get();
            }
            editor.poll();
            assertEquals(9, editor.issue().issueNumber());
            assertEquals("Revised article", editor.issue().articles().getFirst().title());
            refusal(409, () -> journalist.send(stale));
            journalist.poll();
            long revision = journalist.snapshot().revision();
            journalist.send(update);
            assertEquals(revision, journalist.snapshot().revision(), "Retrying the same request must not apply it twice");
        }
    }

    @Test void autosaveRunsWithinFiveSecondsAndRestartReconnectsToRecoveredIssue() throws Exception {
        var server = new HttpIssueServer(directory, "127.0.0.1", 0);
        int port = server.port();
        try (var editor = client(server, Role.EDITOR); var journalist = client(server, Role.JOURNALIST)) {
            editor.create(DETAILS); journalist.poll();
            Article article = Article.create("Recover me", "", "Saved by the server", List.of(image()));
            journalist.add(article);
            Path recovery = directory.resolve("recovery.issue");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean recovered = false;
            while (System.nanoTime() < deadline) {
                if (Files.exists(recovery)) {
                    Issue value = new IssueStorage().open(recovery);
                    if (value.articles().size() == 1) { recovered = true; break; }
                }
                Thread.sleep(25);
            }
            assertTrue(recovered, "Every accepted edit must reach recovery within five seconds");
            UUID oldEpoch = journalist.snapshot().epoch();
            var stale = journalist.change(Kind.UPDATE_DETAILS, journalist.issue().id(), 0, DETAILS);
            server.close(); server = null;
            assertThrows(IOException.class, journalist::poll);
            assertEquals("Recover me", journalist.issue().articles().getFirst().title(), "Last known state is retained offline");
            try (var restarted = new HttpIssueServer(directory, "127.0.0.1", port)) {
                journalist.poll();
                assertNotEquals(oldEpoch, journalist.snapshot().epoch());
                assertEquals(article, journalist.issue().articles().getFirst());
                refusal(409, () -> journalist.send(stale));
            }
        } finally { if (server != null) server.close(); }
    }

    @Test void malformedImagesAndUnsafeFileNamesAreRejectedWithoutChangingIssue() throws Exception {
        try (var server = new HttpIssueServer(directory, "127.0.0.1", 0); var editor = client(server, Role.EDITOR)) {
            editor.create(DETAILS);
            refusal(422, () -> editor.save("../outside.issue", true));
            long revision = editor.snapshot().revision();
            var invalid = editor.change(Kind.UPDATE_DETAILS, editor.issue().id(), editor.snapshot().version(editor.issue().id()),
                    new Details(-5, "2026-11-18", PageFormat.A4, "Invalid"));
            refusal(422, () -> editor.send(invalid));
            editor.refresh(); assertEquals(revision, editor.snapshot().revision());
            var http = java.net.http.HttpClient.newHttpClient();
            try {
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/images/" + "a".repeat(64)))
                        .header(SESSION_HEADER, editor.sessionId().toString())
                        .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3})).build();
                assertEquals(422, http.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            } finally { http.close(); }
        }
    }

    @Test void secondServerCannotUseSameDataDirectory() throws Exception {
        try (var server = new IssueServer(directory)) {
            assertThrows(IOException.class, () -> new IssueServer(directory));
        }
    }

    @Test void expiredLockCanBeTakenByAnotherSession() throws Exception {
        class TestClock extends Clock {
            Instant now = Instant.parse("2026-11-18T10:00:00Z");
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        }
        TestClock clock = new TestClock();
        try (var server = new IssueServer(directory, clock)) {
            var editor = server.connect(Role.EDITOR);
            var journalist = server.connect(Role.JOURNALIST);
            var designer = server.connect(Role.LAYOUT_DESIGNER);
            var snapshot = server.applyChange(editor.id(), new Change(UUID.randomUUID(), editor.snapshot().epoch(), null, null, 0,
                    Kind.CREATE_ISSUE, JSON.valueToTree(DETAILS)));
            Article article = Article.create("Lock expiry", "", "", List.of());
            server.applyChange(journalist.id(), new Change(UUID.randomUUID(), snapshot.epoch(), snapshot.issue().id(), article.id(), 0,
                    Kind.ADD_ARTICLE, JSON.valueToTree(ArticleData.from(article))));
            server.lock(journalist.id(), article.id(), snapshot.epoch());
            clock.now = clock.now.plusSeconds(121);
            var result = server.lock(designer.id(), article.id(), snapshot.epoch());
            assertEquals(designer.id(), result.locks().getFirst().sessionId());
        }
    }
}
