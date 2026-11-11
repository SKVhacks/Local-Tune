package com.opentune.data.lyrics

/** The TTML timed-lyrics format (as Apple Music writes it): `<p begin end>` lines, `<span begin end>` words. */
internal object Ttml {
    fun parse(xml: String): List<LyricLine> {
        val voices = voicesOf(xml)
        // Direction is inherited: from the document, then each <div>, then the <p> itself.
        val doc = directionOf(Regex("""<(?:tt|body)\b([^>]*)>""").findAll(xml).map { it.groupValues[1] }.joinToString(" "))
        val divs = Regex("""<div\b([^>]*)>""").findAll(xml).map { it.range.first to directionOf(it.groupValues[1]) }.toList()
        return Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { p ->
            val rtl = directionOf(p.groupValues[1]) ?: divs.lastOrNull { it.first < p.range.first }?.second ?: doc
            val start = time(attr(p.groupValues[1], "begin")) ?: return@mapNotNull null
            val end = time(attr(p.groupValues[1], "end")) ?: start + 4_000
            // Backing vocals come wrapped in a span of their own; they're shown under the line.
            var background: String? = null
            val inner = BACKING.replace(p.groupValues[2]) { m ->
                val said = unescape(m.groupValues[1].replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()
                if (said.isNotEmpty()) background = listOfNotNull(background, said).joinToString(" ")
                ""
            }
            val voice = voices[attr(p.groupValues[1], "ttm:agent")] ?: VOICE_LEAD
            val spans = Regex("""<span\b([^>]*)>([^<]*)</span>(\s*)""").findAll(inner).mapNotNull { s ->
                val b = time(attr(s.groupValues[1], "begin")) ?: return@mapNotNull null
                val e = time(attr(s.groupValues[1], "end")) ?: b
                LyricWord(unescape(s.groupValues[2]) + s.groupValues[3].ifEmpty { "" }, b, e)
            }.filter { it.text.isNotBlank() }.toList()
            val text = if (spans.isNotEmpty()) spans.joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()
            else unescape(inner.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) return@mapNotNull null
            if (spans.size >= 2) LyricLine(start, end, text, spans, wordSynced = true, voice = voice, background = background, rtl = rtl)
            else LyricLine(start, end, text, LrcParser.spreadAcross(text, start, end), wordSynced = false, voice = voice, background = background, rtl = rtl)
        }.toList()
    }

    /**
     * The song's parts as Apple marks them on each `<div>`:
     * `itunes:song-part="Chorus"` (or `itunes:songPart`), with the div's own
     * begin and end, else those of the lines inside it.
     */
    fun parts(xml: String): List<SongPart> {
        val divs = Regex("""<div\b([^>]*)>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        return divs.mapNotNull { d ->
            val name = attr(d.groupValues[1], "itunes:song-part") ?: attr(d.groupValues[1], "itunes:songPart") ?: return@mapNotNull null
            val lines = Regex("""<p\b([^>]*)>""").findAll(d.groupValues[2]).map { it.groupValues[1] }.toList()
            val start = time(attr(d.groupValues[1], "begin")) ?: lines.firstNotNullOfOrNull { time(attr(it, "begin")) } ?: return@mapNotNull null
            val end = time(attr(d.groupValues[1], "end")) ?: lines.mapNotNull { time(attr(it, "end")) }.maxOrNull() ?: start
            SongPart(name, start, end)
        }.toList()
    }

    /** How long the recording these were timed to is: `<body dur>`, when it says. */
    fun length(xml: String): Long? = Regex("""<body\b[^>]*\bdur="([^"]+)"""").find(xml)?.groupValues?.get(1)?.let(::time)

    private val BACKING = Regex("""<span\b[^>]*ttm:role="x-bg"[^>]*>((?:\s*<span\b[^>]*>[^<]*</span>\s*)*)</span>""")

    /** The singers declared in the head (`<ttm:agent type xml:id>`), mapped as [LyricsPlus.voicesOf] does. */
    private fun voicesOf(xml: String): Map<String, Int> {
        var person = 0
        return Regex("""<ttm:agent\b([^>]*)>""").findAll(xml).mapNotNull { m ->
            val id = attr(m.groupValues[1], "xml:id") ?: return@mapNotNull null
            id to if (attr(m.groupValues[1], "type") == "group") VOICE_TOGETHER else if (person++ % 2 == 0) VOICE_LEAD else VOICE_SECOND
        }.toMap()
    }

    /** True for `tts:direction="rtl"` or a right-to-left `xml:lang`, false for `ltr`, null when neither says. */
    internal fun directionOf(attrs: String): Boolean? {
        when (attr(attrs, "tts:direction")) {
            "rtl" -> return true
            "ltr" -> return false
        }
        val lang = attr(attrs, "xml:lang")?.substringBefore('-')?.lowercase() ?: return null
        return lang in RTL_LANGUAGES
    }

    private val RTL_LANGUAGES = setOf("ar", "he", "iw", "fa", "ur", "yi", "ps", "sd", "ug", "dv", "ku", "ckb")

    private fun attr(attrs: String, name: String) = Regex("""\b$name="([^"]+)"""").find(attrs)?.groupValues?.get(1)

    /** "1:02.345", "00:01:02.345", "62.345s" or "62345ms". */
    internal fun time(t: String?): Long? {
        t ?: return null
        if (t.endsWith("ms")) return t.removeSuffix("ms").toDoubleOrNull()?.toLong()
        if (t.endsWith("s")) return t.removeSuffix("s").toDoubleOrNull()?.times(1000)?.toLong()
        val parts = t.split(':')
        val sec = parts.last().toDoubleOrNull() ?: return null
        val min = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: 0
        val hr = parts.getOrNull(parts.size - 3)?.toLongOrNull() ?: 0
        return ((hr * 3600 + min * 60) * 1000) + (sec * 1000).toLong()
    }

    private fun unescape(s: String) = s.replace("&apos;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
