package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.Map;

/**
 * The streaming home (config.xml, home_layout): a full-screen Recommendations banner with the clock and buttons drawn
 * over it and the dock of favourites fixed at the bottom. Going down from the banner brings up a row of Continue
 * watching cards and a sideways row of all the other apps, in place of the banner; they go, and the banner comes back,
 * when focus leaves them. The
 * dock is still the launcher's grid view, listing only the dock apps; this class adds the rest around it, from fixed
 * geometry that does not depend on what has focus.
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
    /** The Continue watching row is laid out for this many cards across, the most it uses: its cards are then the shortest. */
    private static final int CONTINUE_COLUMNS = 6;
    /** Where the dock grid's tiles start from the screen edges: the header's 56dp plus the grid's own 10dp. */
    private static final int SIDE_DP = 66;

    private final Host host;
    private final FrameLayout root;
    private final RecyclerView grid;
    private final float d;
    private final int columns;
    private final PicksHero banner;
    private final ContinueRow continueRow;
    private final RecyclerView appsRow;
    private final AppAdapter apps;
    private final Rect rect = new Rect();
    private boolean rowsShown;

    StreamingHome(Activity activity, FrameLayout root, RecyclerView grid, View header, float density, Config cfg,
                  int columns, boolean animations, boolean reducedMotion, boolean advancedEffects, boolean overshoot,
                  Host host) {
        this.host = host;
        this.root = root;
        this.grid = grid;
        this.d = density;
        this.columns = columns;
        banner = new PicksHero(activity, root, density, new PicksHero.Host() {
            @Override public void focusDock() { focusBelowBanner(); }
            @Override public void focusTop() { host.focusTop(); }
            @Override public void open(Intent intent) { host.open(intent); }
        }, true);
        continueRow = new ContinueRow(activity, root, density, new ContinueRow.Host() {
            @Override public void focusDock() { focusApps(); }
            @Override public void focusTop() { focusAboveContinue(); }
            @Override public void open(Intent intent) { host.open(intent); }
            @Override public void onHeroCovered(boolean covered) { }
            @Override public void onCardFocused(float cx, float cy, int w, int h, float radius) {
                host.placeGlass(cx, cy, w, h, radius);
            }
        }, animations, reducedMotion, advancedEffects, overshoot, cfg.continueColumns);

        apps = new AppAdapter(activity, new AppAdapter.TileListener() {
            @Override public void onTileFocused(int position, View card) { placeGlassOver(card); }
            @Override public boolean onTileVerticalKey(int position, int keyCode) {
                switch (keyCode) {
                    case android.view.KeyEvent.KEYCODE_DPAD_UP:
                        focusAboveApps();
                        return true;
                    case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                        host.focusDock();
                        return true;
                    case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                        return position == 0;
                    case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                        return position == apps.appCount() - 1;
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
        appsRow.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        appsRow.setClipChildren(false);
        appsRow.setClipToPadding(false);
        appsRow.setItemAnimator(null);
        appsRow.setHasFixedSize(true);
        appsRow.setOverScrollMode(View.OVER_SCROLL_NEVER);
        int side = px(SIDE_DP);
        appsRow.setPadding(side, px(ZOOM_ROOM_DP), side, px(ZOOM_ROOM_DP));
        appsRow.setVisibility(View.INVISIBLE);
        appsRow.setAdapter(apps);
        appsRow.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                View focused = rv.getFocusedChild();
                View card = focused == null ? null : focused.findViewById(R.id.card);
                if (card != null && card.isFocused()) placeGlassOver(card);   // the highlight follows the tiles as they scroll
            }
        });
        root.addView(appsRow, new FrameLayout.LayoutParams(-1, px(100), android.view.Gravity.TOP | android.view.Gravity.START));
        // The rows are up exactly while focus is in them.
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus != null) setRows(continueRow.owns(newFocus) || appsRow.findContainingItemView(newFocus) != null);
        });

        // Under the clock and the buttons, and under the glass: the banner and rows go before the header in the root.
        int at = root.indexOfChild(header);
        for (View v : new View[]{banner.view(), continueRow.view(), appsRow}) {
            root.removeView(v);
            root.addView(v, at++);
        }
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
        continueRow.refresh();
    }

    void close() {
        banner.close();
        continueRow.close();
    }

    // ---- Geometry ----------------------------------------------------------------------------------------------

    /**
     * Places the rows from the screen's height and where the dock sits: the apps row right above the dock, the Continue
     * watching row above that with the room its cards need, and the banner takes everything that is left, from the top
     * edge. Called whenever the dock may have moved (the grid lays out) and when the apps change.
     */
    void layout() {
        int rootH = root.getHeight();
        if (rootH <= 0 || grid.getHeight() <= 0) return;
        // The dock's decorated top: where its first tile really is (DockDecoration leaves 6dp above the row). Before
        // the grid has laid out a tile, the grid's top plus the padding that pushes the dock row down.
        View first = grid.getLayoutManager() == null ? null : grid.getLayoutManager().findViewByPosition(0);
        int dockTop;
        if (first != null) {
            rect.set(0, 0, first.getWidth(), first.getHeight());
            root.offsetDescendantRectToMyCoords(first, rect);
            dockTop = rect.top - px(6);
        } else {
            rect.set(0, 0, grid.getWidth(), grid.getHeight());
            root.offsetDescendantRectToMyCoords(grid, rect);
            dockTop = rect.top + grid.getPaddingTop() - px(6);
        }
        int inner = root.getWidth() - 2 * px(SIDE_DP);
        int tileW = AppAdapter.tileWidthPx(inner, columns, d);
        int appsItemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW), false, d);
        int appsH = appsItemH + 2 * px(ZOOM_ROOM_DP);
        int gap = px(GAP_DP);
        int appsTop = dockTop - gap - appsH;
        boolean haveApps = apps.appCount() > 0;
        if (!haveApps) appsTop = dockTop;
        // A margin, not a translation: offsetDescendantRectToMyCoords (the glass) ignores translations.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) appsRow.getLayoutParams();
        if (lp.height != appsH || lp.topMargin != appsTop) {
            lp.height = appsH;
            lp.topMargin = appsTop;
            appsRow.requestLayout();
        }
        int continueH = continueRow.heightFor(CONTINUE_COLUMNS);
        int continueTop = appsTop - gap - continueH;
        banner.showFixed(0, rootH, rootH - dockTop + gap + px(28));
        this.haveApps = haveApps;
        this.continueTop = continueTop;
        this.continueH = continueH;
        applyRows();
    }

    private boolean haveApps;
    private int continueTop, continueH;

    /** Brings the two rows up, with the banner gone from behind them, or puts them away and brings the banner back. */
    private void setRows(boolean show) {
        if (show == rowsShown) return;
        rowsShown = show;
        applyRows();
    }

    private void applyRows() {
        if (rowsShown) {
            continueRow.showAt(continueTop, continueH);
            appsRow.setVisibility(haveApps ? View.VISIBLE : View.INVISIBLE);
            banner.setShownOver(false);
        } else {
            banner.setShownOver(true);
            continueRow.hide();
            appsRow.setVisibility(View.INVISIBLE);
        }
    }

    // ---- Focus -------------------------------------------------------------------------------------------------

    /** Up from the dock: the banner. The rows only come up from there, going down. */
    void focusAboveDock() {
        if (!banner.focus()) {
            setRows(true);
            if (!continueRow.focusFirst() && !focusApps()) host.focusTop();
        }
    }

    /** Down from the top bar: the banner, or the rows when there is no banner. */
    void focusFromTop() {
        if (banner.focus()) return;
        setRows(true);
        if (!continueRow.focusFirst() && !focusApps()) host.focusDock();
    }

    private void focusBelowBanner() {
        setRows(true);
        if (!continueRow.focusFirst() && !focusApps()) {
            setRows(false);
            host.focusDock();
        }
    }

    private void focusAboveContinue() {
        if (!banner.focus()) host.focusTop();
    }

    private void focusAboveApps() {
        if (!continueRow.focusFirst() && !banner.focus()) host.focusTop();
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
        return continueRow.owns(v);
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
        host.placeGlass(rect.exactCenterX(), rect.exactCenterY(), card.getWidth(), card.getHeight(),
                AppAdapter.cornerRadius(card.getWidth(), d));
    }
}
