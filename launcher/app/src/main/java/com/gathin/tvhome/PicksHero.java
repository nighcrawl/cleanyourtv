package com.gathin.tvhome;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
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
 * The Recommendations banner above the dock (config.xml, picks_hero): one title at a time on a wide picture, with its
 * name, the service that offers it and a summary, changing every few seconds. It starts with what was being watched
 * (the Reprendre app's provider, the same one the Continue watching row reads) and goes on with the recommendations
 * from the TMDB Picks app's provider. Both providers download and cache the pictures, so the launcher keeps no
 * network access and no extra permission. Without those apps installed the banner simply never shows.
 */
final class PicksHero {
    interface Host {
        void focusDock();
        void focusTop();
        void open(Intent intent);
    }

    private static final Uri PICKS = Uri.parse("content://com.gathin.tmdbpicks.items/items");
    private static final Uri CONTINUE = Uri.parse("content://tv.reprendre.items/items");
    private static final int MAX_CONTINUE = 4;
    private static final int MAX_PICKS = 12;
    private static final long REFRESH_MS = 30_000;
    private static final long ROTATE_MS = 8_000;
    private static final int FADE_MS = 600;
    /** Below this height (dp) the banner is not worth showing and stays hidden. */
    private static final int MIN_HEIGHT_DP = 130;

    private static final class Entry {
        String title, subtitle, summary, poster, intent, pkg;
        /** The small heading over the title: what was being watched, or a recommendation. */
        boolean resume;
    }

    private final Activity activity;
    private final Host host;
    private final float d;
    private final int inset, screenW;
    /** streaming home: the banner spans the whole screen from its top edge, with no corners, and lists only recommendations. */
    private final boolean fullBleed;
    /** Whether the Continue watching titles go in the banner too: not on the streaming home, which has its own row. */
    private final boolean includeContinue;
    /** Whether the recommendations go in the banner: off, it shows only what was being watched. */
    private final boolean includePicks;
    /** Height and top the banner is given in full-bleed mode (see {@link #showFixed}). */
    private int fixedTop, fixedHeight;
    private final FrameLayout box;
    private final ImageView lower, upper;
    private final TextView label, title, subtitle, summary;
    private final LinearLayout column;
    private View bottomFade;
    /** Full-bleed mode: the "Watch" button under the summary (the banner's focus target) and the dots, one per title. */
    private TextView watch;
    private LinearLayout dots;
    private View.OnKeyListener keys;
    /** False while the rows are up in the banner's place (streaming home). */
    private boolean shownOver = true;
    private final View texts;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable rotate = this::next;
    private List<Entry> entries = new ArrayList<>();
    private int index;
    /** Bumped for every picture asked for, so a slow decode of an old one is dropped. */
    private int request;
    private long lastLoad = -REFRESH_MS;
    private boolean loading, shown, fits;
    private float heroTop, topLimit;
    /** Width the title and summary lines break at: the text column less its paddings. */
    private int textWidth;

    PicksHero(Activity activity, FrameLayout root, float density, Host host) {
        this(activity, root, density, host, false, true);
    }

    PicksHero(Activity activity, FrameLayout root, float density, Host host, boolean fullBleed) {
        this(activity, root, density, host, fullBleed, !fullBleed);
    }

    PicksHero(Activity activity, FrameLayout root, float density, Host host, boolean fullBleed, boolean includeContinue) {
        this(activity, root, density, host, fullBleed, includeContinue, true);
    }

    PicksHero(Activity activity, FrameLayout root, float density, Host host, boolean fullBleed, boolean includeContinue,
              boolean includePicks) {
        this.fullBleed = fullBleed;
        this.includeContinue = includeContinue;
        this.includePicks = includePicks;
        this.activity = activity;
        this.host = host;
        this.d = density;
        screenW = activity.getResources().getDisplayMetrics().widthPixels;
        // The same inset as the grid and the Continue watching row: the banner lines up with them.
        int gridInset = Math.round(screenW * 0.0805f);
        inset = fullBleed ? 0 : gridInset;
        box = new FrameLayout(activity);
        box.setVisibility(View.INVISIBLE);
        box.setFocusable(!fullBleed);
        box.setClickable(!fullBleed);
        float radius = fullBleed ? 0 : 16 * d;
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        box.setClipToOutline(true);
        box.setBackgroundColor(0xFF1A1A1A);
        lower = picture();
        upper = picture();
        upper.setAlpha(0f);
        // The picture sits on the right and fades into the banner's background on its left edge; the text is on the left.
        FadeEdgeLayout art = new FadeEdgeLayout(activity, R.drawable.banner_mask);
        art.addView(lower);
        art.addView(upper);
        box.addView(art, new FrameLayout.LayoutParams((int) ((screenW - 2 * inset) * 0.68f), -1, Gravity.END));
        // Dark from the left, where the text sits, and from the bottom: the text reads on any picture.
        View scrim = new View(activity);
        GradientDrawable side = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{fullBleed ? 0x99000000 : 0xE6000000, fullBleed ? 0x40000000 : 0x66000000, 0x00000000});
        scrim.setBackground(side);
        box.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        if (fullBleed) {
            // The clock and buttons are drawn over the banner's top, the Continue watching row starts under its bottom.
            View top = new View(activity);
            top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x00000000}));
            box.addView(top, new FrameLayout.LayoutParams(-1, px(150), Gravity.TOP));
            // Fades to black from the text down, so the text and the dock drawn over the picture stay readable.
            bottomFade = new View(activity);
            bottomFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000}));
            box.addView(bottomFade, new FrameLayout.LayoutParams(-1, px(90), Gravity.BOTTOM));
        }

        column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(fullBleed ? gridInset : px(32), px(16), px(32), px(16));
        label = new TextView(activity);
        label.setTextColor(0xFFB8C7D9);
        label.setTextSize(13);
        label.setLetterSpacing(0.12f);
        label.setAllCaps(true);
        label.setTypeface(UiFont.get(activity, R.string.font_medium));
        column.addView(label);
        // The text group is what fades between titles; the label above stays.
        LinearLayout group = new LinearLayout(activity);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setGravity(Gravity.BOTTOM);
        title = text(28, Color.WHITE, 2);
        title.setTypeface(UiFont.get(activity, R.string.font_medium));
        subtitle = text(16, 0xFFD6DEE8, 1);
        subtitle.setPadding(0, px(2), 0, px(6));
        summary = text(15, 0xFFB8C7D9, 2);
        group.addView(title);
        group.addView(subtitle);
        group.addView(summary);
        column.addView(group, new LinearLayout.LayoutParams(-1, 0, 1));
        texts = group;
        if (fullBleed) {
            watch = new TextView(activity);
            watch.setText(R.string.picks_watch);
            watch.setTextSize(17);
            watch.setTypeface(UiFont.get(activity, R.string.font_medium));
            watch.setGravity(Gravity.CENTER);
            watch.setPadding(px(28), px(10), px(28), px(10));
            watch.setFocusable(true);
            watch.setClickable(true);
            watch.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFF101010, Color.WHITE}));
            GradientDrawable idle = new GradientDrawable();
            idle.setCornerRadius(px(24));
            idle.setColor(0x40FFFFFF);
            GradientDrawable lit = new GradientDrawable();
            lit.setCornerRadius(px(24));
            lit.setColor(Color.WHITE);
            StateListDrawable look = new StateListDrawable();
            look.addState(new int[]{android.R.attr.state_focused}, lit);
            look.addState(new int[]{}, idle);
            watch.setBackground(look);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
            wp.topMargin = px(16);
            group.addView(watch, wp);
            dots = new LinearLayout(activity);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2);
            dp.topMargin = px(26);
            group.addView(dots, dp);
        }
        if (fullBleed) {
            // The heading goes with the text at the bottom, not alone at the top under the clock.
            column.removeView(label);
            group.addView(label, 0);
        }
        // The text takes the left 55%: the right of the picture stays clear.
        box.addView(column, new FrameLayout.LayoutParams((int) ((screenW - 2 * inset) * (fullBleed ? 0.5f : 0.55f)), -1, Gravity.START));

        // A white outline while focused.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(px(3), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, ring);
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        if (!fullBleed) box.setForeground(states);   // streaming home: the "Watch" button shows the focus instead

        box.setOnClickListener(v -> launch());
        keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    host.focusDock();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    host.focusTop();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    step(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    step(1);
                    return true;
                default:
                    return false;
            }
        };
        if (fullBleed) {
            watch.setOnClickListener(v -> launch());
            watch.setOnKeyListener(keys);
        } else {
            box.setOnKeyListener(keys);
        }
        root.addView(box, new FrameLayout.LayoutParams(screenW - 2 * inset, px(MIN_HEIGHT_DP), Gravity.TOP | Gravity.START));
        box.setX(inset);
        if (fullBleed) column.setPadding(gridInset, px(16), px(32), px(16));
        textWidth = (int) ((screenW - 2 * inset) * (fullBleed ? 0.5f : 0.55f)) - (fullBleed ? gridInset : px(32)) - px(32);
    }

    private ImageView picture() {
        ImageView view = new ImageView(activity);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        return view;
    }

    private TextView text(int sp, int color, int lines) {
        TextView t = new TextView(activity);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private int px(int dp) {
        return (int) (dp * d);
    }

    /** Whether v is the banner. */
    boolean owns(View v) {
        return v == box || v == watch;
    }

    /** Moves focus to the banner; false when it is not on screen, so the caller keeps its own Up behaviour. */
    boolean focus() {
        return shown && fits && !entries.isEmpty() && box.getVisibility() == View.VISIBLE
                && (fullBleed ? watch.requestFocus() : box.requestFocus());
    }

    /**
     * Shows the banner just above the top shelf (the dock app's name), whose top edge is heroTopY in the root, and
     * below the top bar, which ends at topLimitY. Its height is what is left, up to 9:32 of its width; too little
     * room keeps it hidden.
     */
    void show(float heroTopY, float topLimitY) {
        shown = true;
        heroTop = heroTopY;
        topLimit = topLimitY;
        layout();
    }

    /** Full-bleed mode: the banner occupies exactly this part of the screen, whatever has focus. Its text sits at the bottom. */
    void showFixed(int topPx, int heightPx, int textBottomPx) {
        shown = true;
        fixedTop = topPx;
        fixedHeight = heightPx;
        // The text sits just above this edge (the dock's top), clear of the dock drawn over the picture.
        column.setPadding(column.getPaddingLeft(), px(16), column.getPaddingRight(), textBottomPx);
        if (bottomFade != null && bottomFade.getLayoutParams().height != heightPx * 2 / 5) {
            bottomFade.getLayoutParams().height = heightPx * 2 / 5;   // the lower 40%, behind the dock and the text
            bottomFade.requestLayout();
        }
        layout();
    }

    /** The same as {@link #showFixed}, but a banner that is hidden stays hidden: the classic home hides it off the dock row. */
    void placeFixed(int topPx, int heightPx, int textBottomPx) {
        boolean wasShown = shown;
        showFixed(topPx, heightPx, textBottomPx);
        if (!wasShown) hide();
    }

    /** The picture and its text go while the rows are up in their place, and come back with the banner. */
    void setShownOver(boolean show) {
        shownOver = show;
        if (!fits || box.getVisibility() != View.VISIBLE) return;
        box.animate().alpha(show ? 1f : 0f).setDuration(200).start();
    }

    /** The banner's view, so the streaming home can order it under the clock and buttons. */
    View view() {
        return box;
    }

    private void layout() {
        if (entries.isEmpty()) return;
        float bottom = fullBleed ? fixedTop + fixedHeight : heroTop - 6 * d;
        int width = screenW - 2 * inset;
        int height = fullBleed ? fixedHeight : (int) Math.min(width * 9f / 32f, bottom - topLimit);
        fits = fullBleed ? height > 0 : height >= px(MIN_HEIGHT_DP);
        if (!fits) {
            box.animate().cancel();
            box.setAlpha(0f);
            box.setVisibility(View.INVISIBLE);
            main.removeCallbacks(rotate);
            return;
        }
        if (box.getLayoutParams().height != height) {
            box.getLayoutParams().height = height;
            box.requestLayout();
        }
        box.setY(bottom - height);
        if (box.getVisibility() != View.VISIBLE) {
            box.setAlpha(0f);
            box.setVisibility(View.VISIBLE);
            box.animate().alpha(shownOver ? 1f : 0f).setDuration(200).start();
            display(true);
        }
        schedule();
    }

    void hide() {
        shown = false;
        main.removeCallbacks(rotate);
        box.animate().cancel();
        box.setAlpha(0f);
        box.setVisibility(View.INVISIBLE);
    }

    void close() {
        main.removeCallbacks(rotate);
        io.shutdownNow();
    }

    /** Reads the titles again, at most every 30 seconds. */
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
        if (includeContinue) read(CONTINUE, true, MAX_CONTINUE, list);
        if (includePicks) read(PICKS, false, MAX_PICKS, list);
        return list;
    }

    private void read(Uri uri, boolean resume, int max, List<Entry> list) {
        int start = list.size();
        try (Cursor c = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (c == null) return;
            int iTitle = c.getColumnIndex("title"), iSub = c.getColumnIndex("subtitle"), iSum = c.getColumnIndex("summary"),
                    iPoster = c.getColumnIndex("poster"), iIntent = c.getColumnIndex("intent"), iPkg = c.getColumnIndex("package");
            if (iTitle < 0 || iIntent < 0 || iPoster < 0) return;
            while (c.moveToNext() && list.size() - start < max) {
                Entry e = new Entry();
                e.resume = resume;
                e.title = c.getString(iTitle);
                e.subtitle = iSub < 0 ? "" : c.getString(iSub);
                e.summary = iSum < 0 ? "" : c.getString(iSum);
                e.poster = c.getString(iPoster);
                e.intent = c.getString(iIntent);
                e.pkg = iPkg < 0 ? null : c.getString(iPkg);
                if (e.title != null && e.intent != null && e.poster != null) list.add(e);
            }
        } catch (RuntimeException e) {
            // That app is not installed, or it failed: its titles are missing, the others still show.
            while (list.size() > start) list.remove(list.size() - 1);
        }
    }

    private void apply(List<Entry> list) {
        loading = false;
        boolean same = list.size() == entries.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).title.equals(entries.get(i).title) && list.get(i).resume == entries.get(i).resume;
        }
        if (same) return;
        entries = list;
        index = 0;
        if (list.isEmpty()) {
            hide();
            shown = true;   // still on the dock: a later refresh with titles can show it
        } else if (shown) {
            box.setVisibility(View.INVISIBLE);   // laid out afresh, with the first title
            layout();
        }
    }

    // ---- Changing title ----------------------------------------------------------------------------------------

    private void schedule() {
        main.removeCallbacks(rotate);
        // The streaming banner changes only when asked to (Left and Right, with the dots showing where it is).
        if (!fullBleed && entries.size() > 1 && shown && fits) main.postDelayed(rotate, ROTATE_MS);
    }

    private void next() {
        step(1);
    }

    private void step(int by) {
        if (entries.isEmpty()) return;
        index = (index + by + entries.size()) % entries.size();
        display(false);
        schedule();
    }

    /**
     * Fills the title, subtitle and summary. The text is anchored at the bottom, so a title on two lines would push the
     * heading under the clock: when the subtitle fits after the title's last line it goes there, which keeps the summary's
     * two lines; when it does not, the summary gives up one line instead.
     */
    private void showTexts(Entry e) {
        String name = e.title == null ? "" : e.title;
        String sub = e.subtitle == null ? "" : e.subtitle;
        title.setText(name);
        subtitle.setText(sub);
        subtitle.setVisibility(View.VISIBLE);
        summary.setText(e.summary);
        summary.setMaxLines(2);
        if (lineCount(name) < 2) return;
        if (!sub.isEmpty()) {
            SpannableStringBuilder both = new SpannableStringBuilder(name).append("   ");
            int from = both.length();
            both.append(sub);
            // Looks like the subtitle on its own line: its size, colour and typeface rather than the title's.
            both.setSpan(new FontSpan(subtitle.getTypeface()), from, both.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            both.setSpan(new AbsoluteSizeSpan(16, true), from, both.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            both.setSpan(new ForegroundColorSpan(0xFFD6DEE8), from, both.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (lineCount(both) <= 2) {
                title.setText(both);
                subtitle.setVisibility(View.GONE);
                return;
            }
        }
        summary.setMaxLines(1);
    }

    /** A typeface for part of a text (TypefaceSpan only takes a Typeface from API 28). */
    private static final class FontSpan extends MetricAffectingSpan {
        private final Typeface face;

        FontSpan(Typeface face) {
            this.face = face;
        }

        @Override public void updateDrawState(TextPaint paint) {
            paint.setTypeface(face);
        }

        @Override public void updateMeasureState(TextPaint paint) {
            paint.setTypeface(face);
        }
    }

    private int lineCount(CharSequence text) {
        if (text.length() == 0) return 1;
        return StaticLayout.Builder.obtain(text, 0, text.length(), title.getPaint(), textWidth).setMaxLines(3).build().getLineCount();
    }

    /** Puts the title at {@link #index} on screen: its picture is decoded off the main thread, then faded in. */
    private void display(boolean first) {
        if (entries.isEmpty()) return;
        updateDots();
        Entry e = entries.get(index);
        int mine = ++request;
        int wide = screenW - 2 * inset;
        io.execute(() -> {
            Bitmap bitmap = decode(Uri.parse(e.poster), wide);
            if (activity.isDestroyed()) return;
            activity.runOnUiThread(() -> {
                if (mine != request) return;
                label.setText(e.resume ? R.string.continue_row_title : R.string.picks_hero_title);
                showTexts(e);
                summary.setVisibility(e.summary == null || e.summary.isEmpty() ? View.GONE : View.VISIBLE);
                texts.setAlpha(0f);
                texts.animate().alpha(1f).setDuration(FADE_MS).start();
                if (bitmap == null) return;
                if (first) {
                    lower.setImageBitmap(bitmap);
                    return;
                }
                upper.animate().cancel();
                upper.setImageBitmap(bitmap);
                upper.setAlpha(0f);
                upper.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
                    lower.setImageBitmap(bitmap);
                    upper.setAlpha(0f);
                }).start();
            });
        });
    }

    /** A picture scaled down to about the size it is drawn at, in half the memory (RGB_565). */
    private Bitmap decode(Uri uri, int targetW) {
        ContentResolver cr = activity.getContentResolver();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = Math.max(1, bounds.outWidth / targetW);
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = cr.openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** One dot per title, the current one lit. */
    private void updateDots() {
        if (dots == null) return;
        if (dots.getChildCount() != entries.size()) {
            dots.removeAllViews();
            for (int i = 0; i < entries.size(); i++) {
                View dot = new View(activity);
                GradientDrawable shape = new GradientDrawable();
                shape.setShape(GradientDrawable.OVAL);
                dot.setBackground(shape);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(8), px(8));
                lp.setMarginEnd(px(8));
                dots.addView(dot, lp);
            }
        }
        for (int i = 0; i < dots.getChildCount(); i++) {
            ((GradientDrawable) dots.getChildAt(i).getBackground()).setColor(i == index ? Color.WHITE : 0x66FFFFFF);
        }
    }

    private void launch() {
        if (entries.isEmpty()) return;
        Entry e = entries.get(index);
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
