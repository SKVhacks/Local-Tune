// Ported from the phone app's core/.../data/local/LocalSuggestions.kt. Keep the two in step.

namespace LocalTune.Core;

/// <summary>
/// Songs to suggest, all from this PC's own library: by the same artists first, then from the
/// same albums, then songs played often here, then anything. Light: a few passes over the library,
/// no learning, nothing sent anywhere.
/// </summary>
public static class Suggestions
{
    /// <summary>Up to <paramref name="count"/> songs that go with <paramref name="seeds"/>, none of them in <paramref name="exclude"/>.</summary>
    public static List<Track> After(IReadOnlyList<Track> seeds, int count, ISet<string>? exclude, Random random, Library lib, Dictionary<string, int> plays)
    {
        if (lib.Songs.Count == 0) return new();
        var skip = new HashSet<string>(exclude ?? new HashSet<string>(), StringComparer.OrdinalIgnoreCase);
        foreach (var s in seeds) skip.Add(s.Path);
        bool Ok(Track t) => !skip.Contains(t.Path);
        List<Track> Shuffled(IEnumerable<Track> e) => e.OrderBy(_ => random.Next()).ToList();

        var artistKeys = seeds.SelectMany(s => s.Artists).Select(ArtistNames.Key).ToHashSet();
        var albumKeys = seeds.Select(s => s.AlbumKey).ToHashSet();
        var byArtist = Shuffled(lib.Artists.Where(a => artistKeys.Contains(a.Key)).SelectMany(a => a.Tracks).Where(Ok));
        var byAlbum = Shuffled(lib.Albums.Where(a => albumKeys.Contains(a.Key)).SelectMany(a => a.Tracks).Where(Ok));
        var favourites = Shuffled(Shuffled(lib.Songs.Where(t => Ok(t) && plays.GetValueOrDefault(t.Path) >= 2))
            .OrderByDescending(t => plays.GetValueOrDefault(t.Path)).Take(count * 2));
        var anything = Shuffled(lib.Songs.Where(Ok));

        // A blend, so a long run isn't all one artist: about half by the same artists, a quarter
        // from the same albums, the rest favourites and others.
        var outList = new List<Track>();
        var taken = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var sources = new[] { byArtist, byAlbum, favourites, byArtist, anything }.Select(l => l.GetEnumerator()).ToArray();
        var stalled = 0;
        while (outList.Count < count && stalled < sources.Length)
        {
            stalled = 0;
            foreach (var it in sources)
            {
                if (outList.Count >= count) break;
                var added = false;
                while (it.MoveNext())
                {
                    if (!taken.Add(it.Current.Path)) continue;
                    outList.Add(it.Current);
                    added = true;
                    break;
                }
                if (!added) stalled++;
            }
        }
        return outList;
    }

    /// <summary>
    /// Home's "Recommended": songs that go with what's been played lately (the last dozen songs
    /// heard are the seeds), leaving out those just heard. It follows the listening: each new
    /// listen changes the seeds and the mix, so the shelf moves on as music is played. With
    /// nothing played yet, a mix of the library.
    /// </summary>
    public static List<Track> Recommended(Library lib, int count = 24)
    {
        // Seeded by the history itself: the same while nothing new is played (Home doesn't
        // reshuffle on every visit), a fresh mix after each listen.
        var latest = History.Records.Count > 0 ? History.Records[0].PlayedAt : 0;
        var random = new Random(unchecked((int)(latest ^ (latest >> 32)) * 31 + History.Records.Count));
        var recents = History.Recents(lib, 40);
        var seeds = recents.Take(12).ToList();
        var justHeard = recents.Take(6).Select(t => t.Path).ToHashSet(StringComparer.OrdinalIgnoreCase);
        // Songs snoozed for a month stay out until it's up.
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        foreach (var (path, until) in Settings.Value.Snoozed) if (until > now) justHeard.Add(path);
        return After(seeds, count, justHeard, random, lib, History.PlayCounts(lib));
    }
}
