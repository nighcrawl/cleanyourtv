package tv.reprendre;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Continue watching row: Watch Next rows of the chosen apps, plus Netflix's continueWatching preview rows. */
public class MainActivity extends Activity {
    private static final String TAG = "Reprendre";

        private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private float d;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(px(48), px(40), 0, 0);
        TextView head = text("Continuer la lecture", 30, Color.WHITE);
        root.addView(head);
        List<Items.Item> items = Items.load(this);
        Log.i(TAG, "items=" + items.size());
        if (items.isEmpty()) {
            TextView none = text("Rien à reprendre pour le moment.", 20, 0xFFAAAAAA);
            none.setPadding(0, px(24), 0, 0);
            root.addView(none);
        } else {
            HorizontalScrollView sv = new HorizontalScrollView(this);
            sv.setHorizontalScrollBarEnabled(false);
            sv.setClipToPadding(false);
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, px(28), px(48), px(28));
            sv.addView(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = px(24);
            root.addView(sv, lp);
            View first = null;
            for (Items.Item it : items) {
                View card = card(it);
                if (first == null) first = card;
                row.addView(card);
            }
            if (first != null) first.requestFocus();
        }
        setContentView(root);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setSingleLine(true);
        return t;
    }

    private int px(int dp) { return (int) (dp * d); }

    private View card(Items.Item it) {
        final int w = px(320), h = px(180);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setFocusable(true);
        col.setClickable(true);
        col.setPadding(px(6), px(6), px(6), px(6));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(w + px(12), -2);
        clp.rightMargin = px(18);
        col.setLayoutParams(clp);
        GradientDrawable idle = new GradientDrawable(); idle.setColor(Color.TRANSPARENT); idle.setCornerRadius(px(14));
        GradientDrawable focus = new GradientDrawable(); focus.setColor(0x33FFFFFF); focus.setStroke(px(3), Color.WHITE); focus.setCornerRadius(px(14));
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focus);
        sl.addState(new int[]{}, idle);
        col.setBackground(sl);

        FrameLayout box = new FrameLayout(this);
        GradientDrawable ph = new GradientDrawable(); ph.setColor(0xFF222222); ph.setCornerRadius(px(10));
        box.setBackground(ph);
        box.setClipToOutline(true);
        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (it.dur > 0 && it.pos > 0) {
            View bar = new View(this);
            bar.setBackgroundColor(0xFFE8A33D);
            int bw = (int) (w * Math.min(1f, (float) it.pos / it.dur));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Math.max(bw, px(4)), px(5), Gravity.BOTTOM | Gravity.START);
            box.addView(bar, bl);
        }
        col.addView(box, new LinearLayout.LayoutParams(w, h));
        TextView t = text(it.title, 18, Color.WHITE);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(px(4), px(8), px(4), 0);
        col.addView(t, new LinearLayout.LayoutParams(w, -2));
        TextView s = text(Items.NAMES[it.app] + (it.next ? " · épisode suivant" : " · reprendre"), 14, 0xFF9AA0A6);
        s.setPadding(px(4), 0, px(4), 0);
        col.addView(s, new LinearLayout.LayoutParams(w, -2));

        final Items.Item fi = it;
        col.setOnClickListener(v -> launch(fi));
        if (it.poster != null) pool.execute(() -> {
            Bitmap bm = bitmap(it.poster);
            if (bm != null) runOnUiThread(() -> img.setImageBitmap(bm));
            else Log.i(TAG, "no image for " + it.title);
        });
        return col;
    }

    private Bitmap bitmap(String url) {
        try {
            if (url.startsWith("content:")) {
                try (InputStream in = getContentResolver().openInputStream(Uri.parse(url))) { return BitmapFactory.decodeStream(in); }
            }
            url = url.replace("%7BresolutionXY%7D", "640x360").replace("{resolutionXY}", "640x360");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) { return BitmapFactory.decodeStream(in); }
        } catch (Throwable e) { Log.i(TAG, "image error " + e); return null; }
    }

    private void launch(Items.Item it) {
        try {
            Intent i = Intent.parseUri(it.intent, Intent.URI_INTENT_SCHEME);
            if (i.getComponent() == null) i.setPackage(Items.APPS[it.app]);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable e) {
            Log.i(TAG, "launch failed " + e);
            Intent l = getPackageManager().getLeanbackLaunchIntentForPackage(Items.APPS[it.app]);
            if (l != null) startActivity(l);
        }
    }

}
