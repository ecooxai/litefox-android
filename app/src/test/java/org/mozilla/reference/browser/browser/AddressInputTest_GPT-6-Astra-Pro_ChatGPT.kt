package org.mozilla.reference.browser.browser

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 37], application = Application::class)
class AddressInputTest {
    @Test fun bareYoutubeAndXAreAddresses() {
        for (host in listOf("youtube.com", "www.youtube.com", "m.youtube.com", "x.com", "twitter.com", "github.com")) {
            assertEquals(host, "https://$host", AddressInput.urlOrNull(host))
        }
    }
    @Test fun pathsQueriesAndFragmentsArePreserved() {
        for (value in listOf("youtube.com/watch?v=test&list=abc#t=20", "x.com/example/status/123", "example.com/a%20b?q=x%2Fy")) {
            assertEquals("https://$value", AddressInput.urlOrNull(value))
        }
    }
    @Test fun explicitUrlsAndCaseArePreserved() {
        for (value in listOf("https://x.com", "http://example.com/a", "HTTPS://www.youtube.com/watch?v=123")) {
            assertEquals(value, AddressInput.urlOrNull("  $value  "))
        }
    }
    @Test fun searchPhrasesStaySearchPhrases() {
        for (value in listOf("", "   ", "youtube", "x", "youtube music", "how to open x.com", "hello world.com", "1 + 2", "user@example.com")) {
            assertNull(value, AddressInput.urlOrNull(value))
        }
    }
    @Test fun unsafeSchemesAndInvalidHostsAreNotNavigated() {
        for (value in listOf("javascript:alert(1)", "data:text/html,hello", "intent://x.com/#Intent;end", "ftp://x.com", "https://", "https://bad host.com", "https://user:pass@x.com", "x.com\n/path", "http://x.com\u0000/path", "x.com:99999", "x.com:abc", "x.com:", "x.com:0", "-bad.com", "a..com", "x.com\\evil")) {
            assertNull(value, AddressInput.urlOrNull(value))
        }
    }
    @Test fun localAndInternationalAddressesWork() {
        for (value in listOf("localhost:8000/test", "127.0.0.1:8080/", "[::1]:8000/", "example.photography", "example.internal", "example.test", "x.com:443", "xn--bcher-kva.de", "b\u00fccher.de")) {
            assertEquals(value, "https://$value", AddressInput.urlOrNull(value))
        }
    }
    @Test fun internalAddressesRemainUsable() {
        for (value in listOf("about:blank", "about:privatebrowsing", "about:config", "file:///tmp/test.html", "content://example/doc/1", "moz-extension://example/index.html")) {
            assertEquals(value, AddressInput.urlOrNull(value))
        }
    }
    @Test fun pendingPreviewCannotReplaceNewAddress() {
        assertEquals("https://x.com", AddressInput.previewTarget("https://x.com", "https://youtube.com", "https://m.youtube.com/"))
        assertEquals("https://x.com", AddressInput.previewTarget("https://x.com", "https://x.com", null))
        assertEquals("https://x.com", AddressInput.previewTarget("https://x.com", "https://x.com", "about:blank"))
        assertEquals("https://x.com", AddressInput.previewTarget("https://x.com", "https://x.com", "javascript:alert(1)"))
    }
    @Test fun currentPreviewCanKeepItsRedirect() {
        assertEquals("https://www.youtube.com/", AddressInput.previewTarget("https://youtube.com", "https://youtube.com", "https://www.youtube.com/"))
    }
}
