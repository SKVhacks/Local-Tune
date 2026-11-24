package com.opentune.data.recommend

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/**
 * "Recommended for you": songs from the phone's own library, picked from how
 * the listener actually listens. Nothing leaves the phone, and nothing here
 * touches Android, so it can be tested on its own and shared with other apps.
 *
 * Each candidate song gets a score built from:
 *  - **Taste for its artists, album and folder.** Every listen counts for what
 *    it played, weighted by how recent it is (a 3-week half-life) and how much
 *    of the song was heard. Songs skipped early count against their artist.
 *    Liked songs count extra.
 *  - **Played together.** Songs heard in the same sitting (gaps under 30 min)
 *    are linked, so songs that went with the listener's recent favourites
 *    score higher ("people who played this also played…", from one listener).
 *  - **Liked**, a bonus; and **not yet heard**, a small one, for discovery.
 *  - **Freshness:** songs played in the last day or two are held back, and
 *    much-played ones count a little less, so the shelf isn't just Replay.
 *  - A touch of chance, fixed for the [seed], so it varies between launches.
 *
 * Then a greedy pick keeps it varied: at most [perArtist] songs by one artist
 * and two from one album. With no listening yet, liked songs come first, then
 * a random mix.
 */
object Recommender {
    data class Candidate(
        val id: String,
        val artists: List<String>,
        val album: String?,
        val folder: String?,
        val durationMs: Long,
    )

    data class Listen(
        val id: String,
        val artists: List<String>,
        val album: String?,
        val playedAt: Long,
        val listenedMs: Long,
        /** The song's length, when known: how much of it was heard. */
        val durationMs: Long,
    )

    private const val DAY = 24L * 60 * 60 * 1000
    private const val HALF_LIFE_DAYS = 21.0
    private const val SESSION_GAP = 30L * 60 * 1000
    private const val RECENT_HOLD = 36L * 60 * 60 * 1000

    fun recommend(
        candidates: List<Candidate>,
        listens: List<Listen>,
        liked: Set<String>,
        now: Long,
        seed: Long,
        count: Int = 20,
        perArtist: Int = 2,
        /**
         * A song to follow: what comes after it when it's played on its own. Songs
         * like it (its artists, album and folder, and what's been played with it)
         * then count most, the listener's general taste less.
         */
        after: String? = null,
    ): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val random = Random(seed)
        val byId = candidates.associateBy { it.id }
        val focus = after?.let { byId[it] }
        if (listens.isEmpty() && focus == null) {
            val likedFirst = candidates.filter { it.id in liked }.shuffled(random)
            val rest = candidates.filter { it.id !in liked }.shuffled(random)
            return diverse(likedFirst + rest, count, perArtist, byId)
        }

        // How much one listen says, from its age and how much of it was heard.
        fun weight(l: Listen): Double {
            val ageDays = max(0L, now - l.playedAt) / DAY.toDouble()
            val recency = exp(-ln(2.0) * ageDays / HALF_LIFE_DAYS)
            val heard = if (l.durationMs > 0) (l.listenedMs.toDouble() / l.durationMs).coerceIn(0.0, 1.0) else 0.7
            // Under a third heard is a skip: it counts against.
            val signal = if (heard < 0.33) -0.5 else 0.4 + 0.6 * heard
            return recency * signal
        }

        val artistTaste = HashMap<String, Double>()
        val albumTaste = HashMap<String, Double>()
        val folderTaste = HashMap<String, Double>()
        val plays = HashMap<String, Int>()
        val lastPlayed = HashMap<String, Long>()
        for (l in listens) {
            val w = weight(l)
            l.artists.forEach { artistTaste.merge(key(it), w, Double::plus) }
            l.album?.let { albumTaste.merge(key(it), w, Double::plus) }
            byId[l.id]?.folder?.let { folderTaste.merge(it, w, Double::plus) }
            plays.merge(l.id, 1, Int::plus)
            lastPlayed.merge(l.id, l.playedAt, ::maxOf)
        }
        for (id in liked) {
            val c = byId[id] ?: continue
            c.artists.forEach { artistTaste.merge(key(it), 1.5, Double::plus) }
            c.album?.let { albumTaste.merge(key(it), 0.8, Double::plus) }
        }

