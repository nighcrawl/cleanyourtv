package tv.reprendre;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gives the launcher the Continue watching row without giving the launcher network access: rows from
 * /items, posters from /poster?u=<url>, downloaded here once and cached on disk. Only poster addresses
 * from the latest /items answer are served, so the provider cannot be used to fetch or read anything else.
 */
public class ItemsProvider extends ContentProvider {
    private static final String TAG = "Reprendre";
    static final String AUTHORITY = "tv.reprendre.items";
    private static final Set<String> allowed = new HashSet<>();

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{"_id", "title", "subtitle", "progress", "poster", "intent", "package"});
        if (!"items".equals(uri.getLastPathSegment())) return c;
        List<Items.Item> items = Items.load(getContext());
        Set<String> fresh = new HashSet<>();
        int id = 0;
        for (Items.Item it : items) {
            String poster = null;
            if (it.poster != null) { fresh.add(it.poster); poster = "content://" + AUTHORITY + "/poster?u=" + Uri.encode(it.poster); }
            int progress = it.dur > 0 && it.pos > 0 ? (int) Math.min(100, it.pos * 100 / it.dur) : 0;
            c.addRow(new Object[]{id++, it.title, Items.NAMES[it.app] + (it.next ? " · épisode suivant" : " · reprendre"),
                    progress, poster, it.intent, Items.APPS[it.app]});
        }
        synchronized (allowed) { allowed.clear(); allowed.addAll(fresh); }
        prune(fresh);
        return c;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"poster".equals(uri.getLastPathSegment())) throw new FileNotFoundException();
        String u = uri.getQueryParameter("u");
        synchronized (allowed) { if (u == null || !allowed.contains(u)) throw new FileNotFoundException("not listed"); }
        File f = cacheFile(u);
        if (!f.exists() || f.length() == 0) download(u, f);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File cacheFile(String url) {
        File dir = new File(getContext().getCacheDir(), "posters");
        dir.mkdirs();
        return new File(dir, hash(url));
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    private void download(String url, File out) throws FileNotFoundException {
        File tmp = new File(out.getPath() + ".tmp");
        try {
            InputStream in;
            if (url.startsWith("content:")) {
                in = getContext().getContentResolver().openInputStream(Uri.parse(url));
            } else if (url.startsWith("https://")) {
                url = url.replace("%7BresolutionXY%7D", "640x360").replace("{resolutionXY}", "640x360");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                in = c.getInputStream();
            } else throw new FileNotFoundException("scheme");
            try (InputStream i = in; FileOutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384]; int n;
                while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            }
            if (!tmp.renameTo(out)) throw new FileNotFoundException("rename");
        } catch (FileNotFoundException e) { throw e;
        } catch (Throwable e) {
            Log.i(TAG, "poster error " + e);
            tmp.delete();
            throw new FileNotFoundException(String.valueOf(e));
        }
    }

    /** Drops cached posters of rows that are no longer in the list. */
    private void prune(Set<String> keep) {
        File dir = new File(getContext().getCacheDir(), "posters");
        File[] files = dir.listFiles();
        if (files == null) return;
        Set<String> names = new HashSet<>();
        for (String u : keep) names.add(hash(u));
        for (File f : files) if (!names.contains(f.getName())) f.delete();
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
