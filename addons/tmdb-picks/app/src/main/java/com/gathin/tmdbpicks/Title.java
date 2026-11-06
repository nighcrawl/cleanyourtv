package com.gathin.tmdbpicks;

import org.json.JSONException;
import org.json.JSONObject;

/** One movie or series that a streaming service offers, from a TMDB list. */
final class Title {
    final String name;
    final String overview;
    /** Paths under the image host, like "/abc.jpg"; null when TMDB has none. The poster is portrait, the backdrop 16:9. */
    final String poster;
    final String backdrop;
    /** Release or first air year, 0 when unknown. */
    final int year;
    /** Average vote on a 0-10 scale, 0 when unrated. */
    final double rating;
    /** Index into {@link Services}: the app that offers it. */
    final int service;
    final boolean movie;

    Title(String name, String overview, String poster, String backdrop, int year, double rating, int service, boolean movie) {
        this.name = name;
        this.overview = overview;
        this.poster = poster;
        this.backdrop = backdrop;
        this.year = year;
        this.rating = rating;
        this.service = service;
        this.movie = movie;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("name", name).put("overview", overview).put("poster", poster)
                .put("backdrop", backdrop).put("year", year).put("rating", rating).put("service", service)
                .put("movie", movie);
    }

    static Title fromJson(JSONObject o) throws JSONException {
        return new Title(o.getString("name"), o.optString("overview", ""), o.isNull("poster") ? null : o.optString("poster", null),
                o.isNull("backdrop") ? null : o.optString("backdrop", null), o.optInt("year", 0), o.optDouble("rating", 0),
                o.getInt("service"), o.optBoolean("movie", true));
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Title)) return false;
        Title t = (Title) o;
        return name.equals(t.name) && java.util.Objects.equals(poster, t.poster) && java.util.Objects.equals(backdrop, t.backdrop)
                && year == t.year && service == t.service && movie == t.movie;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, year, service, movie);
    }
}
