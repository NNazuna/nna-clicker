package com.nnazuna.nnaclicker

import android.graphics.Point
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * Base for the overlays [RecorderService] shows on top of other apps. Gives them the
 * service's window manager, storage and helpers so their code reads like it did inside the service.
 */
internal abstract class ServiceOverlay(protected val svc: RecorderService) {
    protected val wm: WindowManager get() = svc.wm
    protected val storage: Storage get() = svc.storage
    protected val handler get() = svc.handler
    protected val panel: LinearLayout? get() = svc.panel
    protected val COLOR_PLAY get() = OverlayPalette.PLAY
    protected val COLOR_REC get() = OverlayPalette.REC

    protected fun dp(v: Float): Int = svc.dp(v)
    protected fun overlayParams(w: Int, h: Int, flags: Int): WindowManager.LayoutParams = svc.overlayParams(w, h, flags)
    protected fun screenSize(): Point = svc.screenSize()
    protected fun toast(msg: String) = svc.toast(msg)
}

/** Colors shared by the floating overlays (always dark, they sit on top of any app). */
internal object OverlayPalette {
    const val PLAY = 0xFF5FD3BC.toInt()
    const val REC = 0xFFF2545F.toInt()
    const val SURFACE = 0xFF1C2220.toInt()
    const val RAISED = 0xFF252D2A.toInt()
    const val INK = 0xFFF1F4F2.toInt()
    const val MUTED = 0x99FFFFFF.toInt()
    const val ON_ACCENT = 0xFF0E1412.toInt()
    const val OUTLINE = 0x33FFFFFF
}