        // Played together: songs within three places of each other in one sitting.
        val together = HashMap<String, HashMap<String, Double>>()
        val ordered = listens.sortedBy { it.playedAt }
        var start = 0
        for (i in ordered.indices) {
            if (i > 0 && ordered[i].playedAt - ordered[i - 1].playedAt > SESSION_GAP) start = i
            val w = max(0.0, weight(ordered[i]))
            for (j in max(start, i - 3) until i) {
                val a = ordered[i].id
                val b = ordered[j].id
                if (a == b) continue
                together.getOrPut(a) { HashMap() }.merge(b, w, Double::plus)
                together.getOrPut(b) { HashMap() }.merge(a, w, Double::plus)
            }
        }
        // The listener's recent favourites, what "played together" is measured from.
        val seeds = listens.filter { now - it.playedAt < 14 * DAY }
            .groupBy { it.id }.mapValues { (_, ls) -> ls.sumOf(::weight) }
            .entries.sortedByDescending { it.value }.take(25).associate { it.key to it.value }

        val maxArtist = artistTaste.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val maxAlbum = albumTaste.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val maxFolder = folderTaste.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val coScores = candidates.associate { c ->
            c.id to (together[c.id]?.entries?.sumOf { (other, w) -> w * (seeds[other] ?: 0.0) } ?: 0.0)
        }
        val maxCo = coScores.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0

        val focusArtists = focus?.artists?.map(::key)?.toSet().orEmpty()
        val focusTogether = focus?.let { together[it.id] }.orEmpty()
        val maxFocusTogether = focusTogether.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val scored = candidates.mapNotNull { c ->
            if (c.id == focus?.id) return@mapNotNull null
            // Just heard: held back for now.
            if ((lastPlayed[c.id] ?: 0L) > now - RECENT_HOLD) return@mapNotNull null
            val artist = c.artists.maxOfOrNull { artistTaste[key(it)] ?: 0.0 } ?: 0.0
            val album = c.album?.let { albumTaste[key(it)] } ?: 0.0
            val folder = c.folder?.let { folderTaste[it] } ?: 0.0
            val n = plays[c.id] ?: 0
            var score = 1.0 * (artist / maxArtist) +
                0.5 * (album / maxAlbum) +
                0.3 * (folder / maxFolder) +
                1.2 * (coScores[c.id]!! / maxCo)
            if (focus != null) {
                // Following a song: like it counts most, general taste half.
                score *= 0.5
                if (c.artists.any { key(it) in focusArtists }) score += 1.2
                if (c.album != null && focus.album != null && key(c.album) == key(focus.album)) score += 0.6
                if (c.folder != null && c.folder == focus.folder) score += 0.4
                score += 1.5 * ((focusTogether[c.id] ?: 0.0) / maxFocusTogether)
            }
            if (c.id in liked) score += 0.5
            if (n == 0 && artist > 0) score += 0.25
            score -= 0.08 * ln(1.0 + n)
            score += 0.15 * random.nextDouble()
            c to score
        }.sortedByDescending { it.second }.map { it.first }
        // Too few left after holding back the recent ones: fill with the rest at random.
        val chosen = scored.mapTo(HashSet()) { it.id }
        val rest = candidates.filter { it.id !in chosen && it.id != focus?.id }.shuffled(random)
        return diverse(scored + rest, count, perArtist, byId)
    }

    private fun diverse(ranked: List<Candidate>, count: Int, perArtist: Int, byId: Map<String, Candidate>): List<String> {
        val out = ArrayList<String>(count)
        val artists = HashMap<String, Int>()
        val albums = HashMap<String, Int>()
        val held = ArrayList<Candidate>()
        for (c in ranked) {
            if (out.size >= count) break
            val a = c.artists.firstOrNull()?.let(::key)
            val al = c.album?.let(::key)
            if ((a != null && (artists[a] ?: 0) >= perArtist) || (al != null && (albums[al] ?: 0) >= 2)) { held += c; continue }
            out += c.id
            a?.let { artists.merge(it, 1, Int::plus) }
            al?.let { albums.merge(it, 1, Int::plus) }
        }
        // A small library can't be that varied: top up in order.
        for (c in held) { if (out.size >= count) break; out += c.id }
        return out.filter { it in byId }
    }

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
