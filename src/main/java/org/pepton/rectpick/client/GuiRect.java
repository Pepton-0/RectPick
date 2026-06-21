package org.pepton.rectpick.client;

/**
 * Axis-aligned rectangle in GUI-scaled screen space.
 *
 * @param left   minimum X coordinate.
 * @param top    minimum Y coordinate.
 * @param right  maximum X coordinate.
 * @param bottom maximum Y coordinate.
 */
public record GuiRect(double left, double top, double right, double bottom) {
    /**
     * Builds a normalized rectangle from two arbitrary points.
     *
     * @param a first point; must not be {@code null}.
     * @param b second point; must not be {@code null}.
     * @return a rectangle whose left/top are minima and right/bottom are maxima of the two points.
     */
    public static GuiRect fromTwoPoints(GuiPoint a, GuiPoint b) {
        return new GuiRect(
                Math.min(a.x(), b.x()),
                Math.min(a.y(), b.y()),
                Math.max(a.x(), b.x()),
                Math.max(a.y(), b.y())
        );
    }

    /**
     * Tests whether this rectangle intersects another axis-aligned rectangle.
     *
     * @param otherLeft   minimum X coordinate of the other rectangle.
     * @param otherTop    minimum Y coordinate of the other rectangle.
     * @param otherRight  maximum X coordinate of the other rectangle.
     * @param otherBottom maximum Y coordinate of the other rectangle.
     * @return {@code true} when the two rectangles overlap with positive area.
     */
    public boolean intersects(double otherLeft, double otherTop, double otherRight, double otherBottom) {
        return left < otherRight
                && right > otherLeft
                && top < otherBottom
                && bottom > otherTop;
    }
}
