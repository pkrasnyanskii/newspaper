package ru.nsu.newspaper.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Issue(UUID id, int issueNumber, LocalDate publicationDate, PageFormat pageFormat,
                    Cover cover, List<Article> articles) {
    public Issue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(publicationDate, "publicationDate");
        Objects.requireNonNull(pageFormat, "pageFormat");
        Objects.requireNonNull(cover, "cover");
        articles = List.copyOf(articles);
        var ids = new HashSet<UUID>();
        for (Article article : articles) {
            if (!ids.add(article.id())) {
                throw new IllegalArgumentException("Duplicate article id: " + article.id());
            }
        }
        if (issueNumber < 1) {
            throw new IllegalArgumentException("Issue number must be positive");
        }
    }

    public static Issue create(int issueNumber, LocalDate publicationDate, PageFormat pageFormat, String title) {
        return new Issue(UUID.randomUUID(), issueNumber, publicationDate, pageFormat, new Cover(title), List.of());
    }

    public Issue withDetails(int number, LocalDate date, PageFormat format, String title) {
        return new Issue(id, number, date, format, new Cover(title), articles);
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
        return withArticles(updated);
    }

    private int articleIndex(UUID articleId) {
        for (int i = 0; i < articles.size(); i++) {
            if (articles.get(i).id().equals(articleId)) return i;
        }
        throw new IllegalArgumentException("Article does not belong to this issue: " + articleId);
    }

    private Issue withArticles(List<Article> updated) {
        return new Issue(id, issueNumber, publicationDate, pageFormat, cover, updated);
    }
}
