package org.pepton.rectpick.client;

/**
 * GUI-scaled mouse position.
 *
 * @param x X coordinate in GUI-scaled screen space.
 * @param y Y coordinate in GUI-scaled screen space.
 */
public record GuiPoint(double x, double y) {
    /**
     * Checks whether another point has exactly the same GUI coordinates.
     *
     * @param other point to compare; may be {@code null}.
     * @return {@code true} only when {@code other} is non-null and both coordinates are exactly equal.
     */
    public boolean isSamePosition(GuiPoint other) {
        return other != null
                && Double.compare(x, other.x) == 0
                && Double.compare(y, other.y) == 0;
    }

    /**
     * Calculates the Euclidean distance to another GUI point.
     *
     * @param other point to measure against; must not be {@code null}.
     * @return non-negative GUI-scaled distance between this point and {@code other}.
     */
    public double distanceTo(GuiPoint other) {
        double dx = x - other.x;
        double dy = y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
