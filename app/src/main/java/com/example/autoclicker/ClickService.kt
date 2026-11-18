package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.random.Random

/**
 * บริการหลัก: วาดแผงควบคุม/จุดแตะเป็น overlay (ไม่ต้องขอสิทธิ์ overlay เพิ่ม)
 * และสั่งแตะ/ปัดผ่าน dispatchGesture
 *
 * ความปลอดภัยที่ฝังไว้:
 *  - รันได้เมื่อผู้ใช้กด ▶ เท่านั้น และมีเวลาสูงสุดบังคับเสมอ (ไม่มีโหมดไม่จำกัด)
 *  - หยุดทันทีเมื่อ: สลับแอป, ปิดหน้าจอ, เข้าหน้า Settings/ตัวติดตั้งแอป/ขอสิทธิ์, ปิดบริการ
 *  - ช่วงเวลาขั้นต่ำ 50ms, คลิกพลาดต่อเนื่องหลายครั้งจะหยุดเอง
 *  - ไม่อ่านเนื้อหาหน้าจอ ไม่มีอินเทอร์เน็ต ไม่เก็บข้อมูลใดๆ นอกจากค่าตั้งค่าในเครื่อง
 */
class ClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickService? = null
            private set

        /** หน้าที่ห้ามคลิกอัตโนมัติ เพื่อกันการกดอนุญาตสิทธิ์/ติดตั้งแอปโดยไม่ตั้งใจ */
        private val DENY = setOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.samsung.android.permissioncontroller",
            "com.miui.securitycenter",
            "com.android.vending"
        )
        private const val MAX_FAILS = 5
    }

    private class Marker(val view: TextView, val lp: WindowManager.LayoutParams)
    private class Step(val swipe: Boolean, val a: Marker, val b: Marker?)

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val steps = mutableListOf<Step>()
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var playBtn: TextView? = null

    private var cfg = Prefs.Config()
    private var running = false
    private var gen = 0
    private var idx = 0
    private var count = 0
    private var fails = 0
    private var startedAt = 0L
    private var currentPkg: String? = null
    private var lockedPkg: String? = null

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = stop("หยุด: ปิดหน้าจอ")
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, f)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return // หน้าต่างของแอปเราเอง (แผง/จุด)
        currentPkg = pkg
        if (!running) return
        if (pkg in DENY) { stop("หยุด: เข้าหน้าที่ห้ามคลิกอัตโนมัติ"); return }
        if (cfg.lockToApp) {
            val locked = lockedPkg
            if (locked == null) lockedPkg = pkg
            else if (pkg != locked) stop("หยุด: สลับแอป")
        }
    }

    override fun onInterrupt() = stop("หยุด: ระบบขัดจังหวะ")

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        stop(null)
        hidePanel()
        try { unregisterReceiver(screenOff) } catch (_: Exception) {}
        instance = null
    }

    // ---------------------------------------------------------------- engine

    fun start() {
        if (running) return
        if (steps.isEmpty()) { toast("กด ＋ เพื่อเพิ่มจุดก่อน"); return }
        cfg = Prefs.load(this)
        if (currentPkg in DENY) { toast("หน้านี้ห้ามคลิกอัตโนมัติเพื่อความปลอดภัย"); return }
        running = true
        gen++
        idx = 0; count = 0; fails = 0
        lockedPkg = if (cfg.lockToApp) currentPkg else null
        val delayMs = cfg.startDelaySec * 1000L
        startedAt = SystemClock.elapsedRealtime() + delayMs
        setMarkersTouchable(false)
        playBtn?.text = "⏸"
        toast("เริ่มใน ${cfg.startDelaySec} วินาที (หยุดสูงสุด ${cfg.maxRuntimeSec} วินาที)")
        val g = gen
        handler.postDelayed({ tick(g) }, delayMs)
    }

    fun stop(reason: String?) {
        if (!running) return
        running = false
        gen++
        handler.removeCallbacksAndMessages(null)
        setMarkersTouchable(true)
        playBtn?.text = "▶"
        saveSteps()
        reason?.let { toast(it) }
    }

    private fun tick(g: Int) {
        if (!running || g != gen) return
        if (SystemClock.elapsedRealtime() - startedAt >= cfg.maxRuntimeSec * 1000L) {
            stop("หยุด: ครบเวลาสูงสุด"); return
        }
        if (cfg.repeat > 0 && count >= cfg.repeat) { stop("เสร็จ: ครบจำนวนรอบ"); return }
        if (steps.isEmpty()) { stop(null); return }

        val gesture = try { gestureFor(steps[idx]) } catch (e: Exception) { stop("หยุด: สร้างคำสั่งไม่ได้"); return }
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { fails = 0; next(g) }
            override fun onCancelled(d: GestureDescription?) {
                if (++fails >= MAX_FAILS) stop("หยุด: คลิกไม่สำเร็จต่อเนื่อง") else next(g)
            }
        }, null)
        if (!sent) stop("หยุด: ระบบไม่รับคำสั่ง")
    }

    private fun next(g: Int) {
        if (!running || g != gen) return
        idx = (idx + 1) % steps.size
        if (idx == 0) count++
        val j = cfg.jitterMs
        val wait = (cfg.intervalMs + if (j > 0) Random.nextLong(-j, j + 1) else 0L)
            .coerceAtLeast(Prefs.MIN_INTERVAL_MS)
        handler.postDelayed({ tick(g) }, wait)
    }

    private fun gestureFor(s: Step): GestureDescription {
        val dm = resources.displayMetrics
        val j = cfg.posJitterPx
        fun jit() = if (j > 0) Random.nextInt(-j, j + 1) else 0
        fun cx(v: Float) = (v + jit()).coerceIn(1f, dm.widthPixels - 1f)
        fun cy(v: Float) = (v + jit()).coerceIn(1f, dm.heightPixels - 1f)

        val (ax, ay) = center(s.a)
        val b = s.b
        val p = Path()
        val dur: Long
        if (s.swipe && b != null) {
            val (bx, by) = center(b)
            p.moveTo(cx(ax), cy(ay))
            p.lineTo(cx(bx), cy(by))
            dur = cfg.swipeMs
        } else {
            p.moveTo(cx(ax), cy(ay))
            dur = cfg.holdMs
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur.coerceAtLeast(1)))
            .build()
    }

    private fun center(m: Marker): Pair<Float, Float> {
        val l = IntArray(2)
        m.view.getLocationOnScreen(l)
        return Pair(l[0] + m.view.width / 2f, l[1] + m.view.height / 2f)
    }

    // ---------------------------------------------------------------- overlay UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun overlayParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    fun showPanel() {
        if (panel != null) return
        cfg = Prefs.load(this)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.argb(225, 30, 30, 36)); cornerRadius = dp(24).toFloat()
            }
        }
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true)
        lp.x = dp(8); lp.y = dp(80)

        fun btn(t: String, onClick: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            setOnClickListener { onClick() }
        }

        // ที่จับลากแผง
        val handle = btn("⠿") {}.apply {
            setOnClickListener(null)
            setOnTouchListener(dragListener(lp) { bar })
        }
        bar.addView(handle)
        val play = btn("▶") { if (running) stop("หยุดแล้ว") else start() }
        playBtn = play
        bar.addView(play)
        bar.addView(btn("＋") { if (!running) addStep(false) })
        bar.addView(btn("⇄") { if (!running) addStep(true) })
        bar.addView(btn("－") { if (!running) removeLastStep() })
        bar.addView(btn("⚙") {
            stop(null)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        bar.addView(btn("✕") { hidePanel() })

        wm.addView(bar, lp)
        panel = bar
        panelLp = lp
        restoreSteps()
    }

    fun hidePanel() {
        stop(null)
        saveSteps()
        steps.forEach { removeMarker(it.a); it.b?.let(::removeMarker) }
        steps.clear()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; panelLp = null; playBtn = null
    }

    fun isPanelShown() = panel != null

    private fun dragListener(lp: WindowManager.LayoutParams, target: () -> View) = object : View.OnTouchListener {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                    try { wm.updateViewLayout(target(), lp) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> saveSteps()
            }
            return true
        }
    }

    private fun makeMarker(label: String, color: Int, x: Int, y: Int): Marker {
        val size = dp(52)
        val tv = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 14f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(170, Color.red(color), Color.green(color), Color.blue(color)))
                setStroke(dp(2), Color.WHITE)
            }
        }
        val lp = overlayParams(size, size, true).apply { this.x = x; this.y = y }
        tv.setOnTouchListener(dragListener(lp) { tv })
        wm.addView(tv, lp)
        return Marker(tv, lp)
    }

    private fun removeMarker(m: Marker) {
        try { wm.removeView(m.view) } catch (_: Exception) {}
    }

    private fun setMarkersTouchable(t: Boolean) {
        val all = steps.flatMap { listOfNotNull(it.a, it.b) }
        for (m in all) {
            m.lp.flags = if (t) m.lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else m.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            m.view.alpha = if (t) 1f else 0.35f
            try { wm.updateViewLayout(m.view, m.lp) } catch (_: Exception) {}
        }
    }

    private fun addStep(swipe: Boolean, ax: Int? = null, ay: Int? = null, bx: Int? = null, by: Int? = null) {
        if (steps.size >= 20) { toast("เพิ่มได้สูงสุด 20 จุด"); return }
        val dm = resources.displayMetrics
        val off = (steps.size % 8) * dp(14)
        val x = ax ?: (dm.widthPixels / 2 - dp(26) + off)
        val y = ay ?: (dm.heightPixels / 2 - dp(26) + off)
        val a = makeMarker("", if (swipe) Color.rgb(46, 160, 67) else Color.rgb(30, 110, 230), x, y)
        val b = if (swipe) makeMarker("", Color.rgb(230, 120, 20), bx ?: x, by ?: (y + dp(160))) else null
        steps.add(Step(swipe, a, b))
        refreshLabels(); saveSteps()
    }

    private fun removeLastStep() {
        if (steps.isEmpty()) return
        val s = steps.removeAt(steps.lastIndex)
        removeMarker(s.a); s.b?.let(::removeMarker)
        refreshLabels(); saveSteps()
    }

    private fun refreshLabels() {
        steps.forEachIndexed { i, s ->
            val n = i + 1
            if (s.swipe) { s.a.view.text = "${n}A"; s.b?.view?.text = "${n}B" } else s.a.view.text = "$n"
        }
    }

    private fun saveSteps() {
        val sb = StringBuilder()
        for (s in steps) {
            val b = s.b
            sb.append(if (s.swipe) "S" else "T").append(',')
                .append(s.a.lp.x).append(',').append(s.a.lp.y).append(',')
                .append(b?.lp?.x ?: 0).append(',').append(b?.lp?.y ?: 0).append(';')
        }
        Prefs.saveSteps(this, sb.toString())
    }

    private fun restoreSteps() {
        val raw = Prefs.loadSteps(this)
        for (part in raw.split(';')) {
            val f = part.split(',')
            if (f.size < 5) continue
            val x = f[1].toIntOrNull() ?: continue
            val y = f[2].toIntOrNull() ?: continue
            addStep(f[0] == "S", x, y, f[3].toIntOrNull(), f[4].toIntOrNull())
        }
    }
}
