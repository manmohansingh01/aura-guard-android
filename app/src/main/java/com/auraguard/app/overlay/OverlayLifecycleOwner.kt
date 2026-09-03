package com.auraguard.app.overlay

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Minimal Lifecycle / ViewModelStore / SavedStateRegistry owner for a
 * ComposeView attached directly to a WindowManager overlay window from a
 * Service. There is no Activity or Fragment backing an overlay window, so
 * Compose's internals (LocalLifecycleOwner, rememberSaveable, etc.) need
 * something standing in for one — this is the standard, minimal shape of
 * that stand-in.
 *
 * [OverlayController] drives this manually (performRestore once, then
 * ON_CREATE/ON_START/ON_RESUME when the overlay windows go up, and the
 * matching ON_PAUSE/ON_STOP/ON_DESTROY when they come down) — it is
 * intentionally NOT tied to the host Service's own lifecycle, since the
 * overlay's lifetime is "while capture + overlay permission both hold",
 * which is a decision OverlayController's start()/stop() already make.
 */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun performRestore(bundle: Bundle?) {
        savedStateRegistryController.performRestore(bundle)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}
