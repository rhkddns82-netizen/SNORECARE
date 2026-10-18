SnoreCare — 코골이 감지 & 단계별 사운드 마스킹 (Android / Kotlin)
1. 아키텍처
```
app/src/main/java/com/example/snorecare/
├─ audio/     SoundEngine.kt        # 핑크/브라운/바이노럴 실시간 합성 + 믹서 + 페이드
├─ detect/    SnoreDetector.kt      # AudioRecord → RMS/주파수 대역비 → 코골이 프레임
│             SnoreStateMachine.kt  # 이벤트 집계 + 단계 전이 (순수 Kotlin, 테스트 용이)
├─ service/   SleepService.kt       # Foreground Service (마이크+오디오), WakeLock
├─ data/      (추가) Room: SleepSession, SnoreEvent, StageLog
├─ settings/  (추가) DataStore: 볼륨, 페이드, 민감도, 단계 유지시간
└─ ui/        (추가) Jetpack Compose: 메인(ON/OFF), 설정(슬라이더), 리포트(그래프)
```
데이터 흐름:
```
Mic ─► SnoreDetector ─Frame─► SnoreStateMachine ─Stage─► SoundEngine ─► Speaker/이어폰
                                    └────► Room(리포트 로그)
UI ◄── Flow(상태/리포트) ◄── Service     UI ─(설정)─► Detector/Machine/Engine
```
오디오 합성은 파일 재생이 아니라 실시간 생성이라 루프 끊김이 없고 용량이 0입니다.
모든 소스에 독립 게인 + 지수 평활을 둬서 단계 전환이 곧 크로스페이드가 됩니다.
`SnoreStateMachine`은 안드로이드 의존이 없어 가짜 Frame을 넣는 JVM 단위 테스트로 검증할 수 있습니다.
2. 상태 머신
현재	조건	다음
IDLE	60초 내 코골이 이벤트 ≥ 3회	PINK
PINK	hold(3분) 경과 + 여전히 코골이	BROWN
BROWN	hold 경과 + 여전히 코골이	BINAURAL
각 단계	10분간 코골이 없음	한 단계 하강 → IDLE
이벤트 = 저주파 우세 + 소음 바닥보다 큰 프레임이 0.3초 이상 연속된 구간.
뒤척임·기침은 짧거나 저주파 비중이 낮아 걸러지고, 3~6초 주기로 반복되는 호흡성 패턴만 횟수로 쌓입니다.
3. 반드시 알아둘 현실적 제약
자기 소리 피드백: 스피커의 브라운 노이즈를 마이크가 다시 잡아 오탐이 생길 수 있습니다. 코드는 소음 바닥을 적응 추적해 완화하지만, 실기기 튜닝이 필수입니다. 폰을 침대 근처에 두되 스피커와 마이크 방향을 분리하세요.
바이노럴 비트는 이어폰(스테레오 분리)이 필요합니다. 스피커로는 효과가 거의 없으므로, 이어폰 미연결 시 3단계는 브라운 노이즈를 유지하도록 분기하는 것을 권장합니다.
효과 근거: 노이즈 마스킹은 듣는 사람(옆 사람) 의 불편을 줄이는 목적에는 합리적이지만, 바이노럴 비트가 코골이 자체를 줄인다는 임상 근거는 약합니다. 앱 설명에서 "치료" 표현은 피하세요. 코골이가 심하거나 숨이 멈추는 느낌이 있으면 수면무호흡 검사를 안내하는 문구를 넣는 것이 좋습니다.
Android 14+: 마이크 Foreground Service는 화면이 보이는 상태(Activity)에서 시작해야 합니다. 제조사 배터리 최적화(삼성 등) 예외 안내 화면도 필요합니다.
프라이버시: 오디오 원본은 저장하지 않고 수치(dB, 이벤트 시각)만 기록하세요.
4. MVP 로드맵
프로젝트 세팅 — Android Studio, Kotlin, minSdk 26, Compose. Manifest 권한은 `AndroidManifest.snippet.xml` 참고.
오디오 엔진 단독 확인 — 버튼으로 `setStage(PINK/BROWN/BINAURAL)`을 눌러 소리, 페이드, 클리핑을 귀로 검증.
감지기 단독 확인 — 화면에 RMS dB / lowRatio / isSnore 실시간 표시. 코골이 녹음을 스피커로 틀어 `marginDb`, `lowRatioMin` 튜닝. (이 단계가 품질을 좌우합니다.)
상태 머신 연결 + 단위 테스트 — 가짜 Frame으로 전이 시나리오 테스트 (타이머는 시연용으로 hold/quiet을 초 단위로 줄여서).
Foreground Service 통합 — 수면 모드 ON/OFF, 알림, WakeLock, 권한 요청 플로우. 화면 끈 채 8시간 배터리/안정성 테스트.
설정 화면 — DataStore + 슬라이더(볼륨, 페이드 시간, 민감도, 단계 유지/조용 시간)를 Service에 실시간 반영.
수면 리포트 — Room에 이벤트/단계 로그 → 밤사이 코골이 횟수 + 단계 타임라인 차트(Vico/MPAndroidChart).
다듬기 — 이어폰 감지(AudioDeviceCallback), 알람/자동 종료, 베타 테스트로 임계값 기본값 보정.
