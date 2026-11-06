package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.Map;

/**
 * The streaming home (config.xml, home_layout): one page that scrolls under the clock and buttons. At rest it is the
 * full-screen Recommendations banner with the dock of favourites at its bottom, and the first of what is below the dock
 * peeking under it. Going down brings up the rest of the page: the dock slides to the top, then Continue watching,
 * then all the other apps as a grid, which scrolls on from there. With picks_hero off the banner shows what was being
 * watched instead of the recommendations, and there is no Continue watching row: the apps peek under the dock. The dock is still the launcher's grid
 * view, listing only the dock apps; this class adds the rest around it, from fixed geometry that does not depend on
 * what has focus, and moves the pieces together with translations (the margins hold the page's scrolled-down layout).
 */
final class StreamingHome {
    interface Host {
        void focusDock();
        void focusTop();
        void open(Intent intent);
        /** Puts the launcher's glass highlight over a card centred at (cx, cy) in the root. */
        void placeGlass(float cx, float cy, int width, int height, float radius);
        void openInput(String inputId);
    }

    /** Space between two rows, and the room an app tile's zoom and glass need above and below it. */
    private static final int GAP_DP = 10;
    private static final int ZOOM_ROOM_DP = 16;
    /** Under the last row of apps: the zoom room plus the name shown under the focused tile (a 7dp gap and 22dp of text). */
    private static final int BOTTOM_ROOM_DP = ZOOM_ROOM_DP + 32;
    /** Space above the "All apps" title, below Continue watching, and the room the title itself takes. */
    private static final int SECTION_GAP_DP = 24;
    private static final int APPS_TITLE_DP = 26;
    /** The Continue watching row is laid out for this many cards across, the most it uses: its cards are then the shortest. */
    private static final int CONTINUE_COLUMNS = 6;
    /** Where the dock grid's tiles start from the screen edges: the header's 56dp plus the grid's own 10dp. */
    private static final int SIDE_DP = 66;
    /** How much of what is below the dock shows at rest: MainActivity lifts the dock by this much from the bottom edge. */
    static final int PEEK_DP = 72;
    private static final long SLIDE_MS = 240;

    private final Host host;
    private final FrameLayout root;
    private final RecyclerView grid;
    private final float d;
    private final int columns;
    /** The full-screen banner: the recommendations, or, with them off (config.xml, picks_hero), what was being watched. */
    private final PicksHero banner;
    /** The clock and its date: the scrolling page is clipped under it. */
    private final View clock;
    private final boolean slides;
    /** The Continue watching row under the dock; null when picks_hero is off, as the banner then shows those titles. */
    private final ContinueRow continueRow;
    private final RecyclerView appsRow;
    /** Fades the dock out under the clock as the page scrolls up. */
    private final TopFade dockFade;
    /** The "All apps" heading above the grid, in the style of the Continue watching one; it scrolls with the page. */
    private final TextView appsTitle;
    private final AppAdapter apps;
    private final Rect rect = new Rect();
    private final ValueAnimator slide = ValueAnimator.ofFloat(0f, 1f);
    /** True while focus is below the dock; the page is then scrolled down by {@link #restShift}. */
    private boolean rowsShown;
    /** 0: the page at rest (the dock at the bottom), 1: scrolled down to the dock at the top. */
    private float page;

    // Geometry from layout(), in px.
    private int restShift, appsTop, continueRowsTop, appsTitleTop, gridTop;
    private boolean haveApps;
    /** How far the apps grid has scrolled, from its own scroll events: computeVerticalScrollOffset reads wrong while the padding changes. */
    private int scrolled;
    /** What the last layout() was computed from: it runs again next frame while that keeps changing (the dock settles late). */
    private int lastGeometry;

