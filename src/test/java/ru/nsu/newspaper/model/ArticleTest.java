package ru.nsu.newspaper.model;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ArticleTest {
    @Test
    void requiresTitleButAllowsDraftBodyAndAuthor() {
        assertThrows(IllegalArgumentException.class, () -> Article.create(" \t\n", "", "draft", List.of()));
        Article article = Article.create("  Первая статья  ", "", "", List.of());
        assertEquals("Первая статья", article.title());
        assertEquals("", article.text());
    }

    @Test
    void editingKeepsIdentityAndExactBodyText() {
        Article original = Article.create("News", "A. Author", "old", List.of());
        String text = "  Первый абзац\n\nВторой абзац — «цитата» 🌍\n";
        Article edited = original.withContent("New title", "Богдан", text, List.of());
        assertEquals(original.id(), edited.id());
        assertEquals(text, edited.text());
        assertEquals("old", original.text());
    }

    @Test
    void issueAddsEditsAndRemovesArticlesWithoutChangingOthers() {
        Issue original = Issue.create(1, LocalDate.now(), PageFormat.A4, "News");
        Article first = Article.create("First", "", "First body", List.of());
        Article second = Article.create("Second", "", "Second body", List.of());
        Issue withArticles = original.addArticle(first).addArticle(second);
        Article edited = first.withContent("Revised", "Editor", "Edited body", List.of());
        Issue updated = withArticles.updateArticle(edited).withDetails(2, LocalDate.now(), PageFormat.A3, "Renamed");
        assertEquals(List.of(edited, second), updated.articles());
        assertEquals(List.of(second), updated.removeArticle(first.id()).articles());
        assertEquals(List.of(first, second), withArticles.articles());
        assertTrue(original.articles().isEmpty());
    }

    @Test
    void rejectsDuplicateOrUnknownArticleIdentity() {
        Article article = Article.create("News", "", "", List.of());
        Issue issue = Issue.create(1, LocalDate.now(), PageFormat.A4, "News").addArticle(article);
        assertThrows(IllegalArgumentException.class, () -> issue.addArticle(article));
        assertThrows(IllegalArgumentException.class, () -> issue.updateArticle(Article.create("Other", "", "", List.of())));
        assertThrows(IllegalArgumentException.class, () -> issue.removeArticle(UUID.randomUUID()));
    }

    @Test
    void collectionsCannotMutateTheIssueBehindTheEditor() {
        var supplied = new ArrayList<ImageAsset>();
        Article article = Article.create("News", "", "", supplied);
        assertThrows(UnsupportedOperationException.class, () -> article.images().add(null));
        Issue issue = Issue.create(1, LocalDate.now(), PageFormat.A4, "News").addArticle(article);
        assertThrows(UnsupportedOperationException.class, () -> issue.articles().clear());
    }
}
