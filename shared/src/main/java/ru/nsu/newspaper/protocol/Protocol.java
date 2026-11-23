package ru.nsu.newspaper.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.nsu.newspaper.model.*;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

public final class Protocol {
    public static final String SESSION_HEADER = "X-Newspaper-Session";
    public static final int MAX_JSON_BYTES = 20 * 1024 * 1024;
    public static final long MAX_IMAGE_BYTES = 128L * 1024 * 1024;
    public static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(MAX_JSON_BYTES).build()).build());

    private Protocol() {}
    public enum Kind {
        CREATE_ISSUE, UPDATE_DETAILS, ADD_ARTICLE, UPDATE_ARTICLE, DELETE_ARTICLE, STYLE_ARTICLE,
        ADD_PAGE, REMOVE_PAGE, UPDATE_LAYOUT, SAVE_TEMPLATE, APPLY_TEMPLATE, PLACE_BLOCK, UNPLACE_BLOCK,
        PUT_ADVERTISEMENT, DELETE_ADVERTISEMENT
    }
    public record Connect(Role role) {}
    public record Session(UUID id, Role role, Snapshot snapshot) {}
    public record Details(int issueNumber, String publicationDate, PageFormat pageFormat, String title, String coverImage) {
        public Details(int issueNumber, String publicationDate, PageFormat pageFormat, String title) {
            this(issueNumber, publicationDate, pageFormat, title, null);
        }
        public Issue create() { return Issue.create(issueNumber, LocalDate.parse(publicationDate), pageFormat, title); }
        public Issue apply(Issue issue) {
            return issue.withDetails(issueNumber, LocalDate.parse(publicationDate), pageFormat, title);
        }
        public static Details from(Issue issue) {
            return new Details(issue.issueNumber(), issue.publicationDate().toString(), issue.pageFormat(),
                    issue.cover().title(), optionalHash(issue.cover().image()));
        }
    }
    public record ArticleData(UUID id, String title, String author, String text, List<String> images,
                              List<StyledRange> styledRanges) {
        public ArticleData(UUID id, String title, String author, String text, List<String> images) {
            this(id, title, author, text, images, List.of());
        }

        public static ArticleData from(Article article) {
            return new ArticleData(article.id(), article.title(), article.author(), article.text(),
                    article.images().stream().map(Protocol::hash).toList(), article.styledRanges());
        }
        public Article toArticle(Function<String, ImageAsset> resolve) {
            return new Article(id, title, author, text, images.stream().map(resolve).toList(),
                    styledRanges == null ? List.of() : styledRanges);
        }
    }

    public record Placement(UUID pageId, Page.Box box, String image) {}
    public record AdvertisementData(String text, String image, UUID pageId, Page.Box box) {}
    public record AdSnapshot(UUID id, String text, String image) {}
    public record BlockSnapshot(UUID id, Page.Box box, String image) {}
    public record PageSnapshot(UUID id, Page.Layout layout, UUID templateId, List<BlockSnapshot> blocks) {}

    public record IssueData(UUID id, Details details, List<ArticleData> articles, List<AdSnapshot> advertisements,
                            List<PageSnapshot> pages, List<PageTemplate> templates) {
        public static IssueData from(Issue issue) {
            if (issue == null) return null;
            return new IssueData(issue.id(), Details.from(issue), issue.articles().stream().map(ArticleData::from).toList(),
                    issue.advertisements().stream().map(ad -> new AdSnapshot(ad.id(), ad.text(), optionalHash(ad.image()))).toList(),
                    issue.pages().stream().map(page -> new PageSnapshot(page.id(), page.layout(), page.templateId(),
                            page.placements().stream().map(block -> new BlockSnapshot(block.elementId(), block.box(),
                                    optionalHash(block.image()))).toList())).toList(), issue.templates());
        }
        public Issue toIssue(Function<String, ImageAsset> resolve) {
            Function<String, ImageAsset> optional = hash -> hash == null ? null : Objects.requireNonNull(resolve.apply(hash));
            return new Issue(id, details.issueNumber(), LocalDate.parse(details.publicationDate()), details.pageFormat(),
                    new Cover(details.title(), optional.apply(details.coverImage())),
                    articles.stream().map(a -> a.toArticle(resolve)).toList(),
                    advertisements.stream().map(ad -> new Advertisement(ad.id(), ad.text(), optional.apply(ad.image()))).toList(),
                    pages.stream().map(page -> new Page(page.id(), page.layout(), page.templateId(),
                            page.blocks().stream().map(block -> new Page.Placement(block.id(), block.box(),
                                    optional.apply(block.image()))).toList())).toList(), templates);
        }

        public Stream<String> imageHashes() {
            return Stream.of(Stream.of(details.coverImage()), articles.stream().flatMap(article -> article.images().stream()),
                            advertisements.stream().map(AdSnapshot::image),
                            pages.stream().flatMap(page -> page.blocks().stream()).map(BlockSnapshot::image))
                    .flatMap(stream -> stream).filter(Objects::nonNull).distinct();
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
    public static String optionalHash(ImageAsset image) { return image == null ? null : hash(image); }
}
