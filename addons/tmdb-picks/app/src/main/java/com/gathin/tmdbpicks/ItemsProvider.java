package com.gathin.tmdbpicks;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gives the launcher's recommendations banner its titles without giving the launcher network access, in the same shape
 * as the Continue watching provider (plus a summary column): rows from /items, 16:9 pictures from /poster?u=<url>, downloaded here once and
 * cached on disk. Only addresses of the latest list are served, so the provider cannot be used to fetch or read
 * anything else. It never hands out the TMDB key.
 */
public final class ItemsProvider extends ContentProvider {
    static final String AUTHORITY = "com.gathin.tmdbpicks.items";
    private static final String LAUNCH = "intent:#Intent;action=android.intent.action.MAIN;"
            + "category=android.intent.category.LEANBACK_LAUNCHER;end";
    private static final ExecutorService refresher = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean refreshing = new AtomicBoolean();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{"_id", "title", "subtitle", "progress", "poster", "intent", "package", "summary"});
        if (!"items".equals(uri.getLastPathSegment())) return c;
        refreshInBackground();
        boolean[] installed = Picks.installed(getContext());
        Set<String> keep = new HashSet<>();
        int id = 0;
        for (Title t : Picks.forHome(Picks.cached(getContext()))) {
            if (!installed[t.service]) continue;
            String url = Tmdb.BACKDROPS + t.backdrop;
            keep.add(url);
            String kind = getContext().getString(t.movie ? R.string.kind_movie : R.string.kind_show);
            c.addRow(new Object[]{id++, t.name, Services.NAMES[t.service] + " · " + kind, 0,
                    "content://" + AUTHORITY + "/poster?u=" + Uri.encode(url), LAUNCH, Services.PACKAGES[t.service], t.overview});
        }
        prune(keep);
        return c;
    }

    /** The launcher asks every 30 seconds; the list itself is fetched at most every six hours, off its thread. */
    private void refreshInBackground() {
        if (Picks.key(getContext()) == null || !refreshing.compareAndSet(false, true)) return;
        refresher.execute(() -> {
            try {
                Picks.refreshIfStale(getContext(), Picks.installed(getContext()), Locale.getDefault().toLanguageTag());
            } catch (IOException ignored) {
                // Offline, or TMDB is down: the last list stays.
            } finally {
                refreshing.set(false);
            }
        });
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"poster".equals(uri.getLastPathSegment())) throw new FileNotFoundException();
        String url = uri.getQueryParameter("u");
        if (url == null || !listed(url)) throw new FileNotFoundException("not listed");
        File file = cacheFile(url);
        if (!file.exists() || file.length() == 0) download(url, file);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private boolean listed(String url) {
        for (Title t : Picks.cached(getContext())) {
            if (t.backdrop != null && url.equals(Tmdb.BACKDROPS + t.backdrop)) return true;
        }
        return false;
    }

    private File cacheFile(String url) {
        File dir = new File(getContext().getCacheDir(), "backdrops");
        dir.mkdirs();
        return new File(dir, hash(url));
    }

    private static String hash(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void download(String url, File out) throws FileNotFoundException {
        File tmp = new File(out.getPath() + ".tmp");
        try {
            Tmdb.download(url, tmp);
            if (!tmp.renameTo(out)) throw new FileNotFoundException("rename");
        } catch (IOException e) {
            tmp.delete();
            throw new FileNotFoundException(String.valueOf(e));
        }
    }

    /** Drops cached pictures of titles that are no longer in the list. */
    private void prune(Set<String> keep) {
        File[] files = new File(getContext().getCacheDir(), "backdrops").listFiles();
        if (files == null) return;
        Set<String> names = new HashSet<>();
        for (String url : keep) names.add(hash(url));
        for (File f : files) if (!names.contains(f.getName())) f.delete();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
