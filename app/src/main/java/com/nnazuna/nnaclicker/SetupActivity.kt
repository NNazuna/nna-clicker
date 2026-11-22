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

        val headerBg = if (p.dark) p.surface else p.ink
        val headerInk = if (p.dark) p.ink else p.bg
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(headerBg)
            setPadding(k.dp(16f), k.dp(26f), k.dp(16f), k.dp(18f))
        }
        header.addView(k.text("Setup", 34f, 3, headerInk))
        header.addView(k.text("Four steps to set up NNA Clicker.", 14f, 0, headerInk).apply {
            alpha = 0.75f
            setPadding(0, k.dp(4f), 0, 0)
        })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(16f), k.dp(12f), k.dp(16f), k.dp(40f))
        }
        list = k.group()
        root.addView(list)
        root.addView(k.pillButton("Done", p.ink) {
            storage.setupSeen = true
            finish()
        })

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        outer.addView(header)
        outer.addView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isVerticalScrollBarEnabled = false
            addView(root)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        setContentView(outer)
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
        var first = true
        fun addStep(view: View) {
            if (!first) list.addView(k.divider(40f))
            list.addView(view)
            first = false
        }

        val a11y = RecorderService.instance != null
        var n = 1

        if (Build.VERSION.SDK_INT >= 33) {
            addStep(card(
                n++, "Allow restricted settings",
                "App info › ⋮ (top right) › Allow restricted settings. Needed once, before the next step.",
                done = a11y, optional = false, action = if (a11y) null else "Open app info",
            ) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }

        addStep(card(
            n++, "Turn on NNA Clicker",
            "Accessibility › Installed apps › NNA Clicker › On",
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
            !installed -> Pair("Zero-delay recording and faster image steps.", "Get Shizuku")
            !running -> Pair("Installed but not running. Open it and press Start.", "Open Shizuku")
            !perm -> Pair("Running. Allow NNA Clicker to use it.", "Allow")
            else -> Pair("Ready to connect.", "Connect")
        }
        addStep(card(n++, "Shizuku", shzText, done = connected, optional = true, action = shzAction) {
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
        addStep(card(
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(k.dp(4f), k.dp(16f), k.dp(4f), k.dp(16f))
        }
        val mark: TextView = k.text(
            if (done) "✓" else number.toString().padStart(2, '0'),
            21f, 3, if (done) p.play else p.muted,
        ).apply { gravity = Gravity.CENTER }
        row.addView(mark, LinearLayout.LayoutParams(k.dp(44f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = k.dp(10f)
        })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(k.text(title, 16f, 2))
        if (optional) head.addView(k.text("OPTIONAL", 10f, 2, p.muted).apply {
            letterSpacing = 0.08f
            setPadding(k.dp(8f), 0, 0, 0)
        })
        col.addView(head)
        col.addView(k.text(body, 13.5f, 0, p.muted).apply { setPadding(0, k.dp(3f), 0, 0) })
        if (action != null) {
            col.addView(k.textButton("$action →", p.play) { onAction() }.apply {
                setPadding(0, k.dp(10f), k.dp(12f), 0)
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }
}