    StreamingHome(Activity activity, FrameLayout root, RecyclerView grid, View header, float density, Config cfg,
                  int columns, boolean animations, boolean reducedMotion, boolean advancedEffects, boolean overshoot,
                  Host host) {
        this.host = host;
        this.root = root;
        this.grid = grid;
        this.d = density;
        dockFade = TopFade.attachMoving(grid, 60 * density);
        this.columns = columns;
        this.clock = activity.findViewById(R.id.leftBlock);
        this.slides = animations && !reducedMotion;
        banner = new PicksHero(activity, root, density, new PicksHero.Host() {
            @Override public void focusDock() { host.focusDock(); }
            @Override public void focusTop() { host.focusTop(); }
            @Override public void open(Intent intent) { host.open(intent); }
        }, true, !cfg.picksHero, cfg.picksHero);
        continueRow = !cfg.picksHero ? null : new ContinueRow(activity, root, density, new ContinueRow.Host() {
            @Override public void focusDock() { focusApps(); }
            @Override public void focusTop() { host.focusDock(); }
            @Override public void open(Intent intent) { host.open(intent); }
            @Override public void onHeroCovered(boolean covered) { }
            @Override public void onCardFocused(float cx, float cy, int w, int h, float radius) {
                host.placeGlass(cx, cy, w, h, radius);
            }
        }, animations, reducedMotion, advancedEffects, overshoot, cfg.continueColumns);
        if (continueRow != null) continueRow.setOnChanged(this::layout);
        slide.setDuration(SLIDE_MS);
        slide.setInterpolator(new DecelerateInterpolator());
        slide.addUpdateListener(a -> {
            page = (float) a.getAnimatedValue();
            applyPage();
        });

        apps = new AppAdapter(activity, new AppAdapter.TileListener() {
            @Override public void onTileFocused(int position, View card) { placeGlassOver(card); }
            @Override public boolean onTileVerticalKey(int position, int keyCode) {
                switch (keyCode) {
                    case android.view.KeyEvent.KEYCODE_DPAD_UP:
                        if (position < StreamingHome.this.columns) {
                            focusAboveApps();
                            return true;
                        }
                        return false;
                    case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                        // The last row is the page's end: nothing below it.
                        return position / StreamingHome.this.columns == (apps.appCount() - 1) / StreamingHome.this.columns;
                    case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                        return position % StreamingHome.this.columns == 0;
                    case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                        return position == apps.appCount() - 1 || (position + 1) % StreamingHome.this.columns == 0;
                    default:
                        return false;
                }
            }
            @Override public void onLibraryRequested() { }
            @Override public void onLibraryFocused() { }
            @Override public void onTileLongPress(int position, View card) { }
            @Override public void onLibraryUp() { }
            @Override public void onOpenInput(String inputId) { host.openInput(inputId); }
        }, columns, cfg, AppAdapter.Slice.REST);

        appsRow = new RecyclerView(activity);
        appsRow.setLayoutManager(new GridLayoutManager(activity, columns));
        appsRow.setClipChildren(false);
        appsRow.setClipToPadding(false);
        appsRow.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) ->
                v.setClipBounds(new Rect(0, 0, r - l, bt - t)));   // the tiles scroll under the header, not over it
        appsRow.setItemAnimator(null);
        appsRow.setHasFixedSize(true);
        appsRow.setOverScrollMode(View.OVER_SCROLL_NEVER);
        int side = px(SIDE_DP);
        appsRow.setPadding(side, px(ZOOM_ROOM_DP), side, px(BOTTOM_ROOM_DP));
        appsRow.setAdapter(apps);
        appsRow.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                scrolled = Math.max(0, scrolled + dy);
                applyPage();   // the dock and Continue watching ride up with the grid, and the highlight with its tiles
            }
        });
        root.addView(appsRow, new FrameLayout.LayoutParams(-1, px(100), android.view.Gravity.TOP | android.view.Gravity.START));
        appsTitle = new TextView(activity);
        appsTitle.setText(R.string.apps_row_title);
        appsTitle.setTextColor(themeColor(activity));
        appsTitle.setTextSize(13);
        appsTitle.setSingleLine(true);   // before setAllCaps: it replaces the transformation method
        appsTitle.setAllCaps(true);
        appsTitle.setTypeface(UiFont.get(activity, R.string.font_medium));
        appsTitle.setLetterSpacing(0.12f);
        appsTitle.setVisibility(View.INVISIBLE);
        // Lines up with the Continue watching heading and the grid's first tile (about 8% of the width).
        appsTitle.setPadding(Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.0805f), 0, 0, 0);
        root.addView(appsTitle, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP | android.view.Gravity.START));
        // The page is scrolled down exactly while focus is below the dock.
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus == null) return;
            boolean inApps = appsRow.findContainingItemView(newFocus) != null;
            if (!inApps) {
                if (scrolled > 0) appsRow.scrollBy(0, -scrolled);   // back to the top of the grid before the dock shows again
            }
            setRows(inApps || continueRow != null && continueRow.owns(newFocus));
        });

        // The dock parks itself by scrolling after the first layout, and a rebuilt Home settles after that again: lay
        // the page out again whenever the dock moves, and once more when things have settled.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                layout();
            }
        });
        root.postDelayed(this::layout, 500);
        root.postDelayed(this::layout, 1500);

        // Under the clock and the buttons, and under the glass: the banner and rows go before the header in the root.
        int at = root.indexOfChild(header);
        for (View v : continueRow == null ? new View[]{banner.view(), appsTitle, appsRow} : new View[]{banner.view(), continueRow.view(), appsTitle, appsRow}) {
            root.removeView(v);
            root.addView(v, at++);
        }
    }

    /** Home's mid text colour in the activity's theme: white at night, dark ink by day (auto_theme). */
    private static int themeColor(Activity activity) {
        android.util.TypedValue value = new android.util.TypedValue();
        activity.getTheme().resolveAttribute(R.attr.homeTextMid, value, true);
        return value.data;
    }

    private int px(int dp) {
        return Math.round(dp * d);
    }

    // ---- Data --------------------------------------------------------------------------------------------------

    void reload(List<Cards.Card> cards, List<ResolveInfo> leanback) {
        apps.reload(cards, leanback);
        layout();
    }

    void setSavedOrder(Map<String, Integer> ranks, int docked) {
        apps.setSavedOrder(ranks, docked);
    }

    void refresh() {
        banner.refresh();
        if (continueRow != null) continueRow.refresh();
    }

    void close() {
        slide.cancel();
        banner.close();
        if (continueRow != null) continueRow.close();
    }

    // ---- Geometry ----------------------------------------------------------------------------------------------

    /**
     * Lays the page out from the screen's height and where the dock rests: the page's scrolled-down layout (dock at the
     * top, then Continue watching, then the apps) is held by the margins and padding, and the page at rest is that
     * layout moved down by {@link #restShift}. The banner takes everything above the dock. Called whenever the dock may
     * have moved (the grid lays out) and when the apps or Continue watching change.
     */
    void layout() {
        int rootH = root.getHeight();
        if (rootH <= 0 || grid.getHeight() <= 0) return;
        // The dock's decorated top: where its first tile really is (DockDecoration leaves 6dp above the row). Before
        // the grid has laid out a tile, the grid's top plus the padding that pushes the dock row down.
        View first = grid.getLayoutManager() == null ? null : grid.getLayoutManager().findViewByPosition(0);
        int dockTop, dockBlock;
        int gap = px(GAP_DP);
        if (first != null) {
            rect.set(0, 0, first.getWidth(), first.getHeight());
            root.offsetDescendantRectToMyCoords(first, rect);
            dockTop = rect.top - px(6);
            dockBlock = first.getHeight() + px(6) + px(12) + gap;   // the tile, the room above it and DockDecoration's below
        } else {
            rect.set(0, 0, grid.getWidth(), grid.getHeight());
            root.offsetDescendantRectToMyCoords(grid, rect);
            dockTop = rect.top + grid.getPaddingTop() - px(6);
            dockBlock = px(100);
        }
        rect.set(0, 0, grid.getWidth(), grid.getHeight());
        root.offsetDescendantRectToMyCoords(grid, rect);
        gridTop = rect.top;   // in the root, translations left out
        rect.set(0, 0, clock.getWidth(), clock.getHeight());
        root.offsetDescendantRectToMyCoords(clock, rect);
        appsTop = rect.bottom + px(4);
        int dockTopRows = appsTop + px(ZOOM_ROOM_DP);
        restShift = Math.max(0, dockTop - dockTopRows);
        continueRowsTop = dockTopRows + dockBlock;

        int continueH = 0;
        if (continueRow != null) {
            // Under the dock: the row's own height, whatever the room.
            continueRow.showAt(continueRowsTop, continueRow.heightFor(CONTINUE_COLUMNS));
            continueH = continueRow.shownHeight();
        }
        // The "All apps" title sits under Continue watching, with more room above it than between two rows.
        int belowH = continueH > 0 ? continueH + px(SECTION_GAP_DP) : 0;
        appsTitleTop = continueRowsTop + belowH;
        int pad = continueRowsTop - appsTop + belowH + px(APPS_TITLE_DP);
        if (appsRow.getPaddingTop() != pad) {
            appsRow.setPadding(appsRow.getPaddingLeft(), pad, appsRow.getPaddingRight(), px(BOTTOM_ROOM_DP));
            // The grid keeps its first tile where it was when the padding changes: put it back under the new padding.
            if (appsRow.getFocusedChild() == null) appsRow.post(() -> ((GridLayoutManager) appsRow.getLayoutManager()).scrollToPositionWithOffset(0, 0));
        }
        haveApps = apps.appCount() > 0;
        if (appsRow.getFocusedChild() == null) scrolled = 0;   // a reload puts the grid back at its top
        // A margin, not a translation: offsetDescendantRectToMyCoords (the glass) ignores translations.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) appsRow.getLayoutParams();
        int appsH = rootH - appsTop;
        if (lp.height != appsH || lp.topMargin != appsTop) {
            lp.height = appsH;
            lp.topMargin = appsTop;
            appsRow.requestLayout();
        }
        appsRow.setVisibility(haveApps ? View.VISIBLE : View.INVISIBLE);
        appsTitle.setVisibility(haveApps ? View.VISIBLE : View.INVISIBLE);
        int geometry = (dockTop * 31 + appsTop) * 31 + dockBlock;
        if (geometry != lastGeometry) {
            lastGeometry = geometry;
            root.post(this::layout);
        }
        banner.showFixed(0, rootH, rootH - dockTop + gap + px(28));
        banner.setShownOver(!rowsShown);
        applyPage();
    }

    /** Moves the dock, Continue watching and the apps to where the scroll has the page: the rows' scroll offset plus the slide. */
    private void applyPage() {
        float down = restShift * page;
        grid.setTranslationY(-(down + scrolled));
        // The dock fades out as it goes up under the clock and buttons, instead of passing over them.
        dockFade.setCut(grid, Math.round(appsTop - px(36) - (gridTop - down - scrolled)));   // the fade ends where the dock rests
        appsRow.setTranslationY(restShift * (1 - page));
        if (continueRow != null) continueRow.setPosition(continueRowsTop + restShift * (1 - page) - scrolled, appsTop);
        // Cut under the header like the grid's tiles: the title scrolls up with the page and disappears under it.
        float titleY = appsTitleTop + restShift * (1 - page) - scrolled;
        appsTitle.setY(titleY);
        int cut = Math.round(appsTop - titleY);
        appsTitle.setClipBounds(cut > 0 ? new Rect(0, cut, appsTitle.getWidth(), appsTitle.getHeight()) : null);
        View focused = appsRow.getFocusedChild();
        View card = focused == null ? null : focused.findViewById(R.id.card);
        if (card != null && card.isFocused()) placeGlassOver(card);
    }

    /** Scrolls the page down to the rows, away from the banner, or back to rest with the banner. */
    private void setRows(boolean show) {
        if (show == rowsShown) return;
        rowsShown = show;
        banner.setShownOver(!show);
        slide.cancel();
        float to = show ? 1f : 0f;
        if (!slides || root.getHeight() <= 0) {
            page = to;
            applyPage();
        } else {
            slide.setFloatValues(page, to);
            slide.start();
        }
    }

    // ---- Focus -------------------------------------------------------------------------------------------------

    /** Up from the dock: the banner, or the top bar when it is empty. */
    void focusAboveDock() {
        if (!banner.focus()) host.focusTop();
    }

    /** Down from the dock: Continue watching below it, or the apps. */
    void focusBelowDock() {
        if (continueRow == null || !continueRow.focusFirst()) focusApps();
    }

    /** Down from the top bar: the banner, or the dock when it is empty. */
    void focusFromTop() {
        if (!banner.focus()) host.focusDock();
    }

    private void focusAboveApps() {
        if (continueRow == null || !continueRow.focusFirst()) host.focusDock();
    }

    private boolean focusApps() {
        if (apps.appCount() == 0 || appsRow.getVisibility() != View.VISIBLE) return false;
        View focused = appsRow.getFocusedChild();
        View item = focused != null ? focused : appsRow.getChildAt(0);
        View card = item == null ? null : item.findViewById(R.id.card);
        return card != null && card.requestFocus();
    }

    /** Whether v is a poster of the Continue watching row, so the launcher keeps the glass while focus is on it. */
    boolean owns(View v) {
        return continueRow != null && continueRow.owns(v);
    }

    private void placeGlassOver(View card) {
        if (card.getWidth() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) placeGlassOver(card);
            });
            return;
        }
        rect.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, rect);
        rect.offset(0, Math.round(appsRow.getTranslationY()));   // the offset above leaves translations out
        host.placeGlass(rect.exactCenterX(), rect.exactCenterY(), card.getWidth(), card.getHeight(),
                AppAdapter.cornerRadius(card.getWidth(), d));
    }
}
