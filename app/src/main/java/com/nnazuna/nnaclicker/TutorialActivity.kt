package com.nnazuna.nnaclicker

import android.app.Activity
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/** A short paged guide: record, play, edit, image steps, repeats, and the run log. */
class TutorialActivity : Activity() {

    private class Page(val icon: Icon, val title: String, val body: String)

    private val pages = listOf(
        Page(Icon.PLAY, "Welcome to NNA Clicker",
            "NNA Clicker repeats what you do on the screen: taps, swipes, and more. This short guide shows the basics in about a minute."),
        Page(Icon.RECORD, "1. Record",
            "Tap Show panel, then open your game. Press the red button on the floating panel, do your taps and swipes, then press stop. The recording is saved and selected."),
        Page(Icon.PAUSE, "2. Play, pause, stop",
            "Press play on the panel. Use pause to take a break and continue from the same step. Press stop, or Volume Down, to end at any time. Set Repeat and Speed under Playback on the main screen."),
        Page(Icon.LIST, "3. Edit",
            "Tap Edit on a recording to change delays, move or delete steps, and add new ones with Add step. Use Play from here in a step's ⋮ menu to test one part."),
        Page(Icon.CAMERA, "4. Image steps",
            "Press the camera button while recording to add an image step. In the editor, draw a box around a button or icon. Playback can then wait for it, tap it wherever it is, or wait until it's gone."),
        Page(Icon.JUMP, "5. Repeat and choose",
            "Open ⋮ on a step and choose Repeat steps… to run a part several times. Image steps can choose what runs next when something is found or not, for example: tap Retry when you lose."),
        Page(Icon.IMAGE, "6. When something goes wrong",
            "If an image isn't found, playback stops and the screen is saved in Run log. Open it to see what was on screen and fix the step with one tap."),
        Page(Icon.TAP, "Ready",
            "No time to record? Use Auto clicker on the main screen to repeat a tap. You can open this guide again from Tutorial on the main screen."),
    )

    private lateinit var k: Kit
    private val p get() = k.p
    private var index = 0
    private lateinit var iconBox: FrameLayout
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var dots: LinearLayout
    private lateinit var back: TextView
    private lateinit var next: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = Kit(this)
        Ui.styleWindow(this, p)
        Storage(this).tutorialSeen = true

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(24f), k.dp(16f), k.dp(24f), k.dp(28f))
            setBackgroundColor(p.bg)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        top.addView(k.textButton("Skip", p.muted) { finish() })
        root.addView(top)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        iconBox = FrameLayout(this).apply { background = k.rounded(p.surface, 48f) }
        content.addView(iconBox, LinearLayout.LayoutParams(k.dp(96f), k.dp(96f)).apply {
            topMargin = k.dp(24f); bottomMargin = k.dp(28f)
        })
        title = k.text("", 24f, 2).apply { gravity = Gravity.CENTER }
        content.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body = k.text("", 16f, 0, p.muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            setPadding(0, k.dp(14f), 0, 0)
        }
        content.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = k.dp(20f)
        })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        back = k.textButton("Back", p.play) { go(index - 1) }.apply {
            setPadding(k.dp(8f), k.dp(14f), k.dp(8f), k.dp(14f))
        }
        bar.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        next = k.pillButton("Next", p.play) { if (index < pages.size - 1) go(index + 1) else finish() }
        bar.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        root.addView(bar)

        // Swipe left or right to change pages.
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || abs(vx) < 600) return false
                go(if (vx < 0) index + 1 else index - 1)
                return true
            }
        })
        content.setOnTouchListener { _, e -> swipe.onTouchEvent(e); true }

        setContentView(root)
        go(0)
    }

    private fun go(i: Int) {
        if (i !in pages.indices) return
        index = i
        val pg = pages[i]
        iconBox.removeAllViews()
        iconBox.addView(k.icon(pg.icon, if (pg.icon == Icon.RECORD) p.rec else p.play, 44f),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        title.text = L.t(pg.title)
        body.text = L.t(pg.body)
        back.visibility = if (i == 0) View.INVISIBLE else View.VISIBLE
        next.text = L.t(if (i == pages.size - 1) "Start" else "Next")
        dots.removeAllViews()
        for (d in pages.indices) {
            dots.addView(View(this).apply { background = k.rounded(if (d == i) p.play else p.line, 4f) },
                LinearLayout.LayoutParams(k.dp(if (d == i) 20f else 8f), k.dp(8f)).apply { setMargins(k.dp(3f), 0, k.dp(3f), 0) })
        }
    }
}
