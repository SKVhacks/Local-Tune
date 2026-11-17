package com.opentune.data.lyrics

import com.opentune.playback.ReplayGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lyric files people keep next to their music, read the way the player needs them. */
class LocalLyricFormatsTest {

    // ---- LRC ------------------------------------------------------------------

    @Test
    fun plainLrcIsLineSynced() {
        val lines = LrcParser.parse(
            """
            [ti:Song]
            [ar:Artist]
            [00:12.00]First line
            [00:15.50]Second line
            """.trimIndent(),
        )
        assertEquals(listOf("First line", "Second line"), lines.map { it.text })
        assertEquals(12_000L, lines[0].startMs)
        assertEquals(15_500L, lines[0].endMs)
        assertFalse(lines[0].wordSynced)
    }

    @Test
    fun enhancedLrcWordTagsAreWordSynced() {
        val lines = LrcParser.parse("[00:10.00]<00:10.00>Hello <00:10.50>bright <00:11.20>world\n[00:13.00]Next")
        val first = lines.first()
        assertTrue(first.wordSynced)
        assertEquals(listOf("Hello ", "bright ", "world"), first.words.map { it.text })
        assertEquals(listOf(10_000L, 10_500L, 11_200L), first.words.map { it.startMs })
        // The last word runs to the next line.
        assertEquals(13_000L, first.words.last().endMs)
    }

    @Test
    fun squareBracketTagsInsideALineAreWordTimes() {
        // Some karaoke tools write word times with the same brackets as line times.
        val lines = LrcParser.parse("[00:20.00]Hel[00:20.40]lo [00:20.90]there\n[00:23.00]End")
        assertEquals(2, lines.size)
        val first = lines.first()
        assertEquals("Hello there", first.text)
        assertTrue(first.wordSynced)
        assertEquals(listOf(20_000L, 20_400L, 20_900L), first.words.map { it.startMs })
    }

    @Test
    fun repeatedLineTimesStillMakeSeveralLines() {
        val lines = LrcParser.parse("[00:30.00][01:30.00]Chorus line\n[00:35.00]Verse")
        assertEquals(listOf(30_000L, 35_000L, 90_000L), lines.map { it.startMs })
        assertEquals("Chorus line", lines[2].text)
    }

    @Test
    fun duetMarksAndBackingLines() {
        val lines = LrcParser.parse(
            """
            [00:01.00]v1: <00:01.00>I <00:01.50>sing
            [bg: <00:01.60>(ooh <00:02.00>yeah)]
            [00:03.00]v2: You sing
            """.trimIndent(),
        )
        assertEquals(2, lines.size)
        assertEquals("I sing", lines[0].text)
        assertEquals(VOICE_LEAD, lines[0].voice)
        assertTrue(lines[0].wordSynced)
        assertEquals("(ooh yeah)", lines[0].background)
        assertEquals("You sing", lines[1].text)
        assertEquals(VOICE_SECOND, lines[1].voice)
    }

    @Test
    fun offsetTagShiftsEveryLine() {
        val lines = LrcParser.parse("[offset:500]\n[00:10.00]Line")
        assertEquals(9_500L, lines.single().startMs)
    }

    @Test
    fun windowsLineEndingsAreFine() {
        val lines = LrcParser.parse("[00:01.00]One\r\n[00:02.00]Two\r\n")
        assertEquals(listOf("One", "Two"), lines.map { it.text })
    }

    // ---- TTML (Apple Music) -----------------------------------------------------

