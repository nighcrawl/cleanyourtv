package com.gathin.tvhome;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
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
    }

    private static final Uri ITEMS = Uri.parse("content://tv.reprendre.items/items");
    private static final int MAX_ITEMS = 24;
    private static final long REFRESH_MS = 30_000;
    private static final int HEIGHT_DP = 192;
    private static final int CARD_H_DP = 118;
    /** Cards that fill the grid's width at once; the row scrolls for the rest. */
    private static final int GROUP = 3;
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
    /** Poster width in px: GROUP posters and their gaps fill the width between the grid's first and last tile. */
    private final int cardW;
    /** Distance of the grid's first and last tiles from the screen edges, and the screen width, in px. */
    private final int inset, screenW;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private List<Entry> entries = new ArrayList<>();
    private long lastLoad = -REFRESH_MS;
    private boolean loading;
    private boolean shown;
    private float heroTop;

    ContinueRow(Activity activity, FrameLayout root, float density, Host host) {
        this.activity = activity;
        this.host = host;
        this.d = density;
        box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setVisibility(View.INVISIBLE);
        TextView heading = new TextView(activity);
        heading.setText(R.string.continue_row_title);
        heading.setTextColor(0xB3FFFFFF);
        heading.setTextSize(15);
        heading.setSingleLine(true);
        heading.setTypeface(UiFont.get(activity, R.string.font_medium));
        heading.setLetterSpacing(0.08f);
        // The grid's first and last tiles sit this far from the screen edges (about 8% of the width); the row lines up with them.
        screenW = activity.getResources().getDisplayMetrics().widthPixels;
        inset = Math.round(screenW * 0.0805f);
        cardW = (screenW - 2 * inset - (GROUP - 1) * px(GAP_DP)) / GROUP;
        heading.setPadding(inset, 0, 0, 0);
        box.addView(heading);
        // The default scroll puts a focused card flush against the screen edge; reveal() keeps it inside the inset instead.
        scroll = new HorizontalScrollView(activity) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return 0;
            }
        };
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);   // the row spans the whole screen: cards scroll out at its edges, not at the left margin
        // A card sits 4dp inside its focus frame and keeps a small margin after it: with these paddings the first card
        // starts at the inset and, scrolled to the end, the last one ends at the inset from the right edge.
        scroll.setPadding(inset - px(4), px(2), inset - px(MARGIN_DP + 4), px(2));
        cards = new LinearLayout(activity);
        cards.setClipChildren(false);
        scroll.addView(cards);
        box.addView(scroll);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(HEIGHT_DP),
                Gravity.TOP | Gravity.START);
        
        root.addView(box, lp);
    }

    private int px(int dp) {
        return (int) (dp * d);
    }

    boolean hasItems() {
        return !entries.isEmpty();
    }

    /** Moves focus into the row; false when it is not on screen, so the caller keeps its own Up behaviour. */
    boolean focusFirst() {
        if (!shown || entries.isEmpty() || cards.getChildCount() == 0 || box.getVisibility() != View.VISIBLE) return false;
        return cards.getChildAt(0).requestFocus();
    }

    /** Shows the row just above the top shelf, whose top edge is heroTopY in the root. */
    void show(float heroTopY) {
        shown = true;
        heroTop = heroTopY;
        if (entries.isEmpty()) return;
        box.setY(heroTopY - 6 * d - px(HEIGHT_DP));
        if (box.getVisibility() != View.VISIBLE) {
            box.setAlpha(0f);
            box.setVisibility(View.VISIBLE);
            box.animate().alpha(1f).setDuration(200).start();
        }
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
            o.inSampleSize = Math.max(1, bounds.outWidth / (cardW * 2));
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
        View focused = cards.getFocusedChild();
        int focusedIndex = focused == null ? -1 : cards.indexOfChild(focused);
        entries = list;
        cards.removeAllViews();
        for (int i = 0; i < list.size(); i++) cards.addView(card(list.get(i), i));
        if (list.isEmpty()) {
            hide();
            shown = true;   // still on the dock: a later refresh with rows can show it
        } else if (shown) {
            show(heroTop);
            if (focusedIndex >= 0) cards.getChildAt(Math.min(focusedIndex, list.size() - 1)).requestFocus();
        }
    }

    private View card(Entry e, int index) {
        int w = cardW, h = px(CARD_H_DP);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setFocusable(true);
        col.setClickable(true);
        col.setPadding(px(4), px(4), px(4), px(4));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(w + px(8), LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMarginEnd(px(MARGIN_DP));
        col.setLayoutParams(clp);
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(Color.TRANSPARENT);
        idle.setCornerRadius(px(12));
        GradientDrawable focus = new GradientDrawable();
        focus.setColor(0x26FFFFFF);
        focus.setStroke(px(2), Color.WHITE);
        focus.setCornerRadius(px(12));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focus);
        states.addState(new int[]{}, idle);
        col.setBackground(states);

        FrameLayout frame = new FrameLayout(activity);
        GradientDrawable placeholder = new GradientDrawable();
        placeholder.setColor(0xFF222222);
        placeholder.setCornerRadius(px(8));
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
        col.addView(line(e.title, 15, 0xFFFFFFFF, w, px(4)));
        col.addView(line(e.subtitle, 12, 0xFF9AA0A6, w, 0));

        col.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) reveal(v);
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
