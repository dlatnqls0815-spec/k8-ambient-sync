# 인수인계 (Codex·Claude 공용)

기준: **2026-11-18**, 브랜치 `codex/device-evidence`(원격 `dlatnqls0815-spec/k8-ambient-sync`, main 병합·강제 Push 금지).
이 문서를 먼저 읽고 `AGENTS.md` → `docs/PROGRESS.md`(위쪽이 최신) → `docs/DECISIONS.md` 순으로 본다.
`NEXT_PARKED_VISIT.md`, 예전 `CLAUDE_HANDOFF.md` 내용, PROGRESS 아래쪽은 **지난 기록**이다(A안 GATT 시험 등은 이미 끝남).
Codex와 Claude 모두 이 프로젝트 폴더에서 작업한다. 동기화와 세션 교대 절차는 [AGENTS.md](../AGENTS.md)를 따른다.

## 한 줄 요약

2021 K8 GL3 HEV(Gen5W, Android 4.4.2/API19, NEMESIS 1.16.4)의 순정 앰비언트를 **B안**으로 사제 조명 2개에 동기화하는 데 성공했다:
차량 NEMESIS 중계(로그 읽기 → 클래식 RFCOMM) → Galaxy 중계 앱 → BLE로 LotusLamp X + Zengge(LEDnetWF).
A안(헤드유닛 직접 BLE)은 스택에 GATT 클라이언트가 없어 **종료**. C안(ESP32)은 2026-11-17~18 실차 시도 후 **철회**(PROGRESS·DECISIONS 2026-11-18).

## 현재 설치 상태 (실차에서 동작 확인)

| 위치 | 버전 | 산출물 |
|---|---|---|
| 차량 NEMESIS `k8ambient.relay` | **0.4.2** (0.3.5 코드 + 시동 시 Wi-Fi 자동 켜기, 2026-11-18 23:49 설치, 시동 후 동작 미확인). 롤백: 커밋 4459f46에서 0.4.1 재빌드 | `private/nemesis-relay/k8ambient.relay-0.4.2.zip` |
| 휴대폰 `io.github.k8ambient.phone` (Galaxy Z Fold) | **0.5.2** (code 20, 내용은 0.4.8과 같음 — ESP32 기능 제거), 2026-11-18 USB 설치 | `../../deliverables/k8-ambient-phone-relay-0.5.2.apk` |

0.4.4는 시동 점등 페이드, 과속 경고 종료 후 복귀 페이드, Zengge의 평소 색 즉시 전송과 경고 전송 수 표시를 포함한다.
0.4.6은 시동 직후 차량 재접속 대기(최대 30초)를 없앴다(차량 ACL 연결 시 즉시 재시도). 0.4.5는 시동 뒤 Lotus가 수십 초 반응하지 않던 문제(혼잡 쓰기에 링크를 끊고, 남은 링크에 바로 재연결)를 고쳤다. 시동 재시험은 아직.
0.4.2~0.4.4 기능(첫 점등 페이드, 경고 종료 복귀, Zengge 경고)은 일부만 실차 확인(0.4.1까지는 확인됨).

사용자가 확인한 동작: 색·밝기·테마 추종, 꺼짐(Lotus 전원 프레임 옵션), 부드러운 전환·톤, Zengge 색(빨강·초록 순서 보정),
Lotus의 과속 카메라 빨강 깜빡임, 드라이브 모드 연동(스포츠·에코·스마트), 시동 껐다 켜면 에코 색 유지, 시동 시 앱 자동 시작.

## 동작 구조와 근거

**차량 중계 `car-relay/` (DEX를 `app_process`로 실행, 차량 명령 없음·읽기 전용)**
- `logcat -b main -b system -v time -s SystemService:V LightService:D`를 읽는다.
- `SYS_SML_STATUS_M_RESP` 9바이트: RGB 16비트×3(상위 0x00=직접색, 0x01=테마), byte6 밝기 raw(1=꺼짐, 2~11=1~10단계),
  byte7 주행 중 감광(mode 8), byte8 드라이브 모드 연동(02 켜짐/01 꺼짐, mode 9).
