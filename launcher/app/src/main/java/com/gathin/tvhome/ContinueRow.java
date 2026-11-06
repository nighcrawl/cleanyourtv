package com.gathin.tvhome;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Continue watching row above the dock (config.xml, continue_row). The rows come from the Reprendre app's
 * content provider, which also downloads and caches the posters, so the launcher itself still has no network
 * access and no extra permission. Without that app installed the row simply never shows.
 */
final class ContinueRow {
    interface Host {
        void focusDock();
        void focusTop();
        void open(Intent intent);
        /** A poster took focus: its centre in the root, its size at rest and its corner radius, all in px. */
        /** The row took the place of the dock app's name (no room for both), or gave it back. */
        void onHeroCovered(boolean covered);
        void onCardFocused(float centerX, float centerY, int width, int height, float radius);
    }

    private static final Uri ITEMS = Uri.parse("content://tv.reprendre.items/items");
    private static final int MAX_ITEMS = 24;
    private static final long REFRESH_MS = 30_000;
    /** What sits around the posters (heading, name lines, paddings), in dp: the row is this plus a poster's height. */
    private static final int FIXED_DP = 82;
    /** The most cards across: past it a 16:9 poster is too small to read, and the row stays hidden. */
    private static final int MAX_COLUMNS = 6;
    /** Space between two posters, and the part of it a card owns: its 4dp frame on each side plus this margin. */
    private static final int GAP_DP = 14;
    private static final int MARGIN_DP = GAP_DP - 8;

    private static final class Entry {
        String title, subtitle, intent, pkg;
        int progress;
        Bitmap poster;
    }

    private final Activity activity;
    private final Host host;
    private final float d;
    private final LinearLayout box;
    private final LinearLayout cards;
    private final HorizontalScrollView scroll;
    /** Cards that fill the grid's width at once (the row scrolls for the rest), and a poster's width in px for that many. */
    private int columns, cardW;
    /** The number of columns asked for (config.xml, continue_columns); more may be shown where the room is short. */
    private final int columnsChoice;
    /** Posters are decoded for 3 across, twice their drawn size, whatever the columns are: 2 across still draws them sharp. */
    private final int decodeW;
    /** Distance of the grid's first and last tiles from the screen edges, and the screen width, in px. */
    private final int inset, screenW;
    /** The focus zoom's modes, the same as the app tiles' (see Motion.zoom). */
    private final boolean animations, reducedMotion, advancedEffects, overshoot;
    /** The poster that has focus, or null while focus is elsewhere. */
    private View focusedCard;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private List<Entry> entries = new ArrayList<>();
    private long lastLoad = -REFRESH_MS;
    private boolean loading;
    private Runnable onChanged;
    private boolean shown;
    private float heroTop, dockTop, topLimit;
    /** Streaming home's fixed place (showAt); the height is 0 in the classic layout. */
    private float fixedTop, fixedHeight;
    /** Poster height in px: always 16:9 of cardW. */
    private int posterH;
    /** The row fits between the top bar and the dock's name; false keeps it hidden instead of over the clock. */
    private boolean fits = true;