    private val appleTtml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word" xml:lang="en">
          <head><metadata>
            <ttm:agent type="person" xml:id="v1"/>
            <ttm:agent type="person" xml:id="v2"/>
          </metadata></head>
          <body dur="3:05.250">
            <div begin="12.000" end="20.000" itunes:song-part="Verse">
              <p begin="12.000" end="15.000" itunes:key="L1" ttm:agent="v1"><span begin="12.000" end="12.300">Some</span><span begin="12.300" end="12.700">bo</span><span begin="12.700" end="13.200">dy</span> <span begin="13.300" end="14.000">once</span><span ttm:role="x-bg"><span begin="14.100" end="14.600">(once)</span></span></p>
              <p begin="15.500" end="18.000" itunes:key="L2" ttm:agent="v2"><span begin="15.500" end="16.000">told</span> <span begin="16.000" end="17.000">me</span></p>
            </div>
            <div itunes:song-part="Chorus">
              <p begin="1:02.000" end="1:05.000" itunes:key="L3" ttm:agent="v1"><span begin="1:02.000" end="1:03.000">Hey</span> <span begin="1:03.000" end="1:05.000">now</span></p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    @Test
    fun appleTtmlSyllablesJoinIntoWords() {
        val lines = Ttml.parse(appleTtml)
        assertEquals(3, lines.size)
        val first = lines[0]
        assertEquals("Somebody once", first.text)
        assertTrue(first.wordSynced)
        // Each syllable keeps its own timing, so the sweep moves through the word.
        assertEquals(listOf(12_000L, 12_300L, 12_700L, 13_300L), first.words.map { it.startMs })
        assertEquals("(once)", first.background)
        assertEquals(VOICE_LEAD, first.voice)
        assertEquals(VOICE_SECOND, lines[1].voice)
        assertEquals(62_000L, lines[2].startMs)
    }

    @Test
    fun appleTtmlSongPartsAndLength() {
        val parts = Ttml.parts(appleTtml)
        assertEquals(listOf("Verse", "Chorus"), parts.map { it.name })
        assertEquals(12_000L, parts[0].startMs)
        // A div without its own times takes them from its lines.
        assertEquals(62_000L, parts[1].startMs)
        assertEquals(65_000L, parts[1].endMs)
        assertEquals(185_250L, Ttml.length(appleTtml))
    }

    @Test
    fun lineTimedTtmlFallsBackToSpreadWords() {
        val lines = Ttml.parse("""<tt><body><div><p begin="00:01.000" end="00:04.000">Just a line</p></div></body></tt>""")
        assertEquals("Just a line", lines.single().text)
        assertFalse(lines.single().wordSynced)
    }

    @Test
    fun appleLineTimedTtmlSweepsAcrossTheWholeLine() {
        // As Apple ships many songs: itunes:timing="Line", one time per line, overlapping a little.
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Line"><body dur="3:10.582"><div begin="13.745" end="23.520" itunes:songPart="Chorus"><p begin="13.745" end="16.871" itunes:key="L3">Four-by-four, I tint my windows up, yeah</p><p begin="17.093" end="20.818" itunes:key="L4">Damn, lil' bro, he did it, one-and-done, yeah</p><p begin="20.493" end="23.520" itunes:key="L5">Out my closet, that shit none-of-none, yeah</p></div></body></tt>"""
        val lines = Ttml.parse(xml)
        val first = lines.first()
        assertFalse(first.wordSynced)
        // The sweep starts on the line's time, runs with no gaps, and is done before the
        // line's held end: a quick verse is sung early in its line.
        assertEquals(13_745L, first.words.first().startMs)
        first.words.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        assertTrue(first.words.last().endMs <= 13_745L + (16_871L - 13_745L) * 9 / 10)
        // Longer words take longer: "windows" more than "I".
        val i = first.words.first { it.text.trim() == "I" }
        val windows = first.words.first { it.text.trim() == "windows" }
        assertTrue(windows.endMs - windows.startMs > (i.endMs - i.startMs) * 5)
        // Through tidying, a line that runs into the next one sings faster into it, nothing past it.
        val tidy = LyricsQuality.tidy(Lyrics.Synced(lines, "x"), 190_582) as Lyrics.Synced
        assertEquals(20_493L, tidy.lines[1].endMs)
        assertTrue(tidy.lines[1].words.last().endMs <= 20_493L)
    }

