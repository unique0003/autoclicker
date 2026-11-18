package com.example.autoclicker

import android.content.Context

/** ค่าตั้งค่าทั้งหมด + ขอบเขตความปลอดภัย (ทุกค่าถูก clamp เสมอ) */
object Prefs {
    const val MIN_INTERVAL_MS = 50L          // ห้ามเร็วกว่านี้
    const val MAX_RUNTIME_LIMIT_SEC = 6 * 3600 // เพดานสูงสุด 6 ชม. (ไม่มีโหมดรันไม่จำกัด)

    data class Config(
        var intervalMs: Long = 300,
        var jitterMs: Long = 30,
        var posJitterPx: Int = 3,
        var holdMs: Long = 50,
        var swipeMs: Long = 300,
        var repeat: Int = 0,              // 0 = วนซ้ำจนกว่าจะหมดเวลา
        var maxRuntimeSec: Int = 600,     // หยุดเองเมื่อครบ (ค่าเริ่มต้น 10 นาที)
        var startDelaySec: Int = 3,
        var lockToApp: Boolean = true     // หยุดทันทีถ้าสลับแอป
    ) {
        fun sanitized() = copy(
            intervalMs = intervalMs.coerceIn(MIN_INTERVAL_MS, 3_600_000L),
            jitterMs = jitterMs.coerceIn(0, intervalMs.coerceAtLeast(MIN_INTERVAL_MS) / 2),
            posJitterPx = posJitterPx.coerceIn(0, 30),
            holdMs = holdMs.coerceIn(1, 10_000),
            swipeMs = swipeMs.coerceIn(50, 10_000),
            repeat = repeat.coerceIn(0, 1_000_000),
            maxRuntimeSec = maxRuntimeSec.coerceIn(5, MAX_RUNTIME_LIMIT_SEC),
            startDelaySec = startDelaySec.coerceIn(0, 60)
        )
    }

    private const val FILE = "autoclicker"

    fun load(c: Context): Config {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = Config()
        return Config(
            intervalMs = p.getLong("interval", d.intervalMs),
            jitterMs = p.getLong("jitter", d.jitterMs),
            posJitterPx = p.getInt("posJitter", d.posJitterPx),
            holdMs = p.getLong("hold", d.holdMs),
            swipeMs = p.getLong("swipe", d.swipeMs),
            repeat = p.getInt("repeat", d.repeat),
            maxRuntimeSec = p.getInt("maxRuntime", d.maxRuntimeSec),
            startDelaySec = p.getInt("delay", d.startDelaySec),
            lockToApp = p.getBoolean("lock", d.lockToApp)
        ).sanitized()
    }

    fun save(c: Context, cfg: Config) {
        val s = cfg.sanitized()
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong("interval", s.intervalMs).putLong("jitter", s.jitterMs)
            .putInt("posJitter", s.posJitterPx).putLong("hold", s.holdMs)
            .putLong("swipe", s.swipeMs).putInt("repeat", s.repeat)
            .putInt("maxRuntime", s.maxRuntimeSec).putInt("delay", s.startDelaySec)
            .putBoolean("lock", s.lockToApp).apply()
    }

    /** รูปแบบ: kind,x,y,x2,y2;kind,... (kind: T=แตะ, S=ปัด) */
    fun loadSteps(c: Context): String =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("steps", "") ?: ""

    fun saveSteps(c: Context, s: String) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("steps", s).apply()
    }
}
