package com.gathin.tmdbpicks;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The recommendations: for each installed service, what is popular among the titles it offers at no extra cost
 * (a subscription, not a purchase or a rental), as TMDB knows it for {@link Services#REGION}. Kept in a file so the
 * home row and the app share one answer and TMDB is asked at most every six hours.
 */
final class Picks {
    private static final String PREFS = "tmdb";
    private static final String KEY = "key";
    private static final long MAX_AGE_MS = 6L * 60 * 60 * 1000;
    private static final String FILE = "picks.json";
    /** Movies, and series, kept for Home: the banner has one dot per title. */
    static final int HOME_PER_KIND = 4;

    private Picks() {
    }

    // ---- The key ----------------------------------------------------------------------------------------------

    static String key(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
    }

    static void setKey(Context context, String key) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, key).apply();
    }

    /** Forgets the key and what it fetched. */
    static synchronized void clearKey(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
        new File(context.getFilesDir(), FILE).delete();
    }

    // ---- The list ---------------------------------------------------------------------------------------------

    /** Which services have an app on this TV. */
    static boolean[] installed(Context context) {
        boolean[] found = new boolean[Services.PACKAGES.length];
        for (int i = 0; i < found.length; i++) {
            found[i] = context.getPackageManager().getLeanbackLaunchIntentForPackage(Services.PACKAGES[i]) != null;
        }
        return found;
    }

    /** What was fetched last, empty when nothing was or the key was removed. */
    static synchronized List<Title> cached(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        List<Title> titles = new ArrayList<>();
        if (!file.isFile()) return titles;
        try {
            JSONArray array = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) titles.add(Title.fromJson(array.getJSONObject(i)));
        } catch (IOException | JSONException e) {
            titles.clear();
        }
        return titles;
    }

    /**
     * The list, fetched again when it is older than six hours (or missing) and the key is set. One caller at a time:
     * a second one waits and then finds a fresh file. A failure keeps what was cached.
     */
    static synchronized List<Title> refreshIfStale(Context context, boolean[] installed, String language) throws IOException {
        File file = new File(context.getFilesDir(), FILE);
        String key = key(context);
        if (key == null) return new ArrayList<>();
        if (file.isFile() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) return cached(context);
        List<Title> titles = fetch(key, language, installed);
        try {
            JSONArray array = new JSONArray();
            for (Title t : titles) array.put(t.toJson());
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), array.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) throw new IOException("rename");
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return titles;
    }

    static List<Title> fetch(String key, String language, boolean[] installed) throws IOException {
        List<Title> all = new ArrayList<>();
        for (int s = 0; s < Services.NAMES.length; s++) {
            if (!installed[s]) continue;
            String query = query(s);
            List<Title> movies = Tmdb.list("/discover/movie", query, language, key, s, true);
            List<Title> shows = Tmdb.list("/discover/tv", query, language, key, s, false);
            all.addAll(alternate(movies, shows));
        }
        return all;
    }

    /** Titles included in the service's subscription (flatrate) in the region, the most popular first. */
    static String query(int service) {
        return "with_watch_providers=" + Services.TMDB_IDS[service] + "&watch_region=" + Services.REGION
                + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc";
    }

    /** One of each in turn, so a row is not all movies or all series. */
    static List<Title> alternate(List<Title> a, List<Title> b) {
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        return out;
    }

    /**
     * The list for Home: the services take turns, a title that two of them offer is listed once, a title without a
     * 16:9 picture is left out, and no more than {@link #HOME_PER_KIND} movies and as many series are kept.
     */
    static List<Title> forHome(List<Title> all) {
        List<List<Title>> perService = new ArrayList<>();
        for (int s = 0; s < Services.NAMES.length; s++) perService.add(new ArrayList<>());
        for (Title t : all) if (t.backdrop != null) perService.get(t.service).add(t);
        List<Title> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int movies = 0, shows = 0;
        for (int i = 0; ; i++) {
            boolean any = false;
            for (List<Title> list : perService) {
                if (i >= list.size()) continue;
                any = true;
                Title t = list.get(i);
                if (t.movie ? movies >= HOME_PER_KIND : shows >= HOME_PER_KIND) continue;
                if (!seen.add(t.name + "/" + t.year)) continue;
                out.add(t);
                if (t.movie) movies++;
                else shows++;
            }
            if (!any) return out;
        }
    }
}
