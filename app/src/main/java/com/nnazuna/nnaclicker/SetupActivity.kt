package com.nnazuna.nnaclicker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** First-run checklist: each step shows whether it's done and a button to do it. */
class SetupActivity : Activity() {

    private companion object {
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    }

    private lateinit var storage: Storage
    private lateinit var k: Kit
    private val p get() = k.p
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        k = Kit(this)
        Ui.styleWindow(this, p)
        ShizukuBridge.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(16f), k.dp(28f), k.dp(16f), k.dp(40f))
        }
        root.addView(k.text("Setup", 32f, 2).apply { setPadding(k.dp(4f), 0, 0, k.dp(6f)) })
        root.addView(k.text("A few switches so NNA Clicker can tap for you.", 15f, 0, p.muted).apply {
            setPadding(k.dp(4f), 0, 0, k.dp(10f))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(k.pillButton("Done", p.play) {
            storage.setupSeen = true
            finish()
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        ShizukuBridge.onChanged = { runOnUiThread { render() } }
        RecorderService.onChanged = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() {
        super.onPause()
        ShizukuBridge.onChanged = null
        RecorderService.onChanged = null
    }

    private fun shizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo(SHIZUKU_PKG, 0); true
    } catch (e: Exception) {
        false
    }

    private fun render() {
        list.removeAllViews()
        val a11y = RecorderService.instance != null
        var n = 1

        if (Build.VERSION.SDK_INT >= 33) {
            list.addView(card(
                n++, "Allow restricted settings",
                "App info › ⋮ (top right) › Allow restricted settings. Needed once, before the next step.",
                done = a11y, optional = false, action = if (a11y) null else "Open app info",
            ) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }

        list.addView(card(
            n++, "Turn on NNA Clicker",
            "Accessibility › Installed apps › NNA Clicker › On.",
            done = a11y, optional = false, action = if (a11y) null else "Open accessibility",
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        val installed = shizukuInstalled()
        val running = ShizukuBridge.isRunning()
        val perm = ShizukuBridge.hasPermission()
        val connected = storage.useShizuku && ShizukuBridge.isConnected
        val (shzText, shzAction) = when {
            connected -> Pair("Connected. Recording has no delay.", null)
            !installed -> Pair("Optional. Zero-delay recording and faster image steps.", "Get Shizuku")
            !running -> Pair("Installed but not running. Open it and press Start.", "Open Shizuku")
            !perm -> Pair("Running. Allow NNA Clicker to use it.", "Allow")
            else -> Pair("Ready to connect.", "Connect")
        }
        list.addView(card(n++, "Shizuku", shzText, done = connected, optional = true, action = shzAction) {
            when {
                !installed -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                !running -> packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)?.let { startActivity(it) }
                !perm -> ShizukuBridge.requestPermission()
                else -> {
                    storage.useShizuku = true
                    ShizukuBridge.connect()
                }
            }
            render()
        })

        val panelShown = RecorderService.instance?.isPanelShown == true
        list.addView(card(
            n, "Show the panel",
            "Record with the red button, play with the triangle. Volume Down stops.",
            done = panelShown, optional = false, action = if (!a11y) null else if (panelShown) null else "Show panel",
        ) {
            RecorderService.instance?.showPanel()
            render()
        })
    }

    private fun card(
        number: Int, title: String, body: String, done: Boolean, optional: Boolean,
        action: String?, onAction: () -> Unit,
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = k.rounded(p.surface, 16f)
            setPadding(k.dp(14f), k.dp(14f), k.dp(14f), k.dp(12f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = k.dp(10f) }
        }
        val badge: TextView = k.text(if (done) "✓" else "$number", 15f, 2, if (done) (if (p.dark) 0xFF0F1312.toInt() else 0xFFFFFFFF.toInt()) else p.ink).apply {
            gravity = Gravity.CENTER
            background = k.rounded(if (done) p.play else p.bg, 16f)
        }
        card.addView(badge, LinearLayout.LayoutParams(k.dp(32f), k.dp(32f)).apply { rightMargin = k.dp(14f) })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(k.text(title, 17f, 2))
        if (optional) head.addView(k.text("Optional", 12.5f, 1, p.muted).apply {
            setPadding(k.dp(8f), k.dp(2f), k.dp(8f), k.dp(2f))
            background = k.rounded(p.bg, 10f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = k.dp(8f) })
        col.addView(head)
        col.addView(k.text(body, 14f, 0, p.muted).apply { setPadding(0, k.dp(4f), 0, 0) })
        if (action != null) {
            col.addView(k.textButton(action, p.play) { onAction() }.apply {
                setPadding(0, k.dp(10f), k.dp(12f), k.dp(4f))
            })
        }
        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }
}
