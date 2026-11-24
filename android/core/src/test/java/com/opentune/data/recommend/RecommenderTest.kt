package com.opentune.data.recommend

import com.opentune.data.recommend.Recommender.Candidate
import com.opentune.data.recommend.Recommender.Listen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day

    private fun song(id: String, artist: String, album: String = "$artist album") =
        Candidate(id, listOf(artist), album, "/music/$artist", 200_000)

    private fun heard(id: String, artist: String, daysAgo: Double, fraction: Double = 1.0, album: String = "$artist album") =
        Listen(id, listOf(artist), album, now - (daysAgo * day).toLong(), (200_000 * fraction).toLong(), 200_000)

    private val library = listOf(
        song("a1", "Anirudh"), song("a2", "Anirudh"), song("a3", "Anirudh", "Other"),
        song("b1", "Yuvan"), song("b2", "Yuvan"),
        song("c1", "ARTMS"), song("c2", "ARTMS"),
        song("d1", "Skipped"), song("d2", "Skipped"),
    )

    @Test
    fun favouriteArtistComesFirst_andSkippedArtistLast() {
        val listens = listOf(
            heard("a1", "Anirudh", 3.0), heard("a1", "Anirudh", 4.0), heard("a2", "Anirudh", 5.0),
            heard("d1", "Skipped", 3.0, fraction = 0.1), heard("d1", "Skipped", 4.0, fraction = 0.1),
        )
        val picks = Recommender.recommend(library, listens, emptySet(), now, seed = 1, count = 9)
        assertTrue(picks.take(2).all { it.startsWith("a") })
        assertTrue(picks.indexOf("d2") > picks.indexOf("b1"))
    }

    @Test
    fun justPlayedSongsAreHeldBack() {
        val listens = listOf(heard("a1", "Anirudh", 0.1), heard("a2", "Anirudh", 5.0))
        val picks = Recommender.recommend(library, listens, emptySet(), now, seed = 1, count = 3)
        assertFalse("a1" in picks)
    }

    @Test
    fun noMoreThanTwoByOneArtistWhenThereIsChoice() {
        val listens = listOf(heard("a1", "Anirudh", 3.0), heard("a2", "Anirudh", 3.0), heard("a3", "Anirudh", 3.0, album = "Other"))
        val picks = Recommender.recommend(library, listens, emptySet(), now, seed = 1, count = 6)
        assertTrue(picks.count { it.startsWith("a") } <= 2)
    }

    @Test
    fun songsPlayedTogetherAreSuggested() {
        // c1 was always heard right after a1 (a favourite); c2 never was.
        val listens = (3..7).flatMap { d ->
            listOf(heard("a1", "Anirudh", d.toDouble()), heard("c1", "ARTMS", d - 0.001))
        }
        val picks = Recommender.recommend(library, listens, emptySet(), now, seed = 1, count = 9)
        assertTrue(picks.indexOf("c1") < picks.indexOf("c2"))
    }

    @Test
    fun noListeningYet_likedFirst_andSameSeedSameList() {
        val a = Recommender.recommend(library, emptyList(), setOf("b2"), now, seed = 7, count = 5)
        val b = Recommender.recommend(library, emptyList(), setOf("b2"), now, seed = 7, count = 5)
        assertEquals("b2", a.first())
        assertEquals(a, b)
    }

    @Test
    fun afterASong_itsArtistFollows_andNotTheSongItself() {
        val listens = listOf(heard("c1", "ARTMS", 4.0), heard("c1", "ARTMS", 5.0), heard("c1", "ARTMS", 6.0))
        val picks = Recommender.recommend(library, listens, emptySet(), now, seed = 3, count = 8, after = "b1")
        assertFalse("b1" in picks)
        assertEquals("b2", picks.first())
    }
}
