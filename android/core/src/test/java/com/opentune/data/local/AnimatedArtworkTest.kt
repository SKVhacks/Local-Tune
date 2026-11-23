package com.opentune.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedArtworkTest {
    @Test
    fun recognisesTallAnimatedArtworkNames() {
        assertTrue(AnimatedArtwork.isTall("tall_animated_artwork.mp4"))
        assertTrue(AnimatedArtwork.isTall("Tall Animated Artwork.MP4"))
        assertTrue(AnimatedArtwork.isTall("blue - tall-animated-artwork.mov"))
        assertFalse(AnimatedArtwork.isTall("square_animated_artwork.mp4"))
        assertFalse(AnimatedArtwork.isTall("tall_animated_artwork.jpg"))
        assertFalse(AnimatedArtwork.isTall("cover.jpg"))
    }
}
