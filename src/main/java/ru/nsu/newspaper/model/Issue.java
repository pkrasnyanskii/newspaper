package ru.nsu.newspaper.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Issue(UUID id, int issueNumber, LocalDate publicationDate, PageFormat pageFormat,
                    Cover cover, List<Article> articles, List<Advertisement> advertisements,
                    List<Page> pages, List<PageTemplate> templates) {
    public Issue(UUID id, int issueNumber, LocalDate publicationDate, PageFormat pageFormat,
                 Cover cover, List<Article> articles) {
        this(id, issueNumber, publicationDate, pageFormat, cover, articles, List.of(), List.of(), List.of());
    }

    public Issue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(publicationDate, "publicationDate");
        Objects.requireNonNull(pageFormat, "pageFormat");
        Objects.requireNonNull(cover, "cover");
        articles = List.copyOf(articles);
        advertisements = List.copyOf(advertisements);
        pages = List.copyOf(pages);
        templates = List.copyOf(templates);
        if (issueNumber < 1) {
            throw new IllegalArgumentException("Issue number must be positive");
        }
        var ids = new HashSet<UUID>();
        addUnique(ids, id);
        articles.forEach(article -> addUnique(ids, article.id()));
        advertisements.forEach(ad -> addUnique(ids, ad.id()));
        var elements = new HashSet<>(ids);
        elements.remove(id);
        var templateIds = new HashSet<UUID>();
        templates.forEach(template -> {
            addUnique(ids, template.id());
            templateIds.add(template.id());
        });
        var placed = new HashSet<UUID>();
        for (Page page : pages) {
            addUnique(ids, page.id());
            if (page.templateId() != null && !templateIds.contains(page.templateId())) {
                throw new IllegalArgumentException("Page refers to an unknown template.");
            }
            page.printableArea(pageFormat);
            for (Page.Placement placement : page.placements()) {
                addUnique(placed, placement.elementId());
                if (placement.image() != null) {
                    addUnique(ids, placement.elementId());
                } else if (!elements.contains(placement.elementId())) {
                    throw new IllegalArgumentException("Page refers to an unknown element.");
                }
                if (page.checkPlacement(pageFormat, placement.box()) == Page.PlacementStatus.OUTSIDE_PRINTABLE_AREA) {
                    throw new IllegalArgumentException(page.printableBoundsMessage(pageFormat));
                }
            }
        }
    }

    private static void addUnique(HashSet<UUID> ids, UUID id) {
        if (!ids.add(id)) throw new IllegalArgumentException("Duplicate id: " + id);
    }

    public static Issue create(int issueNumber, LocalDate publicationDate, PageFormat pageFormat, String title) {
        return new Issue(UUID.randomUUID(), issueNumber, publicationDate, pageFormat, new Cover(title), List.of());
    }

    public Issue withDetails(int number, LocalDate date, PageFormat format, String title) {
        return new Issue(id, number, date, format, new Cover(title, cover.image()),
                articles, advertisements, pages, templates);
    }

    public Issue withCoverImage(ImageAsset image) {
        return new Issue(id, issueNumber, publicationDate, pageFormat, new Cover(cover.title(), image),
                articles, advertisements, pages, templates);
    }

    public Issue addArticle(Article article) {
        var updated = new ArrayList<>(articles);
        updated.add(article);
        return withArticles(updated);
    }

    public Issue updateArticle(Article article) {
        var updated = new ArrayList<>(articles);
        updated.set(articleIndex(article.id()), article);
        return withArticles(updated);
    }

    public Issue removeArticle(UUID articleId) {
        var updated = new ArrayList<>(articles);
        updated.remove(articleIndex(articleId));
        return copy(updated, advertisements, withoutPlacement(articleId), templates);
    }

    public Issue addPage() {
        var updated = new ArrayList<>(pages);
        updated.add(new Page(UUID.randomUUID(), Page.DEFAULT_LAYOUT, null, List.of()));
        return copy(articles, advertisements, updated, templates);
    }

    public Issue removePage(UUID pageId) {
        var updated = new ArrayList<>(pages);
        if (!updated.removeIf(page -> page.id().equals(pageId))) throw new IllegalArgumentException("Unknown page.");
        return copy(articles, advertisements, updated, templates);
    }

    public Issue updateLayout(UUID pageId, Page.Layout layout) {
        Page page = page(pageId);
        return replacePage(new Page(pageId, layout, null, page.placements()));
    }

    public Issue saveTemplate(UUID pageId, String name) {
        var updated = new ArrayList<>(templates);
        updated.add(new PageTemplate(UUID.randomUUID(), name, page(pageId).layout()));
        return copy(articles, advertisements, pages, updated);
    }

    public Issue applyTemplate(UUID pageId, UUID templateId) {
        var template = templates.stream().filter(item -> item.id().equals(templateId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown template."));
        return replacePage(new Page(pageId, template.layout(), templateId, page(pageId).placements()));
    }

    public Issue place(UUID pageId, UUID elementId, Page.Box box) {
        return place(pageId, new Page.Placement(elementId, box, null), advertisements);
    }

    public Issue putImage(UUID pageId, UUID imageId, Page.Box box, ImageAsset image) {
        return place(pageId, new Page.Placement(imageId, box, Objects.requireNonNull(image)), advertisements);
    }

    public Issue unplace(UUID elementId) {
        return copy(articles, advertisements, withoutPlacement(elementId), templates);
    }

    public Issue putAdvertisement(Advertisement ad, UUID pageId, Page.Box box) {
        var updated = new ArrayList<>(advertisements);
        int index = 0;
        while (index < updated.size() && !updated.get(index).id().equals(ad.id())) index++;
        if (index == updated.size()) updated.add(ad);
        else updated.set(index, ad);
        return place(pageId, new Page.Placement(ad.id(), box, null), updated);
    }

    public Issue removeAdvertisement(UUID adId) {
        var updated = new ArrayList<>(advertisements);
        if (!updated.removeIf(ad -> ad.id().equals(adId))) throw new IllegalArgumentException("Unknown advertisement.");
        return copy(articles, updated, withoutPlacement(adId), templates);
    }

    public List<TocEntry> contents() {
        var numbers = new HashMap<UUID, Integer>();
        for (int i = 0; i < pages.size(); i++) {
            for (Page.Placement placement : pages.get(i).placements()) numbers.put(placement.elementId(), i + 1);
        }
        return articles.stream().map(article -> new TocEntry(article.title(), numbers.get(article.id()))).toList();
    }

    public String pageHeader(Page page) {
        String extra = page.layout().headerFooterText();
        return "Issue " + issueNumber + " · " + publicationDate + (extra.isBlank() ? "" : " · " + extra);
    }

    public record TocEntry(String title, Integer pageNumber) {}

    private Issue place(UUID pageId, Page.Placement placement, List<Advertisement> ads) {
        page(pageId);
        var updated = new ArrayList<Page>();
        for (Page page : pages) {
            var items = new ArrayList<>(page.placements());
            if (page.id().equals(pageId)) {
                int index = 0;
                while (index < items.size() && !items.get(index).elementId().equals(placement.elementId())) index++;
                if (index == items.size()) items.add(placement);
                else items.set(index, placement);
            } else {
                items.removeIf(item -> item.elementId().equals(placement.elementId()));
            }
            updated.add(new Page(page.id(), page.layout(), page.templateId(), items));
        }
        return copy(articles, ads, updated, templates);
    }

    private List<Page> withoutPlacement(UUID id) {
        return pages.stream().map(page -> new Page(page.id(), page.layout(), page.templateId(),
                page.placements().stream().filter(item -> !item.elementId().equals(id)).toList())).toList();
    }

    private Page page(UUID id) {
        return pages.stream().filter(page -> page.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown page."));
    }

    private Issue replacePage(Page replacement) {
        return copy(articles, advertisements, pages.stream()
                .map(page -> page.id().equals(replacement.id()) ? replacement : page).toList(), templates);
    }

    private int articleIndex(UUID articleId) {
        for (int i = 0; i < articles.size(); i++) {
            if (articles.get(i).id().equals(articleId)) return i;
        }
        throw new IllegalArgumentException("Article does not belong to this issue: " + articleId);
    }

    private Issue withArticles(List<Article> updated) {
        return copy(updated, advertisements, pages, templates);
    }

    private Issue copy(List<Article> articles, List<Advertisement> advertisements, List<Page> pages,
                       List<PageTemplate> templates) {
        return new Issue(id, issueNumber, publicationDate, pageFormat, cover, articles, advertisements, pages, templates);
    }
}
