package com.example.autoclicker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var lockSwitch: Switch

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Prefs.load(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        fun text(s: String, size: Float = 14f, bold: Boolean = false, color: Int = Color.DKGRAY) =
            TextView(this).apply {
                text = s; textSize = size; setTextColor(color)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(6))
            }

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { onClick() }
        }

        fun field(key: String, label: String, value: Long) {
            col.addView(text(label, 13f))
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
            }
            fields[key] = e
            col.addView(e)
        }

        col.addView(text("Auto Clicker", 26f, true, Color.BLACK))
        col.addView(
            text(
                "แอปนี้ใช้สิทธิ์ \"การช่วยเหลือพิเศษ (Accessibility)\" เพื่อสั่งแตะ/ปัดหน้าจอ ตามจุดที่คุณวางเองเท่านั้น\n" +
                    "• ไม่อ่านเนื้อหาบนหน้าจอ  • ไม่มีสิทธิ์อินเทอร์เน็ต  • ไม่เก็บ/ส่งข้อมูลใดๆ\n" +
                    "• ปิดสิทธิ์ได้ทุกเมื่อที่ ตั้งค่า > การช่วยเหลือพิเศษ"
            )
        )
        status = text("", 15f, true)
        col.addView(status)

        col.addView(button("1) เปิดบริการ Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("2) แสดงแผงควบคุมลอย") {
            saveFields()
            val s = ClickService.instance
            if (s == null) toast("ยังไม่ได้เปิดบริการ Accessibility")
            else { s.showPanel(); moveTaskToBack(true) }
        })
        col.addView(button("ซ่อนแผงควบคุม") { ClickService.instance?.hidePanel() })

        col.addView(text("\nตั้งค่า", 18f, true, Color.BLACK))
        field("interval", "ช่วงเวลาระหว่างการคลิก (มิลลิวินาที, ขั้นต่ำ 50)", cfg.intervalMs)
        field("jitter", "สุ่มเวลา ± (มิลลิวินาที)", cfg.jitterMs)
        field("posJitter", "สุ่มตำแหน่ง ± (พิกเซล)", cfg.posJitterPx.toLong())
        field("hold", "ระยะเวลากดค้างต่อการแตะ (มิลลิวินาที, ใส่ค่ามากเพื่อกดค้าง)", cfg.holdMs)
        field("swipe", "ระยะเวลาการปัด (มิลลิวินาที)", cfg.swipeMs)
        field("repeat", "จำนวนรอบ (0 = วนไปจนหมดเวลา)", cfg.repeat.toLong())
        field("maxRuntime", "หยุดอัตโนมัติเมื่อครบ (วินาที, สูงสุด 21600 = 6 ชม.)", cfg.maxRuntimeSec.toLong())
        field("delay", "หน่วงก่อนเริ่ม (วินาที)", cfg.startDelaySec.toLong())

        lockSwitch = Switch(this).apply {
            text = "หยุดทันทีเมื่อสลับไปแอปอื่น"
            isChecked = cfg.lockToApp
            setPadding(0, dp(12), 0, dp(12))
        }
        col.addView(lockSwitch)
        col.addView(button("บันทึกค่า") { saveFields(); toast("บันทึกแล้ว") })

        col.addView(text("\nวิธีใช้", 18f, true, Color.BLACK))
        col.addView(
            text(
                "1. เปิดบริการ Accessibility แล้วกลับมากด \"แสดงแผงควบคุมลอย\"\n" +
                    "2. ไปที่แอปที่ต้องการ กด ＋ เพื่อเพิ่มจุดแตะ (ลากวงกลมไปวางตำแหน่ง), ⇄ เพื่อเพิ่มการปัด (A=จุดเริ่ม, B=จุดจบ), － ลบจุดล่าสุด\n" +
                    "3. กด ▶ เพื่อเริ่ม / ⏸ เพื่อหยุด  ⚙ กลับมาหน้านี้  ✕ ปิดแผง\n" +
                    "4. จุดจะทำงานตามลำดับหมายเลข วนซ้ำ\n\n" +
                    "ระบบหยุดอัตโนมัติเมื่อ: ครบเวลา/รอบ, สลับแอป, ปิดหน้าจอ, เข้าหน้า Settings/ขอสิทธิ์/ติดตั้งแอป\n\n" +
                    "คำเตือน: อย่าใช้กับหน้าชำระเงิน/โอนเงิน/ยืนยันตัวตน และตรวจสอบกฎของเกม/บริการนั้นๆ ก่อนใช้ (บางเกมห้ามใช้ตัวช่วยคลิกและอาจแบนบัญชี)"
            )
        )
    }

    override fun onResume() {
        super.onResume()
        val on = ClickService.instance != null
        status.text = if (on) "สถานะ: เปิดบริการแล้ว ✓" else "สถานะ: ยังไม่ได้เปิดบริการ"
        status.setTextColor(if (on) Color.rgb(30, 130, 60) else Color.rgb(190, 50, 40))
    }

    override fun onPause() {
        saveFields()
        super.onPause()
    }

    private fun saveFields() {
        fun l(k: String, d: Long) = fields[k]?.text?.toString()?.trim()?.toLongOrNull() ?: d
        val d = Prefs.Config()
        val cfg = Prefs.Config(
            intervalMs = l("interval", d.intervalMs),
            jitterMs = l("jitter", d.jitterMs),
            posJitterPx = l("posJitter", d.posJitterPx.toLong()).toInt(),
            holdMs = l("hold", d.holdMs),
            swipeMs = l("swipe", d.swipeMs),
            repeat = l("repeat", d.repeat.toLong()).toInt(),
            maxRuntimeSec = l("maxRuntime", d.maxRuntimeSec.toLong()).toInt(),
            startDelaySec = l("delay", d.startDelaySec.toLong()).toInt(),
            lockToApp = lockSwitch.isChecked
        )
        Prefs.save(this, cfg)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
