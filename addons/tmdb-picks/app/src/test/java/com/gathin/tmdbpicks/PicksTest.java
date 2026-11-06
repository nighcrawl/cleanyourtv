package com.gathin.tmdbpicks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class PicksTest {

    private static Title t(String name, int service, boolean movie, String backdrop) {
        return new Title(name, "", null, backdrop, 2024, 7, service, movie);
    }

    private static List<String> names(List<Title> titles) {
        List<String> out = new ArrayList<>();
        for (Title t : titles) out.add(t.name);
        return out;
    }

    @Test public void moviesAndSeriesTakeTurns() {
        List<Title> mixed = Picks.alternate(
                Arrays.asList(t("m1", 0, true, "/a"), t("m2", 0, true, "/a"), t("m3", 0, true, "/a")),
                Arrays.asList(t("s1", 0, false, "/a")));
        assertEquals(Arrays.asList("m1", "s1", "m2", "m3"), names(mixed));
    }

    @Test public void servicesTakeTurnsOnHome() {
        List<Title> home = Picks.forHome(Arrays.asList(
                t("n1", 0, true, "/a"), t("n2", 0, true, "/a"),
                t("d1", 1, true, "/a"), t("d2", 1, true, "/a"), t("d3", 1, false, "/a")));
        assertEquals(Arrays.asList("n1", "d1", "n2", "d2", "d3"), names(home));
    }

    @Test public void aTitleOnTwoServicesIsListedOnce() {
        List<Title> home = Picks.forHome(Arrays.asList(t("Same", 0, true, "/a"), t("Same", 2, true, "/a"), t("Other", 2, true, "/a")));
        assertEquals(Arrays.asList("Same", "Other"), names(home));
    }

    @Test public void aTitleWithoutAWidePictureIsLeftOutOfHome() {
        List<Title> home = Picks.forHome(Arrays.asList(t("NoArt", 0, true, null), t("Art", 0, true, "/a")));
        assertEquals(Arrays.asList("Art"), names(home));
    }

    @Test public void homeKeepsFourMoviesAndFourSeries() {
        List<Title> all = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            all.add(t("m" + i, i % 2, true, "/a"));
            all.add(t("s" + i, i % 2, false, "/a"));
        }
        List<Title> home = Picks.forHome(all);
        int movies = 0;
        for (Title t : home) if (t.movie) movies++;
        assertEquals(4, movies);
        assertEquals(8, home.size());
    }

    @Test public void theQueryIsSubscriptionOnlyInTheRegion() {
        String q = Picks.query(3);
        assertTrue(q.contains("with_watch_providers=381"));
        assertTrue(q.contains("watch_region=FR"));
        assertTrue(q.contains("with_watch_monetization_types=flatrate"));
        assertEquals(Services.NAMES.length, Services.TMDB_IDS.length);
        assertEquals(Services.NAMES.length, Services.PACKAGES.length);
    }

    @Test public void aTitleSurvivesTheFile() throws Exception {
        Title original = new Title("Dark", "Time.", "/p.jpg", null, 2017, 8.4, 1, false);
        assertEquals(original, Title.fromJson(original.toJson()));
    }
}
