package org.mozilla.reference.browser.browser;

/** Pure geometry shared by the view layout and regression tests. */
public final class ViewportGeometry {
    private ViewportGeometry() { }

    public static int bottomOverlap(int rootBottom, int windowBottom,
            int navigationBottom, int imeBottom, boolean imeVisible) {
        int obstruction = Math.max(navigationBottom, imeVisible ? imeBottom : 0);
        return Math.max(0, rootBottom - (windowBottom - obstruction));
    }

    public static int contentBottomMargin(int toolbarHeight, boolean toolbarVisible,
            boolean pageKeyboardVisible, boolean fullscreen) {
        return toolbarVisible && !pageKeyboardVisible && !fullscreen
                ? Math.max(0, toolbarHeight) : 0;
    }

    public static int listHeight(int available, int toolbarHeight, int rows, int rowHeight) {
        int limit = Math.max(0, available - toolbarHeight);
        return (int) Math.min(limit, Math.max(1, rows) * (long) Math.max(0, rowHeight));
    }
}
