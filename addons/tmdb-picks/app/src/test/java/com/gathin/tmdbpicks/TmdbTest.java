package com.gathin.tmdbpicks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class TmdbTest {

    @Test public void readsAMovieAndASeries() throws Exception {
        List<Title> titles = Tmdb.parse("{\"results\":["
                + "{\"title\":\"Heat\",\"release_date\":\"1995-12-15\",\"poster_path\":\"/h.jpg\",\"backdrop_path\":\"/hb.jpg\",\"vote_average\":7.9,\"overview\":\"A cop.\"},"
                + "{\"name\":\"Dark\",\"first_air_date\":\"2017-12-01\",\"poster_path\":null,\"vote_average\":8.4}]}", 2, true);
        assertEquals(2, titles.size());
        assertEquals("Heat", titles.get(0).name);
        assertEquals(1995, titles.get(0).year);
        assertEquals("/h.jpg", titles.get(0).poster);
        assertEquals(7.9, titles.get(0).rating, 0.001);
        assertEquals("Dark", titles.get(1).name);
        assertEquals(2017, titles.get(1).year);
        assertNull(titles.get(1).poster);
        assertEquals("", titles.get(1).overview);
        assertEquals("/hb.jpg", titles.get(0).backdrop);
        assertNull(titles.get(1).backdrop);
        assertEquals(2, titles.get(0).service);
    }

    @Test public void skipsNamelessEntriesAndBadDates() throws Exception {
        List<Title> titles = Tmdb.parse("{\"results\":[{\"overview\":\"x\"},{\"title\":\"Odd\",\"release_date\":\"\"},"
                + "{\"title\":\"Odder\",\"release_date\":\"abcd-01-01\"}]}", 0, false);
        assertEquals(2, titles.size());
        assertEquals(0, titles.get(0).year);
        assertEquals(0, titles.get(1).year);
    }

    @Test public void keepsOnlyTheFirstTen() throws Exception {
        StringBuilder json = new StringBuilder("{\"results\":[");
        for (int i = 0; i < 40; i++) json.append(i > 0 ? "," : "").append("{\"title\":\"T").append(i).append("\"}");
        assertEquals(Tmdb.PER_LIST, Tmdb.parse(json.append("]}").toString(), 0, true).size());
    }

    @Test(expected = org.json.JSONException.class) public void anErrorBodyIsNotAList() throws Exception {
        Tmdb.parse("{\"status_code\":7}", 0, true);
    }

    @Test public void aV3KeyGoesInTheQueryAndAV4TokenDoesNot() {
        assertTrue(Tmdb.url("/movie/popular", "", "fr-FR", "abc123").contains("&api_key=abc123"));
        assertFalse(Tmdb.url("/movie/popular", "", "fr-FR", "eyJhbGciOi.abc.def").contains("api_key"));
        assertEquals("https://api.themoviedb.org/3/discover/tv?language=fr-FR&with_genres=18&api_key=k",
                Tmdb.url("/discover/tv", "with_genres=18", "fr-FR", "k"));
    }
}