- 응답보다 약 0.3초 먼저 찍히는 명령 줄(`setSMLRGB`, `setSMLConfiguration` mode 5/8/9)로 예상 상태를 먼저 보냄(`RelayProtocol.predictStatus`), 실제 응답이 바로잡음.
- 과속 카메라: LightService `NaviCameraAlertState, mTempCameraAlertState : 1/0` → `ALERT 1/0`.
- 드라이브 모드: `SYS_DRIVE_MODE_DATA_M_RESP` byte 2 = **0x02 에코, 0x03 스포츠, 0x06 스마트**(사용자 확인). 0x00(변경 직후)·0x0f(부팅 직후)는 모드 아님.
  시동 `SYS_IGN_STATUS_M: 0 → 1`이면 에코로 바꿈(차가 매번 에코로 돌아가지만 모드 줄이 없음).
  시동 직후 20초 안, 다른 모드 보고 전에 오는 0x06은 차량의 잘못된 보고라 무시(계기판은 에코였음).
- 재시작 때 자기 `relay.log`의 마지막 `state`·`mode` 줄로 복원(차량은 변할 때만 보고). 헤드유닛 부팅 4분 이내면 에코로 시작.
- 줄 프로토콜(UUID `6b38a0d2-4f7e-4c1b-9d5a-2e8f71c3b904`): `K8AMB 1`, `SML <hex>`, `ALERT 1/0`, `MODE <hex>`, `PING`.
- 과속 분석용 기록기: `/data/nemesis/logs/k8ambient.relay/overspeed*.log`(16MB×6, root 전용 → `nemesis root -c cat`).
- 엔진 사운드용 텔레메트리 RFCOMM 서버 "K8 Telemetry"(`TelemetryProtocol`, `TEL <kmh> <park> <rev> <mode>`) — `research/gps-engine-sound` 담당.

**휴대폰 앱 `phone-relay/`** (Android 13+, Gradle 없이 `tools/build_phone_relay.py`)
- 조명마다 스레드·`Fader`·`SyncEngine`. Lotus: FFF0/FFF3, 색 `7e 07 05 03 RR GG BB 10 ef`, 밝기 `7e 04 01 lv 01 ff 02 01 ef`, 전원 ON/OFF 프레임.
  Zengge(제품 0xA3, BLE v5): FFFF/FF01, 8바이트 헤더 + `41 01 R G B 00 00 00 01 00 00 f0 chk`, 밝기는 RGB에 곱함, 꺼짐=검정, 기본 색 순서 GRB.
- `ColorTone`(감마 1.8, 파랑 85%), 최대 밝기 Lotus 20%·Zengge 100%, 전환 기본 300ms, BLE 연결 우선순위 HIGH.
  0.4.4에서는 Zengge 평소 색과 경고 펄스를 바로 전송한다. 첫 점등 약 600ms와 경고 종료 후 색 복귀 페이드는 적용한다.
- `DriveModeLight`: 연동이 켜졌을 때만 에코 (50,154,158)·스포츠 (255,0,0), 스마트는 사용자 색. 과속 경고가 우선.
- `AutoStartReceiver`: 차량 ACL 연결·부팅·업데이트 때 자동 시작("중계 시작"을 눌러 둔 경우만, 배터리 "제한 없음" 상태).

## 남은 일 (우선순위 순)

1. **0.4.4의 Zengge 응답과 과속 경고 확인**. 이전 관측에서 Lotus만 깜빡였다. 증상이 다시 나오면 정차 후 시각, Zengge MTU,
   앱의 ‘Zengge 경고 전송 (켜짐 / 꺼짐)’ 수를 받아 HCI와 대조한다. 시동 점등과 경고 종료 페이드도 같은 사용 중 확인한다.
