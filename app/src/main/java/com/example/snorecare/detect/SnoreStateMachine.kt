package com.example.snorecare.detect

import com.example.snorecare.audio.Stage

data class Config(
    val triggerEvents: Int = 3,          // windowMs 안에 이 횟수 이상이면 '연속 코골이'
    val windowMs: Long = 60_000,
    val minEventMs: Long = 300,          // 이보다 짧은 소리(뒤척임·기침)는 무시
    val stageHoldMs: Long = 3 * 60_000,  // 각 단계 유지 후에도 지속되면 다음 단계로
    val quietMs: Long = 10 * 60_000      // 이 시간 조용하면 한 단계 내림
)

/**
 * 순수 Kotlin (안드로이드 의존 없음) → JVM 단위 테스트 가능.
 *   IDLE ─(연속 코골이)→ PINK ─(hold 후에도 지속)→ BROWN ─→ BINAURAL
 *   각 단계 ─(quiet 지속)→ 한 단계 아래 → … → IDLE
 */
class SnoreStateMachine(
    var cfg: Config = Config(),
    private val onStage: (Stage, Long) -> Unit
) {
    var stage = Stage.IDLE; private set
    private var stageEnterMs = 0L
    private var lastSnoreMs = 0L
    private var runStartMs = -1L
    private val events = ArrayDeque<Long>()

    fun onFrame(f: Frame) {
        val now = f.timeMs
        // 1) 연속된 코골이 프레임 → 하나의 이벤트
        if (f.isSnore) {
            if (runStartMs < 0) runStartMs = now
        } else if (runStartMs >= 0) {
            if (now - runStartMs >= cfg.minEventMs) { events.addLast(now); lastSnoreMs = now }
            runStartMs = -1
        }
        while (events.isNotEmpty() && now - events.first() > cfg.windowMs) events.removeFirst()
        val recent = events.size

        // 2) 상태 전이
        when (stage) {
            Stage.IDLE ->
                if (recent >= cfg.triggerEvents) goto(Stage.PINK, now)
            Stage.PINK, Stage.BROWN -> {
                val stillSnoring = recent >= cfg.triggerEvents
                if (stillSnoring && now - stageEnterMs >= cfg.stageHoldMs)
                    goto(if (stage == Stage.PINK) Stage.BROWN else Stage.BINAURAL, now)
            }
            Stage.BINAURAL -> Unit
        }
        // 3) 조용하면 단계 하강
        if (stage != Stage.IDLE && now - maxOf(lastSnoreMs, stageEnterMs) >= cfg.quietMs) {
            goto(when (stage) {
                Stage.BINAURAL -> Stage.BROWN
                Stage.BROWN -> Stage.PINK
                else -> Stage.IDLE
            }, now)
        }
    }

    private fun goto(next: Stage, now: Long) {
        if (next == stage) return
        stage = next; stageEnterMs = now
        onStage(next, now)      // → SoundEngine.setStage + 리포트 로그 기록
    }
}
