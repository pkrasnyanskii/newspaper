package ru.nsu.newspaper.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EditorialWorkflowTest {
    private final Issue initial = Issue.create(42, LocalDate.of(2026, 11, 11), PageFormat.A4, "News");
    private final Article article = Article.create("First headline", "Author", "0123456789", List.of());
    private final Page.Box box = new Page.Box(20, 30, 80, 50);

    @Test
    void contentsTracksPlacementRenameRemovalAndRenumbering() {
        Issue issue = initial.addArticle(article).addPage().addPage();
        var first = issue.pages().getFirst().id();
        var second = issue.pages().getLast().id();
        assertNull(issue.contents().getFirst().pageNumber());
        issue = issue.place(second, article.id(), box);
        assertEquals(2, issue.contents().getFirst().pageNumber());
        issue = issue.place(first, article.id(), box);
        assertTrue(issue.pages().getLast().placements().isEmpty());
        issue = issue.place(second, article.id(), box).removePage(first);
        assertEquals(1, issue.contents().getFirst().pageNumber());
        issue = issue.updateArticle(article.withContent("New headline", "", article.text(), List.of()));
        assertEquals("New headline", issue.contents().getFirst().title());
        issue = issue.removeArticle(article.id());
        assertTrue(issue.contents().isEmpty());
        assertTrue(issue.pages().getFirst().placements().isEmpty());
    }

    @Test
    void validatesPrintableBoundsWhenPlacingAndApplyingTemplates() {
        Issue issue = initial.addArticle(article).addPage();
        UUID page = issue.pages().getFirst().id();
        assertEquals(Page.PlacementStatus.FITS, issue.pages().getFirst().checkPlacement(PageFormat.A4, box));
        assertEquals(Page.PlacementStatus.OUTSIDE_PRINTABLE_AREA, issue.pages().getFirst()
                .checkPlacement(PageFormat.A4, new Page.Box(0, 0, 100, 50)));
        assertThrows(IllegalArgumentException.class, () ->
                issue.place(page, article.id(), new Page.Box(0, 0, 100, 50)));
        Issue placed = issue.place(page, article.id(), box);
        var layout = new Page.Layout(3, new Page.Margins(20, 20, 20, 30), "City news");
        assertThrows(IllegalArgumentException.class, () -> placed.updateLayout(page, layout));
        Issue configured = issue.updateLayout(page, layout).saveTemplate(page, "City").addPage();
        var template = configured.templates().getFirst();
        Issue applied = configured.applyTemplate(configured.pages().getLast().id(), template.id());
        assertEquals(layout, applied.pages().getLast().layout());
        assertEquals(template.id(), applied.pages().getLast().templateId());
    }

    @Test
    void validatesAdvertisementsAndUpdatesPlacementAsPartOfTheSameEdit() {
        assertThrows(IllegalArgumentException.class, () -> new Advertisement(UUID.randomUUID(), " ", null));
        Issue issue = initial.addPage();
        UUID page = issue.pages().getFirst().id();
        var ad = new Advertisement(UUID.randomUUID(), "Subscribe", null);
        assertThrows(IllegalArgumentException.class, () ->
                issue.putAdvertisement(ad, page, new Page.Box(0, 0, 100, 50)));
        Issue added = issue.putAdvertisement(ad, page, box);
        Issue updated = added.putAdvertisement(new Advertisement(ad.id(), "New offer", null), page, box);
        assertEquals("New offer", updated.advertisements().getFirst().text());
        assertEquals(1, updated.pages().getFirst().placements().size());
        Issue deleted = updated.removeAdvertisement(ad.id());
        assertTrue(deleted.advertisements().isEmpty());
        assertTrue(deleted.pages().getFirst().placements().isEmpty());
    }

    @Test
    void appliesStylesToSelectionAndTrimsRangesWhenTextGetsShorter() {
        var bold = new TextStyle(16, TextStyle.Weight.BOLD);
        var normal = new TextStyle(12, TextStyle.Weight.NORMAL);
        Article styled = article.style(0, 10, bold).style(2, 4, normal);
        assertEquals(List.of(new StyledRange(0, 2, bold), new StyledRange(2, 4, normal),
                new StyledRange(4, 10, bold)), styled.styledRanges());
        assertEquals(List.of(new StyledRange(0, 2, bold), new StyledRange(2, 3, normal)),
                styled.withContent("Title", "", "012", List.of()).styledRanges());
        assertThrows(IllegalArgumentException.class, () -> new TextStyle(0, TextStyle.Weight.BOLD));
        assertThrows(IllegalArgumentException.class, () -> article.style(0, 11, bold));
    }
}
