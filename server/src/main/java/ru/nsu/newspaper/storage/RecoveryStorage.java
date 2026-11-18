package ru.nsu.newspaper.storage;

import ru.nsu.newspaper.model.Issue;
import ru.nsu.newspaper.protocol.Protocol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;

public final class RecoveryStorage {
    public record Restored(Issue issue, String currentFile) {}
    private final IssueStorage storage = new IssueStorage();

    public void save(Issue issue, Path path, String currentFile) throws IOException {
        var metadata = Protocol.JSON.createObjectNode();
        metadata.put("currentFile", currentFile);
        metadata.put("savedAt", java.time.Instant.now().toString());
        storage.saveRecovery(issue, path, Protocol.JSON.writeValueAsBytes(metadata));
    }
    public Restored restore(Path path) throws IOException {
        Issue issue = storage.open(path);
        try (var zip = new ZipFile(path.toFile())) {
            var entry = zip.getEntry("recovery.json");
            if (entry == null) return new Restored(issue, null);
            try (var input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(4097);
                if (bytes.length > 4096) throw new IOException("Invalid recovery metadata");
                return new Restored(issue, Protocol.JSON.readTree(bytes).path("currentFile").asText(null));
            }
        }
    }
}
