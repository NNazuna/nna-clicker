package com.nnazuna.nnaclicker

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Tile Quick Settings: ketuk untuk menampilkan / menyembunyikan panel. */
class PanelTileService : TileService() {

    companion object {
        /** Minta sistem memperbarui tampilan tile (dipanggil saat panel muncul/hilang). */
        fun refresh(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, PanelTileService::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val svc = RecorderService.instance
        if (svc == null) {
            // Layanan aksesibilitas belum aktif: buka pengaturannya.
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        if (svc.isPanelShown) svc.hidePanel() else {
            svc.showPanel()
            // Tutup panel notifikasi supaya panel mengambang langsung bisa dipakai.
            if (Build.VERSION.SDK_INT >= 31) {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }
        }
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val svc = RecorderService.instance
        tile.state = when {
            svc == null -> Tile.STATE_INACTIVE
            svc.isPanelShown -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "NNA Clicker"
        if (Build.VERSION.SDK_INT >= 29) {
            L.load(this)
            tile.subtitle = L.t(when {
                svc == null -> "Off"
                svc.isPanelShown -> "Panel on"
                else -> "Panel off"
            })
        }
        tile.updateTile()
    }
}
