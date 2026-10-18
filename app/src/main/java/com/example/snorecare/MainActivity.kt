package com.example.snorecare

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.snorecare.service.SleepService

/** MVP 화면: 수면 모드 ON/OFF 버튼 하나. (설정/리포트 UI는 다음 단계) */
class MainActivity : Activity() {
    private var on = false
    private lateinit var btn: Button
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        status = TextView(this).apply {
            text = "수면 모드 꺼짐\n(바이노럴 비트는 이어폰 착용 시 효과)"
            textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.DKGRAY)
        }
        btn = Button(this).apply { text = "수면 모드 시작"; setOnClickListener { toggle() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48)
            addView(status); addView(btn)
        })
    }

    private fun toggle() {
        if (on) { stop(); return }
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else start()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else status.text = "마이크 권한이 필요합니다"
    }

    // 화면이 보이는 상태에서 시작해야 Android 14+ 마이크 FGS가 허용됨
    private fun start() {
        startForegroundService(Intent(this, SleepService::class.java))
        on = true; btn.text = "수면 모드 종료"; status.text = "수면 모드 작동 중\n코골이를 감지하면 자동으로 소리가 시작됩니다"
    }

    private fun stop() {
        startService(Intent(this, SleepService::class.java).setAction(SleepService.ACTION_STOP))
        on = false; btn.text = "수면 모드 시작"; status.text = "수면 모드 꺼짐"
    }
}
