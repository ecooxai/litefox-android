package org.mozilla.reference.browser.browser

import android.app.Application
import android.content.Context
import android.view.View
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 37], application = Application::class)
class GeckoUrlPreviewTest {
    private lateinit var context: Context
    private lateinit var engine: Engine
    private lateinit var engineView: EngineView
    private lateinit var first: EngineSession
    private lateinit var second: EngineSession
    private lateinit var firstSettings: DefaultSettings

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        engine = mock(Engine::class.java)
        engineView = mock(EngineView::class.java)
        first = mock(EngineSession::class.java)
        second = mock(EngineSession::class.java)
        firstSettings = DefaultSettings()
        `when`(first.settings).thenReturn(firstSettings)
        `when`(second.settings).thenReturn(DefaultSettings())
        `when`(engine.createView(context, null)).thenReturn(engineView)
        `when`(engineView.asView()).thenReturn(View(context))
        `when`(engine.createSession(false, null)).thenReturn(first, second)
        `when`(engine.createSession(true, null)).thenReturn(first, second)
    }
    private fun listener(session: EngineSession): EngineSession.Observer {
        // Java captors return null as a matcher, which violates Kotlin's non-null
        // Observer contract before Mockito can record the verification.
        return mockingDetails(session).invocations.single { it.method.name == "register" }
            .arguments[0] as EngineSession.Observer
    }
    @Test fun privatePreviewsUsePrivateGeckoSessions() {
        val preview = GeckoUrlPreview(engine, context, true)
        preview.loadUrl("https://x.com")
        verify(engine).createSession(true, null)
        verify(engine, never()).createSession(false, null)
        verify(engineView).render(first)
        assertNull(firstSettings.userAgentString)
        assertNull(firstSettings.historyTrackingDelegate)
        assertTrue(firstSettings.suspendMediaWhenInactive)
        assertEquals("https://x.com", preview.requestedUrl)
        preview.close()
    }
    @Test fun regularPreviewsStayRegular() {
        val preview = GeckoUrlPreview(engine, context, false)
        preview.loadUrl("https://youtube.com")
        verify(engine).createSession(false, null)
        verify(engine, never()).createSession(true, null)
        preview.close()
    }
    @Test fun duplicateUrlDoesNotReloadOrAllocateAnotherSession() {
        val preview = GeckoUrlPreview(engine, context, false)
        preview.loadUrl("https://x.com")
        preview.loadUrl("https://x.com")
        verify(engine, times(1)).createSession(false, null)
        verify(engineView, times(1)).render(first)
        preview.close()
    }
    @Test fun replacementReleasesDisplayBeforeClosingOldSession() {
        val preview = GeckoUrlPreview(engine, context, false)
        preview.loadUrl("https://youtube.com")
        preview.loadUrl("https://x.com")
        val order = inOrder(engineView, first, second)
        order.verify(engineView).render(first)
        order.verify(engineView).release()
        order.verify(first).stopLoading()
        order.verify(first).close()
        order.verify(engineView).render(second)
        assertNull(preview.currentUrl)
        preview.close()
    }
    @Test fun staleCallbacksCannotOverwriteNewQueryOrClosedPreview() {
        val preview = GeckoUrlPreview(engine, context, false)
        preview.loadUrl("https://youtube.com")
        val old = listener(first)
        old.onLocationChange("https://m.youtube.com/", false)
        assertEquals("https://m.youtube.com/", preview.currentUrl)
        preview.loadUrl("https://x.com")
        old.onLocationChange("https://youtube.com/stale", false)
        assertNull(preview.currentUrl)
        val current = listener(second)
        current.onLocationChange("https://x.com/home", false)
        assertEquals("https://x.com/home", preview.currentUrl)
        preview.close()
        current.onLocationChange("https://x.com/late", false)
        assertNull(preview.currentUrl)
        verify(first).unregister(old)
        verify(second).unregister(current)
    }
    @Test fun closeIsIdempotent() {
        val preview = GeckoUrlPreview(engine, context, false)
        preview.loadUrl("https://x.com")
        preview.close()
        preview.close()
        verify(first, times(1)).close()
        verify(engineView, times(1)).release()
        assertNull(preview.requestedUrl)
    }
    @Test fun cannotLoadAfterClose() {
        val preview = GeckoUrlPreview(engine, context, true)
        preview.close()
        assertThrows(IllegalStateException::class.java) { preview.loadUrl("https://x.com") }
        verifyNoInteractions(first)
    }
    @Test fun renderFailureStillClosesNativeSession() {
        val preview = GeckoUrlPreview(engine, context, false)
        doThrow(IllegalStateException("display failed")).`when`(engineView).render(first)
        assertThrows(IllegalStateException::class.java) { preview.loadUrl("https://x.com") }
        verify(first).close()
        preview.close()
        verify(first, times(1)).close()
    }
}
