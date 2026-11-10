package com.opentune.data.local

import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import kotlin.random.Random

/**
 * Songs to play after or mix in with others, all from the phone's own
 * library: by the same artists first, then from the same albums, then songs
 * played often here, then anything. Songs marked "Dislike" or hidden are left
 * out. Used by autoplay and smart shuffle.
 */
object LocalSuggestions {
    /** Up to [count] songs that go with [seeds], none of them in [exclude]. */
    fun after(seeds: List<Song>, count: Int, exclude: Set<String> = emptySet(), random: Random = Random.Default): List<Song> {
        val lib = LocalMusic.library.value ?: return emptyList()
        if (lib.songs.isEmpty()) return emptyList()
        val hidden = LibraryStore.hidden.value
        val disliked = LibraryStore.disliked.value
        val skip = HashSet(exclude).apply { seeds.forEach { add(it.videoId) } }
        fun ok(s: Song) = s.videoId !in skip && s.videoId !in disliked && !hidden.hides(s)

        val artistKeys = seeds.flatMap { LocalMusic.splitArtists(it.artist) }.map(LocalMusic::nameKey).toSet()
        val albumIds = seeds.mapNotNull { it.albumId }.toSet()
        val byArtist = lib.artists.filter { LocalMusic.nameKey(it.name) in artistKeys }.flatMap { it.songs }.filter(::ok).shuffled(random)
        val byAlbum = lib.albums.filter { it.id in albumIds }.flatMap { it.songs }.filter(::ok).shuffled(random)
        val plays = History.records.value.groupingBy { it.videoId }.eachCount()
        val favourites = lib.songs.filter { ok(it) && (plays[it.videoId] ?: 0) >= 2 }.shuffled(random)
            .sortedByDescending { plays[it.videoId] ?: 0 }.take(count * 2).shuffled(random)
        val anything = lib.songs.filter(::ok).shuffled(random)

        // A blend, so a long run isn't all one artist: about half by the same
        // artists, a quarter from the same albums, the rest favourites and others.
        val out = LinkedHashMap<String, Song>()
        val sources = listOf(byArtist.iterator(), byAlbum.iterator(), favourites.iterator(), byArtist.iterator(), anything.iterator())
        var stalled = 0
        while (out.size < count && stalled < sources.size) {
            stalled = 0
            for (it in sources) {
                if (out.size >= count) break
                var added = false
                while (it.hasNext()) {
                    val s = it.next()
                    if (out.putIfAbsent(s.videoId, s) == null) { added = true; break }
                }
                if (!added) stalled++
            }
        }
        return out.values.toList()
    }
}
