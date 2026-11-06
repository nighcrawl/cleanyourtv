package com.gathin.tmdbpicks;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The TMDB calls this app makes: a list of titles, a key check, and images. Nothing else is ever requested. */
final class Tmdb {
    static final String API = "https://api.themoviedb.org/3";
    static final String IMAGES = "https://image.tmdb.org/t/p/w185";
    static final String BACKDROPS = "https://image.tmdb.org/t/p/w1280";
    /** Titles kept per request. */
    static final int PER_LIST = 10;
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_BYTES = 2_000_000;

    /** A refused key (HTTP 401): the owner has to enter another one. */
    static final class KeyRefused extends IOException {
        KeyRefused() {
            super("HTTP 401");
        }
    }

    private Tmdb() {
    }

    /** A v4 token is a JWT and goes in a header; a v3 key is 32 hex characters and goes in the query. */
    static boolean isToken(String key) {
        return key.startsWith("eyJ");
    }

    static String url(String path, String query, String language, String key) {
        StringBuilder url = new StringBuilder(API).append(path).append("?language=").append(encode(language));
        if (!query.isEmpty()) url.append('&').append(query);
        if (!isToken(key)) url.append("&api_key=").append(encode(key));
        return url.toString();
    }

    static List<Title> list(String path, String query, String language, String key, int service, boolean movie)
            throws IOException {
        try {
            return parse(get(url(path, query, language, key), key), service, movie);
        } catch (JSONException e) {
            throw new IOException("bad JSON", e);
        }
    }

    /** True when TMDB accepts the key. A network failure throws, so it is not mistaken for a wrong key. */
    static boolean accepts(String key) throws IOException {
        try {
            get(url("/configuration", "", "en", key), key);
            return true;
        } catch (KeyRefused e) {
            return false;
        }
    }

    static List<Title> parse(String json, int service, boolean movie) throws JSONException {
        JSONArray results = new JSONObject(json).getJSONArray("results");
        List<Title> titles = new ArrayList<>();
        for (int i = 0; i < results.length() && titles.size() < PER_LIST; i++) {
            JSONObject o = results.getJSONObject(i);
            // Movies say title and release_date, series say name and first_air_date.
            String name = o.optString("title", o.optString("name", ""));
            if (name.isEmpty()) continue;
            String date = o.optString("release_date", o.optString("first_air_date", ""));
            int year = 0;
            if (date.length() >= 4) {
                try {
                    year = Integer.parseInt(date.substring(0, 4));
                } catch (NumberFormatException ignored) {
                }
            }
            String poster = o.isNull("poster_path") ? null : o.optString("poster_path", null);
            String backdrop = o.isNull("backdrop_path") ? null : o.optString("backdrop_path", null);
            titles.add(new Title(name, o.optString("overview", ""), poster, backdrop, year, o.optDouble("vote_average", 0),
                    service, movie));
        }
        return titles;
    }

    static Bitmap poster(String path) {
        HttpURLConnection c = null;
        try {
            c = open(IMAGES + path, null);
            // 565 halves the memory of a poster; the cache holds a hundred of them.
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in, null, options);
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Downloads a poster or backdrop into out (the provider caches them on disk). */
    static void download(String url, java.io.File out) throws IOException {
        HttpURLConnection c = open(url, null);
        try (InputStream in = c.getInputStream(); java.io.FileOutputStream o = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
    }

    private static String get(String url, String key) throws IOException {
        HttpURLConnection c = open(url, isToken(key) ? key : null);
        try {
            if (c.getResponseCode() == 401) throw new KeyRefused();
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) throw new IOException("response too large");
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        return c;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
