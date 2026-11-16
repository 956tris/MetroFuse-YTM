package dev.metrofuse.youtube

import dev.metrofuse.sdk.acceptsBrowserNavigation
import dev.metrofuse.sdk.captureBrowserCookies
import dev.metrofuse.sdk.validateBrowserRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeLoginRequestTest {
    @Test
    fun browserAllowsGoogleAccountHandoffAndYouTubeSessionRedirects() {
        val request = YouTubeMusicPlugin().browserRequest()
        request.validateBrowserRequest()
        listOf(
            request.url,
            "https://accounts.google.co.uk/accounts/SetSID",
            "https://accounts.youtube.com/accounts/SetSID",
            "https://youtube.com/signin",
            "https://www.youtube.com/signin",
            "https://consent.youtube.com/",
            "https://consent.google.com/",
            "https://music.youtube.com/",
        ).forEach { assertTrue(request.acceptsBrowserNavigation(it), it) }
        assertFalse(request.acceptsBrowserNavigation("https://music.youtube.com.example.org/"))
        assertFalse(request.acceptsBrowserNavigation("http://music.youtube.com/"))
        assertFalse(request.acceptsBrowserNavigation("https://accounts.google.co.uk.example.org/accounts/SetSID"))
        assertFalse(request.acceptsBrowserNavigation("http://accounts.google.co.uk/accounts/SetSID"))
        assertNull(request.captureBrowserCookies("SID=unfinished-login"))
        assertTrue(request.captureBrowserCookies("__Secure-3PAPISID=verified-fixture") != null)
    }
}
