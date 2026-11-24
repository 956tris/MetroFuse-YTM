package dev.metrofuse.youtube

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeCanvasTest {
    @Test
    fun matchesFollowsMetrolistTrackAndArtistRules() {
        assertTrue(
            YouTubeCanvas.matches(
                title = "Blinding Lights",
                artist = "The Weeknd",
                album = "After Hours",
                duration = 200_000L,
                foundTitle = "Blinding Lights",
                foundArtist = "The Weeknd",
                foundAlbum = "After Hours",
                foundDuration = 201_000L,
            ),
        )
        assertFalse(
            YouTubeCanvas.matches(
                title = "Blinding Lights",
                artist = "The Weeknd",
                album = "After Hours",
                duration = 200_000L,
                foundTitle = "Blinding Lights",
                foundArtist = "Another Artist",
                foundAlbum = "After Hours",
                foundDuration = 200_000L,
            ),
        )
        assertFalse(
            YouTubeCanvas.matches(
                title = "Blinding Lights",
                artist = "The Weeknd",
                album = "After Hours",
                duration = 200_000L,
                foundTitle = "Blinding Lights",
                foundArtist = "The Weeknd",
                foundAlbum = "After Hours",
                foundDuration = 230_000L,
            ),
        )
    }

    @Test
    fun trustedMediaAcceptsAppleHttpsHostsAndRejectsOthers() {
        assertTrue(YouTubeCanvas.trustedMedia("https://mvod.itunes.apple.com/itunes-assets/video.mp4"))
        assertTrue(YouTubeCanvas.trustedMedia("https://a1.mzstatic.com/us/r1000/video.mp4"))
        assertFalse(YouTubeCanvas.trustedMedia("http://mvod.itunes.apple.com/video.mp4"))
        assertFalse(YouTubeCanvas.trustedMedia("https://evil.example.com/video.mp4"))
        assertFalse(YouTubeCanvas.trustedMedia(null))
    }
}
