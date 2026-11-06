package com.gathin.tmdbpicks;

/** The streaming services whose catalogues are recommended, and where to find their apps and TMDB's ids for them. */
final class Services {
    static final String[] NAMES = {"Netflix", "Disney+", "Prime Video", "Canal+"};
    /** TMDB watch provider ids. */
    static final int[] TMDB_IDS = {8, 337, 119, 381};
    static final String[] PACKAGES = {"com.netflix.ninja", "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom", "com.canal.android.canal"};
    /** Whose catalogues count: what a service offers differs by country. */
    static final String REGION = "FR";

    private Services() {
    }
}
