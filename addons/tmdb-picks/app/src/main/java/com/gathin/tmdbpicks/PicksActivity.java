package com.gathin.tmdbpicks;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The recommendations full screen, one row of posters per installed service (see {@link Picks}), from the same list the
 * launcher's row reads. Without a key the screen shows a QR code that opens a page on the TV (see {@link KeyServer})
 * where the phone pastes it; the key is kept in the app's private preferences.
 */
public final class PicksActivity extends Activity {
    private static final int ACCENT = 0xFF01B4E4;
    private static final int BACKGROUND = 0xFF0D253F;

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    // Posters are 185 px wide in RGB_565, about 100 KB each: a hundred and some fit in 12 MB.
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    private FrameLayout root;
    private KeyServer server;
    private boolean resumed;
    /** Bumped when the screen changes, so late answers for the old one are dropped. */
    private int generation;
    private TextView detailTitle;
    private TextView detailText;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (Picks.key(this) == null) showSetup();
        else showPicks();
    }

    @Override
    protected void onPause() {
        resumed = false;
        generation++;
        stopServer();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ---- Pairing -----------------------------------------------------------------------------------------------

    private void showSetup() {
        int gen = ++generation;
        stopServer();
        root.removeAllViews();
        LinearLayout column = column(Gravity.CENTER);
        TextView heading = text(getString(R.string.setup_title), 30, Color.WHITE);
        heading.setGravity(Gravity.CENTER);
        column.addView(heading);
        TextView hint = text(getString(R.string.setup_hint), 18, 0xFFB8C7D9);
        hint.setGravity(Gravity.CENTER);
        hint.setMaxWidth(dp(640));
        hint.setPadding(0, dp(12), 0, dp(20));
        column.addView(hint);
        QrView qr = new QrView(this);
        qr.setVisibility(View.INVISIBLE);
        column.addView(qr, new LinearLayout.LayoutParams(dp(260), dp(260)));
        TextView url = text(getString(R.string.setup_waiting), 18, ACCENT);
        url.setGravity(Gravity.CENTER);
        url.setPadding(0, dp(16), 0, 0);
        column.addView(url);
        column.setPadding(dp(48), 0, dp(48), 0);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        io.execute(() -> {
            String ip = localAddress();
            if (ip == null) {
                main.post(() -> url.setText(R.string.setup_no_network));
                return;
            }
            String token = token();
            KeyServer.Text words = new KeyServer.Text(getString(R.string.web_title), getString(R.string.web_label),
                    getString(R.string.web_button), getString(R.string.web_saved), getString(R.string.web_invalid));
            KeyServer candidate = new KeyServer(token, words, key -> {
                if (!Tmdb.accepts(key)) return false;
                Picks.setKey(this, key);
                main.post(() -> {
                    if (resumed && gen == generation) onResume();
                });
                return true;
            });
            try {
                int port = candidate.start();
                String address = "http://" + ip + ":" + port + "/?t=" + token;
                main.post(() -> {
                    if (!resumed || gen != generation) {
                        candidate.stop();
                        return;
                    }
                    server = candidate;
                    qr.setVisibility(qr.setText(address) ? View.VISIBLE : View.INVISIBLE);
                    url.setText("http://" + ip + ":" + port);
                });
            } catch (java.io.IOException e) {
                main.post(() -> url.setText(R.string.setup_no_network));
            }
        });
    }

    private void stopServer() {
        if (server != null) server.stop();
        server = null;
    }

    private static String token() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    /** The TV's address on the home network, or null when it has none. */
    private static String localAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (java.net.SocketException ignored) {
        }
        return null;
    }

    // ---- Posters -----------------------------------------------------------------------------------------------

    private void showPicks() {
        int gen = ++generation;
        stopServer();
        root.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(48), dp(24), dp(48), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.app_name), 28, Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView change = text(getString(R.string.change_key), 16, Color.WHITE);
        change.setPadding(dp(16), dp(8), dp(16), dp(8));
        change.setFocusable(true);
        focusRing(change, 8);
        change.setOnClickListener(v -> {
            Picks.clearKey(this);
            showSetup();
        });
        header.addView(change);
        page.addView(header);

        status = text(getString(R.string.loading), 18, 0xFFB8C7D9);
        page.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        scroll.addView(rows);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        detailTitle = text("", 20, Color.WHITE);
        detailText = text("", 16, 0xFFB8C7D9);
        detailText.setMaxLines(3);
        detailText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        page.addView(detailTitle);
        page.addView(detailText);
        TextView credit = text(getString(R.string.attribution), 12, 0xFF7C93AD);
        credit.setPadding(0, dp(6), 0, 0);
        page.addView(credit);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        String language = Locale.getDefault().toLanguageTag();
        boolean[] installed = Picks.installed(this);
        boolean any = false;
        for (boolean b : installed) any |= b;
        if (!any) {
            status.setText(R.string.no_apps);
            return;
        }
        List<Title> shown = Picks.cached(this);
        render(rows, shown);
        io.execute(() -> {
            try {
                List<Title> fresh = Picks.refreshIfStale(this, installed, language);
                main.post(() -> {
                    if (gen == generation && !fresh.equals(shown)) render(rows, fresh);
                });
            } catch (Tmdb.KeyRefused e) {
                main.post(() -> {
                    if (gen != generation) return;
                    Picks.clearKey(this);
                    showSetup();
                });
            } catch (java.io.IOException e) {
                main.post(() -> {
                    if (gen == generation && rows.getChildCount() == 0) status.setText(R.string.offline);
                });
            }
        });
    }

    /** One row per service that has titles, in the order of {@link Services}. */
    private void render(LinearLayout rows, List<Title> titles) {
        rows.removeAllViews();
        if (titles.isEmpty()) return;
        status.setVisibility(View.GONE);
        for (int s = 0; s < Services.NAMES.length; s++) {
            List<Title> mine = new java.util.ArrayList<>();
            for (Title t : titles) if (t.service == s) mine.add(t);
            if (!mine.isEmpty()) addRow(rows, Services.NAMES[s], mine);
        }
        if (rows.getChildCount() > 0) {
            ViewGroup first = (ViewGroup) ((ViewGroup) ((ViewGroup) rows.getChildAt(0)).getChildAt(1)).getChildAt(0);
            first.getChildAt(0).requestFocus();
        }
    }

    private void addRow(LinearLayout rows, String label, List<Title> titles) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setClipChildren(false);
        TextView name = text(label, 20, Color.WHITE);
        name.setPadding(0, dp(16), 0, dp(8));
        block.addView(name);
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setClipChildren(false);
        strip.setPadding(dp(20), dp(14), dp(20), dp(14));
        scroller.addView(strip);
        block.addView(scroller);
        for (Title title : titles) strip.addView(poster(title));
        rows.addView(block);
    }

    private View poster(Title title) {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setBackgroundColor(0xFF1B3A5C);
        view.setFocusable(true);
        view.setClickable(true);
        view.setContentDescription(title.name);
        focusRing(view, 4);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(120), dp(180));
        params.rightMargin = dp(12);
        view.setLayoutParams(params);
        view.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.1f : 1f).scaleY(hasFocus ? 1.1f : 1f).setDuration(120).start();
            if (hasFocus) showDetail(title);
        });
        load(view, title.poster);
        return view;
    }

    private void showDetail(Title title) {
        StringBuilder head = new StringBuilder(title.name);
        if (title.year > 0) head.append(" (").append(title.year).append(')');
        if (title.rating > 0) head.append("   ★ ").append(String.format(Locale.ROOT, "%.1f", title.rating));
        detailTitle.setText(head);
        detailText.setText(title.overview);
    }

    private void load(ImageView view, String path) {
        if (path == null) return;
        Bitmap cached = posters.get(path);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        int gen = generation;
        io.execute(() -> {
            Bitmap bitmap = Tmdb.poster(path);
            if (bitmap == null) return;
            posters.put(path, bitmap);
            main.post(() -> {
                if (gen == generation) view.setImageBitmap(bitmap);
            });
        });
    }

    // ---- Small helpers -----------------------------------------------------------------------------------------

    private LinearLayout column(int gravity) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(gravity);
        return column;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    /** A white outline while the view has focus: the foreground is clear otherwise. */
    private void focusRing(View view, int radiusDp) {
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(radiusDp));
        ring.setStroke(dp(3), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, ring);
        states.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        view.setForeground(states);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
