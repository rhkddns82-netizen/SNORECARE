package com.example.snorecare.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

enum class Stage { IDLE, PINK, BROWN, BINAURAL }

/** 핑크 노이즈 (Paul Kellet 근사 필터) */
class PinkNoise {
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    private var b3 = 0f; private var b4 = 0f; private var b5 = 0f; private var b6 = 0f
    fun next(): Float {
        val w = Random.nextFloat() * 2f - 1f
        b0 = 0.99886f * b0 + w * 0.0555179f
        b1 = 0.99332f * b1 + w * 0.0750759f
        b2 = 0.96900f * b2 + w * 0.1538520f
        b3 = 0.86650f * b3 + w * 0.3104856f
        b4 = 0.55000f * b4 + w * 0.5329522f
        b5 = -0.7616f * b5 - w * 0.0168980f
        val out = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f) * 0.11f
        b6 = w * 0.115926f
        return out
    }
}

/** 브라운 노이즈 (누설 적분기) */
class BrownNoise {
    private var last = 0f
    fun next(): Float {
        val w = Random.nextFloat() * 2f - 1f
        last = (last + 0.02f * w) / 1.02f
        return (last * 3.5f).coerceIn(-1f, 1f)
    }
}

/** 바이노럴 비트: 좌/우 귀에 주파수가 다른 사인파 (이어폰 필수) */
class BinauralBeat(private val sampleRate: Int) {
    var carrierHz = 200.0
    var beatHz = 5.0            // 델타 0.5~4Hz, 세타 4~8Hz
    private var pl = 0.0; private var pr = 0.0
    fun next(out: FloatArray) {
        out[0] = sin(pl).toFloat(); out[1] = sin(pr).toFloat()
        pl += 2 * PI * carrierHz / sampleRate
        pr += 2 * PI * (carrierHz + beatHz) / sampleRate
        if (pl > 2 * PI) pl -= 2 * PI
        if (pr > 2 * PI) pr -= 2 * PI
    }
}

/**
 * 실시간 합성 + 믹싱. 각 소스에 독립 게인이 있고, 목표 게인으로 1차 지수 평활
 * (= 부드러운 페이드/크로스페이드) 되므로 단계 전환 시 갑작스러운 소리가 없다.
 */
class SoundEngine(private val sampleRate: Int = 44100) {
    @Volatile var masterVolume = 0.4f          // 0..1, 설정 화면에서 조절
    @Volatile var fadeSeconds = 8f             // 페이드 시간 (설정)
    @Volatile var binauralBeatHz = 5.0

    private val pink = PinkNoise(); private val brown = BrownNoise()
    private val bin = BinauralBeat(sampleRate)
    private var gPink = 0f; private var gBrown = 0f; private var gBin = 0f
    @Volatile private var tPink = 0f; @Volatile private var tBrown = 0f; @Volatile private var tBin = 0f

    private var track: AudioTrack? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    fun setStage(stage: Stage) {
        val v = masterVolume
        when (stage) {
            Stage.IDLE     -> { tPink = 0f; tBrown = 0f; tBin = 0f }
            Stage.PINK     -> { tPink = v; tBrown = 0f; tBin = 0f }
            Stage.BROWN    -> { tPink = 0f; tBrown = v; tBin = 0f }
            Stage.BINAURAL -> { tPink = 0f; tBrown = v * 0.5f; tBin = v * 0.6f } // 브라운을 깔아 마스킹 유지
        }
    }

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuf, 8192) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track!!.play()
        running = true
        thread = Thread({ renderLoop() }, "SoundEngine").also { it.start() }
    }

    private fun renderLoop() {
        val frames = 1024
        val buf = ShortArray(frames * 2)
        val tmp = FloatArray(2)
        while (running) {
            // 시간상수 ≈ fadeSeconds/3 → fadeSeconds 안에 ~95% 도달
            val coef = (3f / (fadeSeconds.coerceAtLeast(0.5f) * sampleRate))
            bin.beatHz = binauralBeatHz
            for (i in 0 until frames) {
                gPink += (tPink - gPink) * coef
                gBrown += (tBrown - gBrown) * coef
                gBin += (tBin - gBin) * coef
                val p = if (gPink > 0.0005f) pink.next() * gPink else 0f
                val b = if (gBrown > 0.0005f) brown.next() * gBrown else 0f
                var l = p + b; var r = p + b
                if (gBin > 0.0005f) { bin.next(tmp); l += tmp[0] * gBin; r += tmp[1] * gBin }
                buf[2 * i]     = (l.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                buf[2 * i + 1] = (r.coerceIn(-1f, 1f) * 32767).toInt().toShort()
            }
            track?.write(buf, 0, buf.size)   // blocking write = 자연스러운 페이싱
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        track?.run { stop(); release() }
        track = null
    }
}
