package com.example.snorecare.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.snorecare.audio.SoundEngine
import com.example.snorecare.detect.*

/** Foreground Service: 화면이 꺼져도 마이크 감지 + 오디오 재생 유지 */
class SleepService : Service() {
    private val engine = SoundEngine()
    private lateinit var detector: SnoreDetector
    private lateinit var machine: SnoreStateMachine
    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: Thread? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startInForeground()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "snorecare:sleep").apply { acquire(10 * 60 * 60 * 1000L) }

        engine.start()                         // 무음(게인 0)으로 대기
        machine = SnoreStateMachine { stage, t ->
            engine.setStage(stage)
            // TODO: Room DB에 (t, stage) 기록 → 수면 리포트 타임라인
        }
        detector = SnoreDetector { frame ->
            machine.onFrame(frame)
            // TODO: isSnore 프레임이면 코골이 카운트 증가
        }
        worker = Thread({ detector.run() }, "SnoreDetector").also { it.start() }
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("sleep", "수면 모드", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, SleepService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "sleep")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentTitle("수면 모드 작동 중").setOngoing(true)
            .addAction(0, "종료", stop).build()
        startForeground(1, n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onDestroy() {
        detector.stop(); worker?.join(500); engine.stop(); wakeLock?.release()
        super.onDestroy()
    }
    override fun onBind(p0: Intent?): IBinder? = null
    companion object { const val ACTION_STOP = "STOP" }
}
