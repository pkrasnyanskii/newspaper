package ru.nsu.newspaper.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Page(UUID id, Layout layout, UUID templateId, List<Placement> placements) {
    public static final Layout DEFAULT_LAYOUT = new Layout(1, new Margins(20, 15, 20, 15), "");

    public Page {
        Objects.requireNonNull(id);
        Objects.requireNonNull(layout);
        placements = List.copyOf(placements);
    }

    public Box printableArea(PageFormat format) {
        return layout.printableArea(format);
    }

    public PlacementStatus checkPlacement(PageFormat format, Box block) {
        return printableArea(format).contains(block) ? PlacementStatus.FITS : PlacementStatus.OUTSIDE_PRINTABLE_AREA;
    }

    public String printableBoundsMessage(PageFormat format) {
        Box area = printableArea(format);
        return "Block must fit in the printable area: x " + area.x() + "–" + (area.x() + area.width())
                + " mm, y " + area.y() + "–" + (area.y() + area.height()) + " mm.";
    }

    public enum PlacementStatus { FITS, OUTSIDE_PRINTABLE_AREA }

    public record Box(double x, double y, double width, double height) {
        public Box {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width)
                    || !Double.isFinite(height) || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Enter finite coordinates and positive dimensions.");
            }
        }

        public boolean contains(Box other) {
            double tolerance = 1e-9;
            return other.x >= x - tolerance && other.y >= y - tolerance
                    && other.x + other.width <= x + width + tolerance
                    && other.y + other.height <= y + height + tolerance;
        }
    }

    public record Margins(double top, double right, double bottom, double left) {
        public Margins {
            for (double value : new double[]{top, right, bottom, left}) {
                if (!Double.isFinite(value) || value < 0) {
                    throw new IllegalArgumentException("Margins must be finite and nonnegative.");
                }
            }
        }
    }

    public record Layout(int columns, Margins margins, String headerFooterText) {
        public Layout {
            Objects.requireNonNull(margins);
            Objects.requireNonNull(headerFooterText);
            if (columns < 1) throw new IllegalArgumentException("Enter a positive number of columns.");
        }

        public Box printableArea(PageFormat format) {
            double width = format.widthMm() - margins.left - margins.right;
            double height = format.heightMm() - margins.top - margins.bottom;
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Margins must leave a printable area.");
            if (width / columns < 1) throw new IllegalArgumentException("Each column needs at least 1 mm of usable width.");
            return new Box(margins.left, margins.top, width, height);
        }
    }

    public record Placement(UUID elementId, Box box, ImageAsset image) {
        public Placement {
            Objects.requireNonNull(elementId);
            Objects.requireNonNull(box);
        }
    }
}
