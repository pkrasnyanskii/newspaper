package ru.nsu.newspaper.server;

import io.javalin.Javalin;
import io.javalin.http.Context;
import ru.nsu.newspaper.protocol.Protocol;
import static ru.nsu.newspaper.protocol.Protocol.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

public final class HttpIssueServer implements AutoCloseable {
    private final IssueServer server;
    private final Javalin http;

    public HttpIssueServer(Path folder, String host, int port) throws IOException {
        server = new IssueServer(folder);
        http = Javalin.create(config -> {
            config.http.maxRequestSize = MAX_JSON_BYTES + 1024L;
            config.routes.exception(ApiException.class, (e, ctx) -> failure(ctx, e.status, e.getMessage()));
            config.routes.exception(IllegalArgumentException.class, (e, ctx) -> failure(ctx, 422, e.getMessage()));
            config.routes.exception(java.time.DateTimeException.class, (e, ctx) -> failure(ctx, 422, "Invalid publication date"));
            config.routes.exception(NullPointerException.class, (e, ctx) -> failure(ctx, 422, "Missing required data"));
            config.routes.exception(IOException.class, (e, ctx) -> failure(ctx, 422, e.getMessage()));
            config.routes.post("/sessions", ctx -> reply(ctx, server.connect(body(ctx, Connect.class).role())));
            config.routes.delete("/sessions/current", ctx -> { server.disconnect(session(ctx)); ctx.status(204); });
            config.routes.get("/issue", ctx -> reply(ctx, server.getIssue(session(ctx))));
            config.routes.get("/changes", ctx -> reply(ctx, server.getChanges(session(ctx),
                    UUID.fromString(ctx.queryParam("epoch")), Long.parseLong(ctx.queryParam("since")))));
            config.routes.post("/changes", ctx -> reply(ctx, server.applyChange(session(ctx), body(ctx, Change.class))));
            config.routes.post("/locks/{id}", ctx -> reply(ctx, server.lock(session(ctx),
                    UUID.fromString(ctx.pathParam("id")), body(ctx, LockRequest.class).epoch())));
            config.routes.delete("/locks/{id}", ctx -> reply(ctx, server.unlock(session(ctx), UUID.fromString(ctx.pathParam("id")))));
            config.routes.put("/images/{hash}", ctx -> {
                server.upload(session(ctx), ctx.pathParam("hash"), ctx.bodyAsBytes()); ctx.status(204);
            });
            config.routes.head("/images/{hash}", ctx -> ctx.status(server.hasImage(session(ctx), ctx.pathParam("hash")) ? 200 : 404));
            config.routes.get("/images/{hash}", ctx -> ctx.contentType("application/octet-stream")
                    .result(server.image(session(ctx), ctx.pathParam("hash"))));
            config.routes.get("/files", ctx -> reply(ctx, server.files(session(ctx))));
            config.routes.post("/files/save", ctx -> reply(ctx, server.save(session(ctx), body(ctx, FileRequest.class))));
            config.routes.post("/files/open", ctx -> reply(ctx, server.open(session(ctx), body(ctx, FileRequest.class))));
        });
        try { http.start(host, port); }
        catch (RuntimeException e) { http.stop(); server.close(); throw e; }
    }
    public int port() { return http.port(); }
    private static UUID session(Context ctx) {
        try { return UUID.fromString(ctx.header(SESSION_HEADER)); }
        catch (RuntimeException e) { throw new ApiException(401, "Connect to the server first"); }
    }
    private static <T> T body(Context ctx, Class<T> type) throws IOException {
        return JSON.readValue(ctx.bodyAsBytes(), type);
    }
    private static void reply(Context ctx, Object value) throws IOException {
        ctx.contentType("application/json").result(JSON.writeValueAsBytes(value));
    }
    private static void failure(Context ctx, int status, String message) {
        try { ctx.status(status); reply(ctx, new Protocol.Error(message == null ? "Request failed" : message)); }
        catch (IOException e) { ctx.status(500); }
    }
    @Override public void close() throws IOException { http.stop(); server.close(); }
}
