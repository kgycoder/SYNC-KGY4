package com.sync.app.glass

import android.view.ViewGroup
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

object GlassHost {

    /**
     * Moves the existing WebView into a Compose tree (so it can act as the Kyant `layerBackdrop` source)
     * and layers the native Liquid Glass UI above it. Call once from onCreate, before the page is loaded.
     */
    @JvmStatic
    fun install(activity: AppCompatActivity, webView: WebView) {
        val parent = webView.parent as ViewGroup
        val index = parent.indexOfChild(webView)
        parent.removeView(webView)

        val controller = GlassController(webView)
        webView.addJavascriptInterface(controller, "GlassBridge")
        webView.setOnScrollChangeListener { _, _, y, _, _ -> controller.onNativeScroll(y) }

        val compose = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { GlassRoot(webView, controller) }
        }
        parent.addView(
            compose, index,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }
}
