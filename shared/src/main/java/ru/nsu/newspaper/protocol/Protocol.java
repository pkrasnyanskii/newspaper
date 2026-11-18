package ru.nsu.newspaper.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.nsu.newspaper.model.*;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

public final class Protocol {
    public static final String SESSION_HEADER = "X-Newspaper-Session";
    public static final int MAX_JSON_BYTES = 20 * 1024 * 1024;
    public static final long MAX_IMAGE_BYTES = 128L * 1024 * 1024;
    public static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(MAX_JSON_BYTES).build()).build());

    private Protocol() {}
    public enum Kind { CREATE_ISSUE, UPDATE_DETAILS, ADD_ARTICLE, UPDATE_ARTICLE, DELETE_ARTICLE }
    public record Connect(Role role) {}
    public record Session(UUID id, Role role, Snapshot snapshot) {}
    public record Details(int issueNumber, String publicationDate, PageFormat pageFormat, String title) {
        public Issue create() { return Issue.create(issueNumber, LocalDate.parse(publicationDate), pageFormat, title); }
        public Issue apply(Issue issue) {
            return issue.withDetails(issueNumber, LocalDate.parse(publicationDate), pageFormat, title);
        }
        public static Details from(Issue issue) {
            return new Details(issue.issueNumber(), issue.publicationDate().toString(), issue.pageFormat(), issue.cover().title());
        }
    }
    public record ArticleData(UUID id, String title, String author, String text, List<String> images) {
        public static ArticleData from(Article article) {
            return new ArticleData(article.id(), article.title(), article.author(), article.text(),
                    article.images().stream().map(Protocol::hash).toList());
        }
        public Article toArticle(Function<String, ImageAsset> resolve) {
            return new Article(id, title, author, text, images.stream().map(resolve).toList());
        }
    }
    public record IssueData(UUID id, Details details, List<ArticleData> articles) {
        public static IssueData from(Issue issue) {
            return issue == null ? null : new IssueData(issue.id(), Details.from(issue),
                    issue.articles().stream().map(ArticleData::from).toList());
        }
        public Issue toIssue(Function<String, ImageAsset> resolve) {
            return new Issue(id, details.issueNumber(), LocalDate.parse(details.publicationDate()), details.pageFormat(),
                    new Cover(details.title()), articles.stream().map(a -> a.toArticle(resolve)).toList());
        }
    }
    public record EditLock(UUID articleId, UUID sessionId, Role role, long expiresAt) {}
    public record Snapshot(UUID epoch, long revision, IssueData issue, Map<String, Long> versions,
                           List<EditLock> locks, String currentFile, boolean unsaved, String recoveryError) {
        public long version(UUID id) { return versions.getOrDefault(id.toString(), 0L); }
    }
    public record Change(UUID requestId, UUID epoch, UUID issueId, UUID objectId, long version, Kind kind, JsonNode data) {}
    public record AppliedChange(long revision, Kind kind, UUID objectId) {}
    public record Changes(UUID epoch, long revision, List<AppliedChange> changes, Snapshot snapshot,
                          List<EditLock> locks, String recoveryError) {}
    public record FileRequest(UUID epoch, long revision, String name, boolean overwrite) {}
    public record LockRequest(UUID epoch) {}
    public record Error(String message) {}

    public static String hash(ImageAsset image) { return image.archivePath().substring(7, 71); }
}
