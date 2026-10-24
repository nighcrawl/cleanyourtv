package tv.reprendre;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Continue watching rows: Watch Next rows of the chosen apps, plus Netflix's continueWatching preview rows. */
final class Items {
    private static final String TAG = "Reprendre";
    static final String[] APPS = {"com.amazon.amazonvideo.livingroom", "com.disney.disneyplus",
            "com.canal.android.canal", "com.netflix.ninja", "org.jellyfin.androidtv"};
    static final String[] NAMES = {"Prime Video", "Disney+", "Canal+", "Netflix", "Jellyfin"};
    private static final int PER_APP = 10;

    static class Item {
        int app, seq; String title, poster, intent; long pos, dur, engaged; boolean next;
    }

    private Items() {}

    private static String s(Cursor c, String col) { int i = c.getColumnIndex(col); return i < 0 ? null : c.getString(i); }
    private static long l(Cursor c, String col) { String v = s(c, col); try { return v == null ? 0 : Long.parseLong(v); } catch (Exception e) { return 0; } }
    private static int appOf(String pkg) { for (int i = 0; i < APPS.length; i++) if (APPS[i].equals(pkg)) return i; return -1; }

    static List<Item> load(Context ctx) {
        List<Item> all = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(Uri.parse("content://android.media.tv/watch_next_program"), null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                int a = appOf(s(c, "package_name"));
                if (a < 0) continue;
                long type = l(c, "watch_next_type");
                if (type > 1) continue;
                Item it = new Item();
                it.app = a; it.title = s(c, "title"); it.poster = s(c, "poster_art_uri"); it.intent = s(c, "intent_uri");
                it.pos = l(c, "last_playback_position_millis"); it.dur = l(c, "duration_millis");
                it.engaged = l(c, "last_engagement_time_utc_millis"); it.next = type == 1;
                if (it.title != null && it.intent != null) all.add(it);
            }
        } catch (Throwable e) { Log.i(TAG, "watch_next error " + e); }
        try (Cursor c = ctx.getContentResolver().query(Uri.parse("content://android.media.tv/preview_program"), null, null, null, null)) {
            int n = 0;
            while (c != null && c.moveToNext()) {
                if (!"com.netflix.ninja".equals(s(c, "package_name"))) continue;
                String in = s(c, "intent_uri");
                if (in == null || !in.contains("listContext%3DcontinueWatching")) continue;
                Item it = new Item();
                it.app = appOf("com.netflix.ninja"); it.title = s(c, "title"); it.poster = s(c, "poster_art_uri"); it.intent = in;
                it.seq = n++;
                if (it.title != null) all.add(it);
            }
        } catch (Throwable e) { Log.i(TAG, "preview error " + e); }
        Collections.sort(all, new Comparator<Item>() {
            @Override public int compare(Item x, Item y) {
                // Most recent playback first; Netflix publishes no date, so its rows (engaged 0) come last in its own order.
                if (x.engaged != y.engaged) return Long.compare(y.engaged, x.engaged);
                return x.seq - y.seq;
            }
        });
        List<Item> out = new ArrayList<>();
        int[] count = new int[APPS.length];
        for (Item it : all) if (count[it.app]++ < PER_APP) out.add(it);
        return out;
    }
}
