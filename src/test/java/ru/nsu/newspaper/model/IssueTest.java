package ru.nsu.newspaper.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IssueTest {
    @Test
    void editingDetailsKeepsIssueIdentity() {
        Issue first = Issue.create(1, LocalDate.of(2026, 11, 11), PageFormat.A4, "Morning News");
        Issue edited = first.withDetails(2, LocalDate.of(2026, 11, 12), PageFormat.A3, "Evening News");

        assertEquals(first.id(), edited.id());
        assertEquals(2, edited.issueNumber());
        assertEquals(PageFormat.A3, edited.pageFormat());
        assertEquals("Evening News", edited.cover().title());
    }

    @Test
    void rejectsInvalidIssueNumber() {
        assertThrows(IllegalArgumentException.class,
                () -> Issue.create(0, LocalDate.now(), PageFormat.A4, "News"));
    }
}
