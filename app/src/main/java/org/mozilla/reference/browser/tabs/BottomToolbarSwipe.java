package org.mozilla.reference.browser.tabs;

/** A drag can only dismiss when its original pointer went down inside the bottom toolbar. */
public final class BottomToolbarSwipe {
    private final float threshold;
    private final float slop;
    private float startX;
    private float startY;
    private int pointer;
    private boolean armed;
    private boolean handled;

    public BottomToolbarSwipe(float threshold, float slop) {
        this.threshold = threshold;
        this.slop = slop;
    }

    public void begin(float x, float y, int pointerId, boolean insideToolbar) {
        startX = x;
        startY = y;
        pointer = pointerId;
        armed = insideToolbar;
        handled = false;
    }

    public boolean move(float x, float y, int pointerId) {
        if (!armed || handled || pointerId != pointer) return false;
        float dx = Math.abs(x - startX);
        float dy = y - startY;
        if (dy < -slop || (dx > slop && dx >= Math.abs(dy))) {
            armed = false;
            return false;
        }
        if (dy >= threshold && dy > dx * 1.15f) {
            handled = true;
            armed = false;
            return true;
        }
        return false;
    }

    public void cancel() { armed = false; }
    public boolean isHandled() { return handled; }
}
