package com.sync.app.glass

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * Frontend(WebView) <-> Native(Compose) channel.
 *
 *  JS  -> Kotlin : window.GlassBridge.push(json)   (DOM state mirror, rects in CSS px)
 *  Kotlin -> JS  : window.__glassCmd({...})        (taps / text input forwarded to the existing app.js functions)
 *
 * The WebView + app.js + AndroidBridge keep running exactly as before; they remain the single source of
 * truth for playback / search / playlists. Compose only renders the glass surfaces and their controls.
 */
class GlassController(private val webView: WebView) {

    var state by mutableStateOf(GlassState())
        private set

    /** WebView.getScrollY() (px) — updated on the UI thread in the same frame the page scrolls. */
    var nativeScrollPx by mutableIntStateOf(0)
        private set
    /** window.scrollY (CSS px) from JS — fallback only, used if the WebView never reports a native scroll. */
    private var jsScroll by mutableFloatStateOf(0f)
    private var nativeWorks = false

    // --- scroll tracking with latency compensation -------------------------------------------------------
    // WebView applies the new scroll offset on the render thread AFTER the UI thread recorded the frame, so the
    // value readable here is always ~1-2 frames old. We measure the scroll velocity and extrapolate by the
    // measured pipeline delay, which cancels the trailing without touching how the glass looks.
    private var lastY = 0
    private var lastNs = 0L
    private var velPxPerMs = 0f
    private var frameMs = 16f

    fun onNativeScroll(y: Int) {
        val now = System.nanoTime()
        val dt = (now - lastNs) / 1_000_000f
        if (dt in 1f..64f) {
            velPxPerMs = 0.5f * velPxPerMs + 0.5f * ((y - lastY) / dt)
            frameMs = 0.9f * frameMs + 0.1f * dt.coerceIn(4f, 34f)
        } else {
            velPxPerMs = 0f
        }
        lastY = y; lastNs = now
        if (y > 0) nativeWorks = true
        nativeScrollPx = y
    }

    @JavascriptInterface
    fun scroll(y: Float) { jsScroll = y }

    /** Current document scroll in CSS px (== dp). Reads snapshot state → callers re-run when it changes. */
    fun cssScroll(density: Float): Float {
        if (!nativeWorks) return jsScroll
        @Suppress("UNUSED_VARIABLE") val invalidateOnScroll = nativeScrollPx // snapshot read → re-run on every scroll event
        val live = webView.scrollY.toFloat()                                  // freshest value available on the UI thread
        val ageMs = (System.nanoTime() - lastNs) / 1_000_000f
        val lead = if (ageMs < 48f) velPxPerMs * frameMs * LEAD_FRAMES else 0f // 0 when the page is not moving
        return (live + lead).coerceAtLeast(0f) / density
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastJson = ""

    @JavascriptInterface
    fun push(json: String) {
        if (json == lastJson) return
        lastJson = json
        // parse on the JS bridge thread; only the already-built immutable state hops to the UI thread
        val parsed = try { GlassState.parse(json) } catch (_: Exception) { return }
        main.post { state = parsed }
    }

    fun cmd(build: JSONObject.() -> Unit) {
        val js = JSONObject().apply(build).toString()
        webView.post { webView.evaluateJavascript("window.__glassCmd&&window.__glassCmd($js)", null) }
    }

    private companion object {
        /** pipeline delay (in frames) between the page's real scroll and what we can read; tune 1.0–2.5 if needed */
        const val LEAD_FRAMES = 1.5f
    }
}