    ContinueRow(Activity activity, FrameLayout root, float density, Host host, boolean animations,
                boolean reducedMotion, boolean advancedEffects, boolean overshoot, int columns) {
        this.activity = activity;
        this.host = host;
        this.d = density;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.advancedEffects = advancedEffects;
        this.overshoot = overshoot;
        box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setVisibility(View.INVISIBLE);
        TextView heading = new TextView(activity);
        heading.setText(R.string.continue_row_title);
        heading.setTextColor(themeColor(R.attr.homeTextMid));
        heading.setTextSize(15);
        heading.setSingleLine(true);
        heading.setTypeface(UiFont.get(activity, R.string.font_medium));
        heading.setLetterSpacing(0.08f);
        // The grid's first and last tiles sit this far from the screen edges (about 8% of the width); the row lines up with them.
        screenW = activity.getResources().getDisplayMetrics().widthPixels;
        inset = Math.round(screenW * 0.0805f);
        decodeW = 2 * widthFor(3);
        columnsChoice = columns;
        setColumns(columns);
        heading.setPadding(inset, 0, 0, 0);
        box.addView(heading);
        // The default scroll puts a focused card flush against the screen edge; reveal() keeps it inside the inset instead.
        scroll = new HorizontalScrollView(activity) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return 0;
            }
        };
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);   // the focused poster's zoom and lift reach past the scroll's edges
        scroll.setClipToPadding(false);   // the row spans the whole screen: cards scroll out at its edges, not at the left margin
        // A card sits 4dp inside its focus frame and keeps a small margin after it: with these paddings the first card
        // starts at the inset and, scrolled to the end, the last one ends at the inset from the right edge.
        // The top padding leaves room for the focused poster's zoom and lift above the others.
        scroll.setPadding(inset - px(4), px(8), inset - px(MARGIN_DP + 4), px(8));
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (focusedCard != null) notifyFocused(focusedCard);   // the highlight follows the posters as they scroll
        });
        cards = new LinearLayout(activity) {
            {
                setChildrenDrawingOrderEnabled(true);
            }

            /** The focused card is drawn last, so its zoom lies over its neighbours instead of under them. */
            @Override protected int getChildDrawingOrder(int count, int i) {
                int f = indexOfChild(getFocusedChild());
                if (f < 0) return i;
                return i == count - 1 ? f : i >= f ? i + 1 : i;
            }
        };
        cards.setClipChildren(false);
        scroll.addView(cards);
        box.addView(scroll);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(FIXED_DP) + posterH,
                Gravity.TOP | Gravity.START);
        
        root.addView(box, lp);
    }

    /** A poster's width for this many across: they and their gaps fill the width between the grid's first and last tile. */
    private int widthFor(int count) {
        return (screenW - 2 * inset - (count - 1) * px(GAP_DP)) / count;
    }

    /** True when the number changed, so the cards must be built again at the new size. */
    private boolean setColumns(int count) {
        if (count == columns) return false;
        columns = count;
        cardW = widthFor(count);
        posterH = cardW * 9 / 16;
        return true;
    }

    /** One of Home's text colours in the activity's theme: white at night, dark ink by day (auto_theme). */
    private int themeColor(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        activity.getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    private int px(int dp) {
        return (int) (dp * d);
    }

    /** Whether v is one of the cards, so the launcher keeps the glass highlight while focus is on it. */
    boolean owns(View v) {
        return v != null && v.getParent() == cards;
    }

    boolean hasItems() {
        return !entries.isEmpty();
    }

    /** Moves focus into the row; false when it is not on screen, so the caller keeps its own Up behaviour. */
    boolean focusFirst() {
        if (!shown || entries.isEmpty() || cards.getChildCount() == 0 || box.getVisibility() != View.VISIBLE) return false;
        return cards.getChildAt(0).requestFocus();
    }

    /**
     * Shows the row just above the top shelf (the dock app's name), whose top edge is heroTopY in the root, and
     * below the top bar, which ends at topLimitY. Where the room between them is short (big tiles, rows under the
     * dock) the row takes the name's place, down to dockTopY, and the host hides the name. The posters are always
     * 16:9: when even that is too short for them, there are more of them across (narrower), up to MAX_COLUMNS, and
     * past that the row stays hidden.
     */
    void show(float heroTopY, float dockTopY, float topLimitY) {
        shown = true;
        heroTop = heroTopY;
        dockTop = dockTopY;
        topLimit = topLimitY;
        if (entries.isEmpty()) return;
        int fixed = px(FIXED_DP);   // heading, name lines and paddings around the posters
        float bottom = heroTopY - 6 * d;
        int wanted = columnsChoice;
        boolean covered = widthFor(wanted) * 9 / 16 > bottom - topLimitY - fixed;
        if (covered) bottom = dockTopY - 16 * d;
        int count = wanted;
        while (count < MAX_COLUMNS && widthFor(count) * 9 / 16 > bottom - topLimitY - fixed) count++;
        fits = widthFor(count) * 9 / 16 <= bottom - topLimitY - fixed;
        host.onHeroCovered(fits && covered);
        if (!fits) {
            box.animate().cancel();
            box.setAlpha(0f);
            box.setVisibility(View.INVISIBLE);
            return;
        }
        if (setColumns(count)) {
            buildCards();
            box.getLayoutParams().height = fixed + posterH;
            box.requestLayout();
        }
        box.setY(bottom - fixed - posterH);
        if (box.getVisibility() != View.VISIBLE) {
            box.setAlpha(0f);
            box.setVisibility(View.VISIBLE);
            box.animate().alpha(1f).setDuration(200).start();
        }
    }

    /**
     * Streaming home: the row sits at topY with availableH of room whatever has focus, and takes as many columns as that
     * height needs (up to MAX_COLUMNS); with no room at all it stays hidden.
     */
    void showAt(float topY, float availableH) {
        shown = true;
        fixedTop = topY;
        fixedHeight = availableH;
        if (entries.isEmpty()) return;
        int fixed = px(FIXED_DP);
        int count = columnsChoice;
        while (count < MAX_COLUMNS && widthFor(count) * 9 / 16 > availableH - fixed) count++;
        fits = widthFor(count) * 9 / 16 <= availableH - fixed;
        if (!fits) {
            box.animate().cancel();
            box.setAlpha(0f);
            box.setVisibility(View.INVISIBLE);
            return;
        }
        if (setColumns(count)) {
            buildCards();
            box.getLayoutParams().height = fixed + posterH;
            box.requestLayout();
        }
        box.setY(topY);
        if (box.getVisibility() != View.VISIBLE) {
            box.setAlpha(0f);
            box.setVisibility(View.VISIBLE);
            box.animate().alpha(1f).setDuration(200).start();
        }
    }

    /** Height of the row (heading, posters and names) with this many cards across. */
    int heightFor(int columns) {
        return px(FIXED_DP) + widthFor(columns) * 9 / 16;
    }

    /** The row's view, so the streaming home can order it under the clock and buttons. */
    View view() {
        return box;
    }

    /** Puts the row at y in the root, cutting off what passes above clipY (under the header) and following it for the glass. */
    void setPosition(float y, float clipY) {
        box.setY(y);
        int cut = Math.round(clipY - y);
        box.setClipBounds(cut > 0 ? new Rect(0, cut, box.getWidth(), Math.max(box.getHeight(), px(FIXED_DP) + posterH)) : null);
        if (focusedCard != null) notifyFocused(focusedCard);
    }

    /** Height of the row as shown: 0 while it has nothing to show or does not fit. */
    int shownHeight() {
        return entries.isEmpty() || !fits ? 0 : px(FIXED_DP) + posterH;
    }

    void hide() {
        shown = false;
        box.animate().cancel();
        box.setAlpha(0f);
        box.setVisibility(View.INVISIBLE);
    }

    void close() {
        io.shutdownNow();
    }

    /** Reads the rows again, at most every 30 seconds. */
    void refresh() {
        long now = SystemClock.elapsedRealtime();
        if (loading || now - lastLoad < REFRESH_MS) return;
        loading = true;
        lastLoad = now;
        io.execute(() -> {
            List<Entry> list = query();
            if (!activity.isDestroyed()) activity.runOnUiThread(() -> apply(list));
        });
    }

    private List<Entry> query() {
        List<Entry> list = new ArrayList<>();
        ContentResolver cr = activity.getContentResolver();
        try (Cursor c = cr.query(ITEMS, null, null, null, null)) {
            if (c == null) return list;
            int iTitle = c.getColumnIndex("title"), iSub = c.getColumnIndex("subtitle"), iProg = c.getColumnIndex("progress"),
                    iPoster = c.getColumnIndex("poster"), iIntent = c.getColumnIndex("intent"), iPkg = c.getColumnIndex("package");
            if (iTitle < 0 || iIntent < 0) return list;
            while (c.moveToNext() && list.size() < MAX_ITEMS) {
                Entry e = new Entry();
                e.title = c.getString(iTitle);
                e.subtitle = iSub < 0 ? "" : c.getString(iSub);
                e.progress = iProg < 0 ? 0 : c.getInt(iProg);
                e.intent = c.getString(iIntent);
                e.pkg = iPkg < 0 ? null : c.getString(iPkg);
                String poster = iPoster < 0 ? null : c.getString(iPoster);
                if (poster != null) e.poster = decode(cr, Uri.parse(poster));
                if (e.title != null && e.intent != null) list.add(e);
            }
        } catch (RuntimeException e) {
            // The Reprendre app is not installed, or it failed: no row.
            list.clear();
        }
        return list;
    }

    /** A poster scaled down to about the size it is drawn at. */
    private Bitmap decode(ContentResolver cr, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = Math.max(1, bounds.outWidth / decodeW);
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = cr.openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void apply(List<Entry> list) {
        loading = false;
        boolean same = list.size() == entries.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).title.equals(entries.get(i).title) && list.get(i).progress == entries.get(i).progress
                    && (list.get(i).poster == null) == (entries.get(i).poster == null);
        }
        if (same) return;
        entries = list;
        buildCards();
        if (list.isEmpty()) {
            hide();
            shown = true;   // still on the dock: a later refresh with rows can show it
        } else if (shown) {
            if (fixedHeight > 0) showAt(fixedTop, fixedHeight);
            else show(heroTop, dockTop, topLimit);
        }
        if (onChanged != null) onChanged.run();
    }

    /** Called once the rows have changed (the streaming home lays out again around the new height). */
    void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    /** The cards for the current rows at the current size; focus stays on the same position. */
    private void buildCards() {
        View focused = cards.getFocusedChild();
        int focusedIndex = focused == null ? -1 : cards.indexOfChild(focused);
        cards.removeAllViews();
        for (int i = 0; i < entries.size(); i++) cards.addView(card(entries.get(i), i));
        if (focusedIndex >= 0 && cards.getChildCount() > 0) {
            cards.getChildAt(Math.min(focusedIndex, cards.getChildCount() - 1)).requestFocus();
        }
    }

    private View card(Entry e, int index) {
        int w = cardW, h = posterH;
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setFocusable(true);
        col.setClickable(true);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(px(4), px(4), px(4), px(4));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(w + px(8), LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMarginEnd(px(MARGIN_DP));
        col.setLayoutParams(clp);
        // No frame of its own: the launcher's glass highlight, the one the app tiles use, sits over the focused poster.
        FrameLayout frame = new FrameLayout(activity);
        float radius = AppAdapter.cornerRadius(w, d);
        GradientDrawable placeholder = new GradientDrawable();
        placeholder.setColor(0xFF222222);
        placeholder.setCornerRadius(radius);
        frame.setBackground(placeholder);
        frame.setClipToOutline(true);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (e.poster != null) image.setImageBitmap(e.poster);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        if (e.progress > 0) {
            View bar = new View(activity);
            bar.setBackgroundColor(0xFFE8A33D);
            frame.addView(bar, new FrameLayout.LayoutParams(Math.max(px(3), w * e.progress / 100), px(4),
                    Gravity.BOTTOM | Gravity.START));
        }
        col.addView(frame, new LinearLayout.LayoutParams(w, h));
        TextView title = line(e.title, 15, themeColor(R.attr.homeText), w, px(4));
        TextView subtitle = line(e.subtitle, 12, themeColor(R.attr.homeText) == 0xFFFFFFFF ? 0xFF9AA0A6 : themeColor(R.attr.homeTextMid), w, 0);
        col.addView(title);
        col.addView(subtitle);

        col.setOnFocusChangeListener((v, hasFocus) -> {
            Motion.zoom(frame, hasFocus, animations, reducedMotion, advancedEffects, overshoot, false);
            // The text steps down out of the zoomed poster's way, as an app tile's name does.
            float down = hasFocus && animations ? 8 * d : 0f;
            title.animate().cancel();
            subtitle.animate().cancel();
            if (reducedMotion || !animations) {
                title.setTranslationY(down);
                subtitle.setTranslationY(down);
            } else {
                title.animate().translationY(down).setDuration(220).start();
                subtitle.animate().translationY(down).setDuration(220).start();
            }
            if (hasFocus) {
                focusedCard = v;
                reveal(v);
                notifyFocused(v);
            } else if (focusedCard == v) {
                focusedCard = null;
            }
        });
        col.setOnClickListener(v -> launch(e));
        col.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int last = cards.getChildCount() - 1;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    host.focusDock();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    host.focusTop();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return index == 0;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return index == last;
                default:
                    return false;
            }
        });
        return col;
    }

    /** Tells the launcher where the poster of this card is, in root coordinates and without its zoom. */
    private void notifyFocused(View card) {
        View frame = ((ViewGroup) card).getChildAt(0);
        float cx = box.getX() + scroll.getLeft() - scroll.getScrollX() + cards.getLeft() + card.getLeft()
                + frame.getLeft() + frame.getWidth() / 2f;
        float cy = box.getY() + scroll.getTop() + cards.getTop() + card.getTop() + frame.getTop() + frame.getHeight() / 2f;
        host.onCardFocused(cx, cy, frame.getWidth(), frame.getHeight(), AppAdapter.cornerRadius(frame.getWidth(), d));
    }

    /** Scrolls just enough that the card's poster sits between the grid's first and last tile edges. */
    private void reveal(View card) {
        int base = scroll.getPaddingLeft() - scroll.getScrollX();
        int left = base + card.getLeft() + px(4);
        int right = base + card.getRight() - px(4);
        int dx = right > screenW - inset ? right - (screenW - inset) : left < inset ? left - inset : 0;
        if (dx != 0) scroll.smoothScrollBy(dx, 0);
    }

    private TextView line(String text, int sp, int color, int width, int topPadding) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(px(2), topPadding, px(2), 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private void launch(Entry e) {
        try {
            Intent i = Intent.parseUri(e.intent, Intent.URI_INTENT_SCHEME);
            if (i.getComponent() == null && e.pkg != null) i.setPackage(e.pkg);
            host.open(i);
        } catch (URISyntaxException | RuntimeException ex) {
            Intent l = e.pkg == null ? null : activity.getPackageManager().getLeanbackLaunchIntentForPackage(e.pkg);
            if (l != null) host.open(l);
        }
    }
}
