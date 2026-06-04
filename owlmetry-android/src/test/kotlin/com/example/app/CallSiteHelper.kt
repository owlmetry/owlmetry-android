package com.example.app

import com.owlmetry.android.Owl

/**
 * Lives OUTSIDE the SDK package so [Owl]'s call-site capture (which skips
 * `com.owlmetry.android` frames) resolves `source_module` to this file — the
 * real-app scenario, where the caller is in the host app's package, not the
 * SDK's. Used by OwlLoggingTest.sourceModuleIsDerivedFromTheCallSite.
 */
object CallSiteHelper {
    fun emitInfo(message: String) = Owl.info(message)
}
