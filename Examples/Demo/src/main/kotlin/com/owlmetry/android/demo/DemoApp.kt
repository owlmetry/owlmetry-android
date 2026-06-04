package com.owlmetry.android.demo

import android.app.Application
import android.util.Log
import com.owlmetry.android.Owl

/**
 * Application subclass that configures the Owlmetry SDK once, at process start —
 * the Android analog of the Swift demo's `OwlmetryDemoApp.init()`.
 *
 * The endpoint is `http://10.0.2.2:4000`: on the Android emulator, `10.0.2.2` is
 * a special alias that routes to the host machine's `localhost`, so this reaches
 * the dev server running on the host's port 4000. (On a physical device, swap
 * this for your machine's LAN IP.)
 *
 * The API key is the seeded demo client key (`owl_client_demo_…`), identical to
 * the one the Swift demo uses, so both demos report into the same "Demo Project".
 */
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Owl.configure(
                context = this,
                endpoint = "http://10.0.2.2:4000",
                apiKey = "owl_client_demo_000000000000000000000000000000000000000000",
            )
        } catch (e: Throwable) {
            // Mirrors the Swift demo's do/catch — a bad endpoint or key shouldn't
            // crash the demo; it just logs and continues with the SDK un-configured
            // (every Owl call then warns once and no-ops).
            Log.e("OwlmetryDemo", "Owlmetry configuration failed", e)
        }
    }
}
