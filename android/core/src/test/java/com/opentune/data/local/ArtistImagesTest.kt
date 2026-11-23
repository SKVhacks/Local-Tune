package com.opentune.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class ArtistImagesTest {
    private val cover = ByteArray(2000) { 1 }
    private val artist = ByteArray(1500) { 7 }

    private fun syncsafe(n: Int) = byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
    private fun be(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())

    private fun apic(type: Int, image: ByteArray, v4: Boolean): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(0); write("image/jpeg".toByteArray()); write(0); write(type); write("d".toByteArray()); write(0); write(image)
        }.toByteArray()
        return "APIC".toByteArray() + (if (v4) syncsafe(body.size) else be(body.size)) + byteArrayOf(0, 0) + body
    }

    private fun id3(v4: Boolean, vararg frames: ByteArray): ByteArray {
        val all = frames.fold(ByteArray(0)) { a, f -> a + f } + ByteArray(64)
        return "ID3".toByteArray() + byteArrayOf(if (v4) 4 else 3, 0, 0) + syncsafe(all.size) + all + ByteArray(100)
    }

    @Test fun findsTheArtistPictureInId3v23() {
        val file = id3(false, apic(3, cover, false), apic(8, artist, false))
        assertArrayEquals(artist, ArtistImages.picture(file.inputStream()))
    }

    @Test fun findsTheLeadArtistInId3v24() {
        val file = id3(true, apic(8, cover, true), apic(7, artist, true))
        assertArrayEquals(artist, ArtistImages.picture(file.inputStream()))
    }

    @Test fun aCoverOnlyGivesNothing() {
        assertNull(ArtistImages.picture(id3(false, apic(3, cover, false)).inputStream()))
    }

    @Test fun findsItInFlac() {
        fun picture(type: Int, image: ByteArray) = be(type) + be(10) + "image/jpeg".toByteArray() + be(0) + ByteArray(16) + be(image.size) + image
        fun block(type: Int, body: ByteArray, last: Boolean) = byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
        val file = "fLaC".toByteArray() + block(0, ByteArray(34), false) + block(6, picture(3, cover), false) + block(6, picture(8, artist), true) + ByteArray(50)
        assertArrayEquals(artist, ArtistImages.picture(file.inputStream()))
    }
}
