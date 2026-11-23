using Windows.Storage.Streams;

namespace LocalTune.Core;

/// <summary>
/// Exact jumps in FLAC files that have no seek table.
///
/// Without a seek table, Windows' FLAC reader guesses where a moment is from the file's average
/// bitrate: measured on the user's library, the sound lands anywhere from 3 s early to 4.4 s late,
/// different for every song and spot, and the position it reports is off by yet another amount
/// (by the size of the cover picture inside the file). So tapping a lyric line played near it,
/// but the lyrics couldn't know where.
///
/// With a seek table Windows lands within a few hundredths of a second and reports the right
/// time. The user's files are never changed: the song is read once (a fraction of a second on an
/// SSD), a table is made from its frame headers, and Windows is given the file with that table
/// added, through a stream that reads the rest straight from the file.
/// </summary>
public static class FlacSeek
{
    private sealed record Prepared(long Size, DateTime Written, byte[] Header, long AudioAt);

    private static readonly Dictionary<string, Prepared?> cache = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>The song as Windows should read it, or null to read the file as it is (it has a table, or isn't FLAC).</summary>
    public static IRandomAccessStream? Open(string path)
    {
        if (!path.EndsWith(".flac", StringComparison.OrdinalIgnoreCase)) return null;
        var info = new FileInfo(path);
        Prepared? p;
        lock (cache)
        {
            if (!cache.TryGetValue(path, out p) || (p != null && (p.Size != info.Length || p.Written != info.LastWriteTimeUtc)))
                p = null;
            else if (p == null) return null;
        }
        if (p == null)
        {
            p = Prepare(path, info);
            lock (cache) cache[path] = p;
            if (p == null) return null;
        }
        return new WithHeader(path, p.Header, p.AudioAt).AsRandomAccessStream();
    }

    /// <summary>Reads the file's frame headers and builds the new header (null: nothing needed, or not readable).</summary>
    private static Prepared? Prepare(string path, FileInfo info)
    {
        try
        {
            using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 1 << 16);
            var magic = new byte[4];
            fs.ReadExactly(magic);
            if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') return null;

            var blocks = new List<(int Type, byte[] Body)>();
            var head = new byte[4];
            while (true)
            {
                fs.ReadExactly(head);
                var type = head[0] & 0x7f;
                var length = (head[1] << 16) | (head[2] << 8) | head[3];
                var body = new byte[length];
                fs.ReadExactly(body);
                // A seek table with real points already: Windows can jump in this file as it is.
                if (type == 3 && HasPoints(body)) return null;
                blocks.Add((type, body));
                if ((head[0] & 0x80) != 0) break;
            }
            var audioAt = fs.Position;
            if (blocks.Count == 0 || blocks[0].Type != 0 || blocks[0].Body.Length < 18) return null;
            var si = blocks[0].Body;
            var rate = (si[10] << 12) | (si[11] << 4) | (si[12] >> 4);
            if (rate <= 0) return null;

            var points = Scan(fs, audioAt, rate);
            if (points.Count < 2) return null;

            var table = new byte[points.Count * 18];
            for (var i = 0; i < points.Count; i++)
            {
                var (sample, offset, frameSamples) = points[i];
                WriteBig(table, i * 18, sample, 8);
                WriteBig(table, i * 18 + 8, offset, 8);
                WriteBig(table, i * 18 + 16, frameSamples, 2);
            }
            // STREAMINFO, the new table, then the rest; the old empty table and the pictures are
            // left out (the app shows the cover itself, and pictures can be megabytes).
            var kept = new List<(int Type, byte[] Body)> { blocks[0], (3, table) };
            kept.AddRange(blocks.Skip(1).Where(b => b.Type != 3 && b.Type != 6 && b.Type != 1));
            using var ms = new MemoryStream();
            ms.Write("fLaC"u8);
            for (var i = 0; i < kept.Count; i++)
            {
                var (type, body) = kept[i];
                ms.WriteByte((byte)(type | (i == kept.Count - 1 ? 0x80 : 0)));
                ms.WriteByte((byte)(body.Length >> 16));
                ms.WriteByte((byte)(body.Length >> 8));
                ms.WriteByte((byte)body.Length);
                ms.Write(body);
            }
            return new Prepared(info.Length, info.LastWriteTimeUtc, ms.ToArray(), audioAt);
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static bool HasPoints(byte[] table)
    {
        for (var i = 0; i + 18 <= table.Length; i += 18)
            if (table[i] != 0xFF || table[i + 1] != 0xFF) return true; // 0xFFFF… marks an empty placeholder
        return false;
    }

    /// <summary>
    /// A point about every second: (first sample, bytes after the header, samples in that frame).
    /// Frames are followed one after another: each must start with the very sample the last one
    /// ended on, so a run of audio data that merely looks like a frame header is never taken.
    /// </summary>
    private static List<(long Sample, long Offset, int Samples)> Scan(FileStream fs, long audioAt, int rate)
    {
        var points = new List<(long, long, int)>();
        var buffer = new byte[1 << 20];
        long bufferAt = audioAt; // file position of buffer[0]
        var filled = 0;
        long expected = 0, nextSecond = 0;
        var fixedBlock = 0;
        var at = 0; // where in the buffer to look next
        var end = false;
        fs.Position = audioAt;
        while (true)
        {
            // Keep plenty ahead of the search: a frame header is at most 16 bytes.
            if (!end && filled - at < 64)
            {
                Array.Copy(buffer, at, buffer, 0, filled - at);
                bufferAt += at;
                filled -= at;
                at = 0;
                while (filled < buffer.Length)
                {
                    var n = fs.Read(buffer, filled, buffer.Length - filled);
                    if (n <= 0) { end = true; break; }
                    filled += n;
                }
            }
            var limit = filled - 16;
            if (at >= limit)
            {
                if (end) break;
                continue;
            }
            var found = Array.IndexOf(buffer, (byte)0xFF, at, limit - at);
            if (found < 0) { at = limit; continue; }
            if (Frame(buffer, found, ref fixedBlock) is { } f && f.Sample == expected)
            {
                if (f.Sample >= nextSecond * rate)
                {
                    points.Add((f.Sample, bufferAt + found - audioAt, f.Samples));
                    nextSecond = f.Sample / rate + 1;
                }
                expected = f.Sample + f.Samples;
                at = found + f.Length;
            }
            else at = found + 1;
        }
        return points;
    }

    /// <summary>A frame header at <paramref name="p"/>: its first sample, sample count and header length; null if not one.</summary>
    private static (long Sample, int Samples, int Length)? Frame(byte[] d, int p, ref int fixedBlock)
    {
        if (d[p] != 0xFF || (d[p + 1] & 0xFE) != 0xF8) return null;
        var variable = (d[p + 1] & 1) != 0;
        var sizeCode = d[p + 2] >> 4;
        var rateCode = d[p + 2] & 15;
        if (sizeCode == 0 || rateCode == 15) return null;
        var channels = d[p + 3] >> 4;
        var bits = (d[p + 3] >> 1) & 7;
        if (channels >= 11 || bits == 3 || (d[p + 3] & 1) != 0) return null;
        var q = p + 4;
        long number = d[q];
        var extra = 0;
        if (number >= 0x80)
        {
            if (number >= 0xFE || number < 0xC0) return null;
            extra = 1;
            while ((number & (0x80 >> (extra + 1))) != 0) extra++;
            number &= 0x7F >> (extra + 1);
            for (var k = 1; k <= extra; k++)
            {
                var y = d[q + k];
                if ((y & 0xC0) != 0x80) return null;
                number = (number << 6) | (long)(y & 0x3F);
            }
        }
        q += 1 + extra;
        int samples;
        if (sizeCode == 1) samples = 192;
        else if (sizeCode <= 5) samples = 576 << (sizeCode - 2);
        else if (sizeCode == 6) samples = d[q++] + 1;
        else if (sizeCode == 7) { samples = ((d[q] << 8) | d[q + 1]) + 1; q += 2; }
        else samples = 256 << (sizeCode - 8);
        if (rateCode == 12) q += 1;
        else if (rateCode is 13 or 14) q += 2;
        if (Crc8(d, p, q - p) != d[q]) return null;
        long sample;
        if (variable) sample = number;
        else
        {
            // Fixed-size blocks count frames; every frame but the last has the first one's size.
            if (fixedBlock == 0) fixedBlock = samples;
            sample = number * fixedBlock;
        }
        return (sample, samples, q + 1 - p);
    }

    private static readonly byte[] CrcTable = MakeCrcTable();

    private static byte[] MakeCrcTable()
    {
        var t = new byte[256];
        for (var i = 0; i < 256; i++)
        {
            var c = i;
            for (var k = 0; k < 8; k++) c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xFF : (c << 1) & 0xFF;
            t[i] = (byte)c;
        }
        return t;
    }

    private static byte Crc8(byte[] d, int from, int count)
    {
        byte c = 0;
        for (var i = from; i < from + count; i++) c = CrcTable[c ^ d[i]];
        return c;
    }

    private static void WriteBig(byte[] to, int at, long value, int bytes)
    {
        for (var i = bytes - 1; i >= 0; i--) { to[at + i] = (byte)value; value >>= 8; }
    }

    /// <summary>The new header, then the file's audio, read from the file as Windows asks for it.</summary>
    private sealed class WithHeader : Stream
    {
        private readonly byte[] header;
        private readonly long audioAt;
        private readonly FileStream file;
        private long position;

        public WithHeader(string path, byte[] header, long audioAt)
        {
            this.header = header;
            this.audioAt = audioAt;
            file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 1 << 16);
        }

        public override bool CanRead => true;
        public override bool CanSeek => true;
        public override bool CanWrite => false;
        public override long Length => header.Length + file.Length - audioAt;

        public override long Position
        {
            get => position;
            set => position = Math.Clamp(value, 0, Length);
        }

        public override int Read(byte[] buffer, int offset, int count)
        {
            lock (file)
            {
                var done = 0;
                if (position < header.Length)
                {
                    var n = (int)Math.Min(count, header.Length - position);
                    Array.Copy(header, position, buffer, offset, n);
                    position += n;
                    done = n;
                }
                if (done < count && position >= header.Length)
                {
                    file.Position = audioAt + position - header.Length;
                    var n = file.Read(buffer, offset + done, count - done);
                    position += n;
                    done += n;
                }
                return done;
            }
        }

        public override long Seek(long offset, SeekOrigin origin)
        {
            Position = origin switch
            {
                SeekOrigin.Begin => offset,
                SeekOrigin.Current => position + offset,
                _ => Length + offset,
            };
            return position;
        }

        public override void Flush() { }
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        protected override void Dispose(bool disposing)
        {
            if (disposing) file.Dispose();
            base.Dispose(disposing);
        }
    }
}