2. **직접 고른 색 + 연동 켜짐에서 모드 전환이 안 바뀐다는 사용자 보고**: 기록으로 재현 못 함(그 시간대엔 테마였음). 다시 보이면 시각을 받아 HCI·relay.log 대조.
3. 에코 "리프레싱 오션"의 정확한 RGB 미확정(테마 `0132019a019e` 색으로 추정). 사용자가 그 테마를 고르면 상태값에서 바로 확인 가능.
4. 엔진 사운드 앱(0.9.3, "K8 차량" 입력)의 텔레메트리 연결을 실주행에서 확인(`telemetry client connected`).
5. C안(ESP32)은 철회·정리 끝(차량 0.4.1, 휴대폰 0.5.2, 헤드유닛 ESP32 페어링 삭제). 근거는 PROGRESS·DECISIONS 2026-11-18.
6. 정리 후보: 차량의 시험용 BLE 점검 패키지 제거, `relay.log` 크기 제한(현재 무제한 증가), 차량 `persist.service.adb.tcp.enable=1`(무선 AA 충돌 이력, 사용자 판단 대기·임의 변경 금지).

## 검사·빌드·설치

```bash
JAVA_HOME="/c/Users/dlatn/OneDrive/문서/ChatGPT/kia 자동잠금/analysis/tools/.toolchain/jdk17" python tools/check.py
```
0.4.4 기준: 코어 335, Python 89 통과·1 건너뜀, host 14, 진단 모의 37, BLE probe 24, 차량 중계 API 19 컴파일.

- 차량 중계: `python tools/build_api19_dex.py --target relay --d8 <build-tools>/lib/d8.jar --jdk <JDK17>` → `python tools/build_nemesis_relay.py`
  (버전은 `tools/build_nemesis_relay.py`의 `PACKAGE_VERSION`). DEX 빌드가 실패해도 zip은 옛 DEX로 만들어지니 `dex_sha256`을 확인.
- 휴대폰: `python tools/build_phone_relay.py --sdk C:/Users/dlatn/.local/share/android-sdk --jdk <JDK17>` (버전은 `phone-relay/AndroidManifest.xml`, 서명키 `private/phone-relay/`).
- adb: `C:/Users/dlatn/.local/share/android-sdk/platform-tools/adb.exe`. 차량은 무선 ADB(포트 5555, 주소는 사용자에게 받는다 — 커밋 금지), 휴대폰은 USB.
- 차량 설치(사용자 승인 후): **먼저 logcat에서 속도 0 확인**(`getSpeed_KMHUnit() = 0.0` 또는 `SYS_SPEED_M: 0`) →
  Git Bash는 `MSYS_NO_PATHCONV=1`로 `adb push <zip> /data/local/tmp/` → `nemesis install --update <zip>` → 설치된 DEX `md5` 비교 → relay.log에 `started`·`phone connected` 확인 → 임시 zip 삭제.
- 휴대폰 설치: `adb -s <serial> install -r private/phone-relay/k8-ambient-phone-relay.apk`.
- 휴대폰 진단 기록(0.4.7+): `adb -s <serial> pull /sdcard/Android/data/io.github.k8ambient.phone/files/relay-events.log` (사용자가 알려 준 실패 시각 전후를 차량 relay.log와 대조).
- 휴대폰 Bluetooth 증거: `adb bugreport` → `FS/data/log/bt/btsnoop_hci.log*` → `.tools/wireshark/.../tshark.exe`(캡처 시각이 9시간 앞서 있음).

## 지킬 것

- 차량 설치·시스템 변경·CAN/LIN/Binder 송신·조명 BLE 쓰기 실험은 사용자 승인 후, 차량 명령 전엔 로그로 정차 확인.
- `private/`, `.tools/`, `captures/`, 원본 로그·APK·서명키·VIN·MAC·IP·휴대폰 serial은 커밋·외부 전송 금지.
- 사용자는 한국어로 소통하고 차에 반복해서 가는 것을 부담스러워한다. PC 분석을 먼저 하고 현장 확인은 묶어서.
- 외부 AI 자문은 MiniMax만, 실제 로그·비밀 전송 금지. Codex 로그인·모델·MCP 설정은 바꾸지 않는다.