    @Test
    fun appleAdLibsInBracketsBecomeBackingVocalsSweptAlongside() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Line"><body><div><p begin="8.083" end="13.582" itunes:key="L2">Yeah, yeah-yeah, yeah-yeah-yeah-yeah-yeah-yeah (Four-by—)</p><p begin="1:10.779" end="1:14.640" itunes:key="L18">Damn, lil' bro, he did it, one-and-done, yeah (Need to) (One-and, one-and—)</p><p begin="1:20.000" end="1:22.000">(Ooh)</p></div></body></tt>"""
        val lines = Ttml.parse(xml)
        val l2 = lines[0]
        assertEquals("Yeah, yeah-yeah, yeah-yeah-yeah-yeah-yeah-yeah", l2.text)
        assertEquals("Four-by—", l2.background)
        // The ad-lib comes in while the line's last words are still sweeping, and is done by the line's end.
        val bg = l2.backgroundWords
        assertEquals("Four-by—", bg.joinToString("") { it.text }.trim())
        assertTrue(bg.last().endMs <= 13_582L)
        assertTrue(bg.first().startMs < l2.words.last().endMs)
        // Where Apple Music's own highlight is: at 0:10.5 its sweep is most of the way along.
        val at = 10_500L
        val litLetters = l2.words.sumOf { w -> val n = w.text.count(Char::isLetterOrDigit); when { at >= w.endMs -> n; at <= w.startMs -> 0; else -> (n * (at - w.startMs) / (w.endMs - w.startMs)).toInt() } }
        val allLetters = l2.text.count(Char::isLetterOrDigit)
        assertTrue("only $litLetters of $allLetters lit", litLetters >= allLetters * 3 / 4)
        // Two asides in a row are one backing part.
        assertEquals("Damn, lil' bro, he did it, one-and-done, yeah", lines[1].text)
        assertEquals("Need to One-and, one-and—", lines[1].background)
        // A line that is all aside stays a line.
        assertEquals("(Ooh)", lines[2].text)
        assertNull(lines[2].background)
    }

    @Test
    fun ttmlEntitiesAreRead() {
        val lines = Ttml.parse("""<tt><body><p begin="1s" end="2s"><span begin="1s" end="1.5s">Don&apos;t</span> <span begin="1.5s" end="2s">stop</span></p></body></tt>""")
        assertEquals("Don't stop", lines.single().text)
    }

    // ---- Files ------------------------------------------------------------------

    @Test
    fun namesMatchLoosely() {
        assertEquals(LocalLyrics.key("Daft Punk - One More Time"), LocalLyrics.key("daft punk – one more time"))
        assertEquals(LocalLyrics.key("Beyoncé"), LocalLyrics.key("Beyonce"))
    }

    @Test
    fun textEncodings() {
        assertEquals("héllo", LocalLyrics.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo".toByteArray(Charsets.UTF_8)))
        assertEquals("hi", LocalLyrics.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(Charsets.UTF_16LE)))
        // An old LRC saved in Windows-1252 isn't valid UTF-8; it still reads.
        assertEquals("café", LocalLyrics.decode(byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())))
    }

    @Test
    fun wordSyncedOutranksLineSynced() {
        val word = Lyrics.Synced(LrcParser.parse("[00:01.00]<00:01.00>a <00:01.50>b"), "x")
        val line = Lyrics.Synced(LrcParser.parse("[00:01.00]a b"), "x")
        assertTrue(LyricsRepository.rank(word) > LyricsRepository.rank(line))
        assertTrue(LyricsRepository.rank(line) > LyricsRepository.rank(Lyrics.Plain("a b", "x")))
    }

    // ---- ReplayGain -------------------------------------------------------------

    @Test
    fun replayGainValues() {
        assertEquals(-7.31f, ReplayGain.parseDb("-7.31 dB")!!, 0.001f)
        assertEquals(1.2f, ReplayGain.parseDb("+1.2dB")!!, 0.001f)
        assertNotNull(ReplayGain.parseDb("-3"))
        assertNull(ReplayGain.parseDb("loud"))
    }

    @Test
    fun appleTtmlSweepsButPlainLrcLightsWholeLines() {
        val ttml = Ttml.parse("""<tt itunes:timing="Line"><body><div><p begin="1.0" end="4.0">Life in plastic</p></div></body></tt>""")
        assertTrue(ttml.single().sweeps)
        val lrc = LrcParser.parse("[00:27.72]Adiyae kolluthey\n[00:31.72]Azhago alluthey")
        assertTrue(lrc.none { it.sweeps || it.wordSynced })
    }
}
