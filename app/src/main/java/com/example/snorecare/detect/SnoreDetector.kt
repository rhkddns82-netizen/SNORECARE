package com.example.snorecare.detect

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

data class Frame(val timeMs: Long, val rmsDb: Double, val lowRatio: Double, val isSnore: Boolean)

/**
 * 16kHz 모노 마이크 → 64ms 프레임 → (1) RMS dB (2) 저주파/고주파 에너지 비 (Goertzel)
 * 코골이 프레임 = 적응형 소음 바닥보다 margin dB 이상 크고 + 저주파(100~800Hz) 비중이 높음.
 * 반복 패턴(호흡 주기 3~6초)은 SnoreStateMachine에서 '이벤트 횟수'로 판단한다.
 */
class SnoreDetector(
    private val sampleRate: Int = 16000,
    private val onFrame: (Frame) -> Unit
) {
    @Volatile var marginDb = 12.0       // 민감도 슬라이더 (작을수록 민감)
    @Volatile var lowRatioMin = 0.55
    @Volatile var minAbsoluteDb = -45.0 // 이보다 작으면 무조건 무음 처리
    @Volatile private var running = false
    private var noiseFloorDb = -60.0

    private val lowFreqs = doubleArrayOf(100.0, 150.0, 200.0, 300.0, 400.0, 600.0, 800.0)
    private val highFreqs = doubleArrayOf(1500.0, 2000.0, 3000.0, 4000.0, 6000.0)

    @SuppressLint("MissingPermission") // RECORD_AUDIO는 호출 전에 확보
    fun run() {
        val n = 1024
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, n * 4))
        val pcm = ShortArray(n)
        running = true
        rec.startRecording()
        try {
            while (running) {
                var read = 0
                while (read < n && running) {
                    val r = rec.read(pcm, read, n - read)
                    if (r <= 0) break
                    read += r
                }
                if (read < n) continue
                onFrame(analyze(pcm, n))
            }
        } finally { rec.stop(); rec.release() }
    }

    fun stop() { running = false }

    private fun analyze(x: ShortArray, n: Int): Frame {
        var sum = 0.0
        for (i in 0 until n) { val s = x[i] / 32768.0; sum += s * s }
        val db = 20 * log10(sqrt(sum / n) + 1e-9)

        val low = lowFreqs.sumOf { goertzel(x, n, it) }
        val high = highFreqs.sumOf { goertzel(x, n, it) }
        val ratio = low / (low + high + 1e-12)

        val loud = db > noiseFloorDb + marginDb && db > minAbsoluteDb
        val snore = loud && ratio > lowRatioMin
        // 코골이가 아닐 때만 소음 바닥을 천천히 추적 (재생 중인 노이즈/선풍기 소리 보정)
        if (!snore) noiseFloorDb += (db - noiseFloorDb) * 0.01
        return Frame(System.currentTimeMillis(), db, ratio, snore)
    }

    private fun goertzel(x: ShortArray, n: Int, f: Double): Double {
        val k = 2 * cos(2 * PI * f / sampleRate)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until n) { val s0 = x[i] / 32768.0 + k * s1 - s2; s2 = s1; s1 = s0 }
        return (s1 * s1 + s2 * s2 - k * s1 * s2) / (n.toDouble() * n)
    }
}
