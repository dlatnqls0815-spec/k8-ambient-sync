# 진행 상황

## 2026-11-20 현대·기아·제네시스 Gen5W 타 차종 확장(차종 프로필·적응형 밝기·원터치 모드 학습) — 휴대폰 0.5.6 (빌드 완료, 실차 확인 전)

- 목적: 기존 K8 GL3 HEV 전용 기본값과 100% 호환을 유지하면서, EV6·아이오닉5·6 및 기타 현대·기아·제네시스 Gen5W 차종에서도 코드 수정 없이 앱 설정만으로 순정 앰비언트를 연동할 수 있게 확장.
- 코어(Java 7):
  - `VehicleProfile`: 3종 프리셋(`K8_GL3` 기본, `EV6_IONIQ`, `GEN5W_AUTO`), 커스텀 밝기 원시값 범위(`offRaw`, `minOnRaw`, `maxOnRaw`, `allowAdaptiveRange`), 커스텀 드라이브 모드 바이트 코드(`ecoModeCode`, `sportModeCode`, `normalModeCode`) 및 역할 충돌 자동 해소(`withAssignedModeCode`) 추가.
  - `K8LotusMapping`: 기본 생성자는 `VehicleProfile.K8_GL3` 그대로 유지(기존 단위 테스트 100% 호환). `K8LotusMapping(VehicleProfile)` 생성자에서 `1~11`, `0~10`, `0~100` 등 임의 범위를 선형 정규화하며, `allowAdaptiveRange=true`일 때 범위 밖 원시값이 들어오면 무시하지 않고 안전하게 꺼짐/최대값 자동 확장 학습(`effectiveMaxOnRaw()`)으로 정규화.
  - `DriveModeLight`: 기존 API 유지 + `setModeColor`/`clearModeColor`/`fromProfile`/`linked(status, profile)`/`apply(mapped, status, mode, profile)` 추가.
- 차량 중계(`car-relay` 0.4.5 점검): `SYS_SML_STATUS_M_RESP`·`SYS_DRIVE_MODE_DATA_M_RESP` 파싱과 NEMESIS 패키지(`os=any`, `models=any`)가 이미 K8 외 Gen5W 차종의 `0x01..0x0e, 0x10..0xff` 드라이브 모드 코드를 모두 통과시키므로 불필요한 변경 없이 `0.4.5` 유지.
- 휴대폰 앱(0.5.6, code 24):
  - `Prefs`·`RelayStatus`·`RelayService`: 차종 프로필·커스텀 밝기 범위·커스텀 드라이브 모드 코드 저장 및 실행 중 즉시 재반영(`ACTION_RELOAD_THEMES`), 현재 수신 중인 `brightnessRaw`와 `effectiveMaxRaw` 표시.
  - `MainActivity`: `차종 프로필` 선택 버튼, `밝기 원시값 범위 설정` 다이얼로그(`1~11`/`0~10`/`0~100` 빠른 선택 + 직접 입력 + 자동 적응 토글), 현재 수신 드라이브 모드 코드(`0x..`)를 `에코`/`스포츠`/`일반(사용자색)`에 지정하는 원터치 버튼 3종 추가.
- 검사: `tools/check.py --android-jar ...` 통과(Python 89통과·1건너뜀, 코어 476, 호스트 14, 진단 모의 37, BLE probe 24, 차량 중계 API 19 컴파일).
- 빌드: 휴대폰 APK `0.5.6` (`apksigner verify` 통과, sha256 `52ff8c5d…07b00a`) → `../../deliverables/k8-ambient-phone-relay-0.5.6.apk`. K8 및 타 Gen5W 차종 실차 검증은 대기.

## 2026-11-20 범용 사제 엠비언트 등록(RGB 설정·실시간 테스트) — 휴대폰 0.5.5 (빌드 완료, 실차 확인 전)

- 목적: 기존 하드코딩된 Lotus·Zengge 외에 다른 사제 BLE 엠비언트도 앱에서 쉽게 추가·연동할 수 있도록 지원.
- 코어(Java 7): `CustomRgbTemplate`(`RR`/`GG`/`BB` 자리표시자, `SQ` 시퀀스, `CS`/`CS:n` 합계 체크섬, `XR`/`XR:n` XOR 체크섬, 1개 고유 RGB 샘플 및 2~3개 샘플 기반 패킷 자동 추출), `CustomLampProfile`(8종 사제 프로토콜 프리셋, UUID 정규화/자동 추천, 조명별 색 순서·RGB 비율·최대 밝기·즉시전송·응답쓰기 설정 및 저장 직렬화) 추가.
- 휴대폰 앱(0.5.5, code 23):
  - `BleLampProbe`: BLE 기기 GATT 쓰기 가능 특성 자동 탐색 및 등록 전 실시간 RGB 테스트 전송.
  - `GattTransport`: 커스텀 UUID, 선택적 Notify, 패킷 길이 기반 동적 MTU, `WRITE_TYPE_DEFAULT`/`WRITE_TYPE_NO_RESPONSE` 자동 선택 지원.
  - `Prefs`·`RelayStatus`·`RelayService`: 기존 Lotus·Zengge 설정 100% 유지 + 다수의 커스텀 사제 조명 독립 스레드 구동 + `ACTION_RELOAD_LAMPS` 즉시 재적용.
  - `MainActivity`: `+ 사제 엠비언트 추가 (BLE 스캔 · RGB 등록)`, `등록된 사제 엠비언트 관리`, 샘플 RGB 자동 추출 마법사, 빨강/초록/파랑/사용자 RGB 실시간 테스트 UI 추가.
- 검사: `tools/check.py --android-jar ...` 통과(Python 89통과·1건너뜀, 코어 438, 호스트 14, 진단 모의 37, BLE probe 24, 차량 중계 API 19 컴파일).
- 빌드: 휴대폰 APK `0.5.5` (`apksigner verify` 통과, sha256 `51c8eaa9…acfdf`). 실차·휴대폰 설치 및 실조명 검증은 대기.

## 2026-11-20 순정 색상 응답 기준 동기화

- 사용자는 사제 조명만 빨강이 되고 원래 색으로 돌아오지 않는다고 보고했다. 0.4.5 차량 중계는 RGB 요청을 무시하고 상태 응답만 색에 반영한다. 이전 로그의 요청 예측도 재시작 복원에 사용하지 않는다.
- 0.5.4 휴대폰 중계는 독립 마이 드라이브 스포츠 덮어쓰기를 제거했다. `MYDRIVE`는 진단에 표시한다. 순정 연동 옵션의 모드 팔레트와 카메라 경고는 유지한다.
- 기본 검사 Python 89통과·1건너뜀, 코어 396, 호스트 14, 진단 모의 37, BLE probe 24 및 차량 API 19 컴파일 통과. 패키지와 APK를 빌드하고 검증했다. 실차 미설치.

## 마이 드라이브 스포츠 빨강 옵션 — 2026-11-20 (구현, 실차 미확인)

- 목적: 마이 드라이브(`MODE 06`)에서 엔진·서스펜션·핸들 항목 중 하나라도 스포츠(5)일 때(또는 스포츠 모드 `MODE 03`) 사제 조명을
  스포츠 색(기본 255,0,0)으로 표시하고, 모두 비스포츠로 돌아오면 평소 색으로 복귀한다(기존 전환 페이드 유지, 과속 경고 우선, 순정 드라이브 모드 연동 여부 무관).
- 코어: `RelayProtocol`에 `mIndividualModeFeedback: [0] a [1] b [2] c` 파서와 `MYDRIVE <a> <b> <c>` 인코더·디코더 추가(구버전 휴대폰은 무시).
  순수 규칙 클래스 `MyDriveSportLight`와 `TestMain` 단위 테스트 추가.
- 차량 중계(0.4.4): `CarRelay`가 `mIndividualModeFeedback` 변경 시에만 `MYDRIVE a b c` 전송, 휴대폰 접속 직후 재전송,
  자체 `relay.log`의 `mydrive` 줄로 재시작 복원(읽기 전용 유지, 차량 명령·Binder 호출 없음).
- 휴대폰 앱(0.5.3, code 21): `Prefs.MY_DRIVE_SPORT`(기본 꺼짐), `MainActivity` 스위치와 상태 표시(`RelayStatus.myDrive`),
  `RelayService`의 `MYDRIVE` 수신(`DiagLog.event("rx", ...)`)·설정 재반영(`reloadThemes`)·`DriveModeLight` 뒤 `MyDriveSportLight` 적용.
- 구현은 Gemini 4 Neon(High)이 `private/` 없는 작업 사본에서 했고, Claude가 검수했다(스포츠 모드는 피드백을 받기 전에도 빨강으로 수정).
- 검사: `tools/check.py` 통과(코어 423, Python 90·1 건너뜀, host 14, 진단 모의 37, BLE probe 24, 차량 중계 API 19 컴파일).
- 빌드: 차량 `k8ambient.relay-0.4.4.zip`(dex_sha256 7d8fdc56…a504f), 휴대폰 0.5.3 → `../../deliverables/k8-ambient-phone-relay-0.5.3.apk`.
  DEX 빌드에는 `--android-jar <SDK>/platforms/android-19/android.jar`가 필요했다(`.tools/android19`에는 source.properties가 없어 거부됨).
  문서의 OneDrive JDK 17 경로는 없어졌고, 이번에는 `C:/Users/dlatn/Documents/Codex/2026-10-04/so/work/local-jvm/jdk-17.0.20.1+1`을 썼다.
- 설치: 2026-11-20 05:04 차량 중계 0.4.4(설치 DEX md5가 빌드와 같음, 임시 zip 삭제), 휴대폰 0.5.3(USB, 재연결 확인). 옵션 켠 상태 실차 동작은 아직.
  05:20 헤드유닛 재부팅(마이 드라이브 자동 중복 실행 정리) 뒤에도 중계 1개 정상, 휴대폰 연결됨.

## 차량 중계 0.4.3: Wi-Fi 상시 감시 — 2026-11-19 (Claude, 03:27 차 설치 완료)

- 03:27 사용자 승인으로 설치. 설치된 DEX md5 c87a9808…5f420d가 빌드와 같음, 임시 zip 삭제.
  relay.log: `started K8AMB 1`, `wifi: window start (relay start)`, `phone connected`, `wifi: on=true ap=false ALREADY_ON`.
  **다음에 확인:** 앱 업데이트로 헤드유닛만 재설정된 뒤 relay.log의 `wifi: setWifiEnabled false by ...`(누가 껐는지)와 `watch: found off` → 다시 켜짐.

- 사용자 보고: 차 재시작 때는 Wi-Fi가 자동으로 켜지지만, 앱 업데이트로 헤드유닛만 재설정되면 Wi-Fi가 꺼진 채로 남는다.
  relay.log(11-19 02:22~03:20): 릴레이 시작 9번, 03:02 꺼짐 → `svc wifi enable #1` → 켜짐, 나머지는 이미 켜짐. 꺼진 시점의 기록은 없다(0.4.2는 시작 후 3분만 감시).
- 변경: 창 밖에서도 2분(`WATCH_SECONDS`)마다 확인해 꺼져 있으면 창을 다시 연다. logcat에 `WifiService:D`를 더해
  `setWifiEnabled true/false pid uid`를 호출 프로세스 이름과 함께 relay.log에 남긴다(누가 껐는지).
  Wi-Fi 설정 화면·상태바(`com.hkmc.wifisettings`, systemui/statusbar)에서 끄면 사용자 의도로 보고 다음 시동·릴레이 시작까지 켜지 않는다. 다른 호출자가 끄면 바로 창을 연다.
- 검사: 코어 376(새 10), Python 90 통과. 빌드 `private/nemesis-relay/k8ambient.relay-0.4.3.zip` (sha256 46a488a3…de0c45), DEX sha256 2ec3128c…c961b.

## 차량 중계 0.4.2: 시동 시 Wi-Fi 자동 켜기 — 2026-11-18 (Claude, 23:49 차 설치 완료, 시동 후 동작은 미확인)

- 23:49 사용자 승인("정차 안 해도 알아서 설치")으로 무선 ADB `nemesis install --update` 설치. 설치된 DEX md5 5f060860…e4899가 빌드와 같음, 임시 zip 삭제.
  relay.log: `started K8AMB 1`, `wifi: window start (relay start)`, `wifi: on=true ap=false ALREADY_ON persist=3`, `phone connected`(Wi-Fi가 이미 켜져 있어 enable은 안 불림).
  **다음에 확인:** 시동을 껐다 켠 뒤 relay.log의 `wifi:` 줄(꺼져 있었는지, `svc wifi enable` 결과, 핫스팟 접속).

- 차 확인(읽기 전용, `tools/probe_wifi_aa.py`): 무선 ADB는 이미 영구(`persist.adb.tcp.port=5555`, `persist.service.adb.tcp.enable=0`, adbd 0.0.0.0:5555).
  Wi-Fi는 헤드유닛 Wi-Fi 설정 화면(`com.hkmc.wifisettings`, uid 1000)이 `WifiService.setWifiEnabled(true)`를 부를 때까지 꺼져 있었다.
  hostapd 없음(무선 AA 미사용), Seogaemo `modules.list`는 package-compat만(Wi-Fi·무선 ADB·무선 AA 모듈 꺼짐).
  Seogaemo `wifi` 모듈(builtin_role client)은 재시작·시스템 모듈 활성화가 필요해 쓰지 않음.
- 구현: `core/WifiKeeperPolicy`(판단 규칙, TestMain 15항목) + `car-relay/WifiKeeper`(실행). 릴레이 시작·시동 켬(ignition on) 때 20초 뒤부터 3분간 10초마다 확인,
  `settings get global wifi_on`이 0이고 hostapd가 없으면 `svc wifi enable`(최대 4회). hostapd가 있으면 양보. 3분 뒤에는 손대지 않음.
  끄기: 차의 `/data/local/tmp/k8-ambient-relay/wifi-keeper.off` 파일. 로그: relay.log `wifi:` 줄(상태 변화·결과·`persist.wifi.state`).
- 빌드: 이 PC엔 API 19 android.jar와 JDK 7~17이 없어 `platforms/android-19`를 sdkmanager로 받고(사용자 승인), JDK 21에서 `-source 8`로 컴파일한 임시 사본 스크립트로 DEX 생성
  (프로젝트 `build_api19_dex.py`는 `-source 7`이라 JDK 21 불가, 수정하지 않음). 소스는 Java 7 문법만 사용.
  산출물 `private/nemesis-relay/k8ambient.relay-0.4.2.zip` (sha256 fd36c064…66f8af), DEX sha256 e8d628e8…9d96f2. 빌더가 이전 0.4.1 zip을 지움 → 롤백은 커밋 4459f46에서 0.4.1을 다시 빌드.
- 검사: 코어 367, Python 90 통과. `tools/check.py`는 JDK 8 필요로 못 돌림.
- 미확인: 차에서 `svc wifi enable`이 통하는지, 시동 뒤 누가 Wi-Fi를 끄는지, 켜진 뒤 핫스팟 자동 접속. 설치는 정차 상태에서 사용자 승인 뒤.

## 시동 시 Wi-Fi 자동 켜기(무선 AA 사용 중엔 양보) 준비 — 2026-11-18 (Claude, 차 확인 전)

- 사용자 요청: 시동마다 Wi-Fi·무선 디버깅을 손으로 켜는 문제를, 무선 AA를 쓸 때만 양보하는 방식으로 해결.
- 조사(Gemini, GitHub 검색): Open-HMG/hmgapi-adb(무선 AA와 안 부딪히는 ADB 5555), xwtk/hmg_nemesis(부팅 서비스 기반)는 참고 가능.
  부팅마다 Wi-Fi 클라이언트 자동 접속·AP와 클라이언트 동시 공존 사례는 못 찾음. Wi-Fi 칩 하나라 동시 사용은 어렵다는 기록만 확인.
- 추가: `tools/probe_wifi_aa.py`(읽기 전용 스냅샷·비교), `docs/WIFI_AA_PROBE.md`(차 방문 1회 절차). 차에서는 아직 실행 안 함.
- 미확인: 무선 AA 사용 중 판별 근거, Wi-Fi 켰을 때 핫스팟 접속·순정 시스템 반응, 비영구 ADB 포트(5555) 동작.
- 구현·설치는 근거 확보와 사용자 승인 뒤. 코드(car-relay) 변경 없음.

## C안 철회, ESP 이전으로 복귀 — 2026-11-18 01:20 (Claude)

- 사용자 요청: "그냥 ESP하기 전으로 돌려줘". 휴대폰이 계속 중계하고 ESP32가 조명만 맡는 안은 단계만 늘어 느려지므로 제외했고,
  USB·차량 Wi-Fi 대안도 진행하지 않는다.
- 코드: 차량 중계·휴대폰 앱·코어·테스트·도구를 2df73a8(0.4.8/0.3.5) 상태로 되돌리고 ESP32 펌웨어·EspConfig·EspGolden·EspLink·
  esp32 검사 도구·ESP32_FIELD_TEST를 삭제(기록은 Git 이력과 이 문서, DECISIONS에 남김). check.py: 코어 352, 차량 중계 API19 컴파일.
- 버전은 앞으로만 올림(설치 위에 덮기 위해): 휴대폰 **0.5.2**(code 20, 내용 0.4.8) — 01:19 USB 설치, 중계 서비스 실행 확인.
  차량 중계 **0.4.1**(내용 0.3.5, DEX에 ESP32 문자열 없음) — 빌드만, 차량 ADB가 끊겨(시동 꺼짐) 설치 전. 지금 차량은 0.4.0이지만
  휴대폰 통로는 0.3.5와 같아 동작에는 영향 없음.
- 01:34 차량 중계 **0.4.1 설치 완료**(정차, DEX md5 일치, 임시 zip 삭제). relay.log `started`·`listening`·`telemetry listening`·
  `phone connected`, `esp32 listening` 없음 → ESP 이전 상태로 복귀.
- 01:45 헤드유닛 페어링 목록(K8AMB-ESP32, Galaxy)에서 이름이 K8AMB-ESP32인 기기만 `removeBond`(작은 DEX, app_process root,
  `Looper.prepareMainLooper()` + 쓰기 가능한 `ANDROID_DATA`) → `true`, 상태 10(해제), 목록에 Galaxy만 남음. 도구 파일 삭제.
  첫 시도(01:34)는 실행 직후 헤드유닛 재부팅으로 적용 안 됨. 헤드유닛은 이날 우리 작업과 무관하게도 여러 번 재부팅(00:38, 01:2x, 01:39).
- C안 정리 끝. ESP32 보드는 페어링 기억·차량 주소를 지운 상태(사용 안 함).

## C안 실차 첫 연결 — 2026-11-18 00:00~00:50 (Claude, 차량 중계 0.4.0 설치, ESP32 0.1.2, 결론: 페어링 방식 보류)

- 2026-11-17 23:55 정차(속도 0)에서 차량 중계 0.4.0 설치(DEX md5 일치, 임시 zip 삭제). relay.log `esp32 listening` — 헤드유닛이 비보안 SPP 서버를 연다.
  휴대폰 통로는 그대로(`listening`, 휴대폰 중계 정상).
- ESP32 접속: 헤드유닛이 두 번째 ACL은 받지만(`#ACL = 2`) 연결마다 즉시 SSP 페어링을 시작하고
  `btif_dm_ssp_cfm_req_evt: Auto-reject JustWorks pairing unconditionally`로 끊는다. 채널 직접 지정(5)도 같음 → **비보안 무페어링 접속은 불가**.
- 사용자 승인으로 ESP32 0.1.1(SSP 표시+예/아니오, 숫자 확인 자동 수락) → 00:24 페어링 성공(bonded), `car connected`,
  `CAR MODE 02`, `CAR SML 00b7002300ea0b0102` → `TARGET 50,154,158,100,1 mode 2`(에코), 드라이런 페이드 프레임 정상. **차량→ESP32→계산 경로는 실차에서 동작.**
- 문제 1(ESP32, 0.1.2에서 수정): BluetoothSerial이 이전 시도의 늦은 OPEN/CLOSE로 연결 플래그·클라이언트 핸들을 꼬이게 해 새 연결을 바로 끊음
  → 연결 플래그 대신 무응답 30초로 판단, 시도 전후 남은 클라이언트 정리.
- 문제 2(헤드유닛, 미해결): 페어링된 ESP32를 헤드유닛 연결 관리자(BTCMService/BTCMStateMachine)가 휴대폰처럼 다룬다 —
  `CONNECT_PRIORITY_DEV2 … try to connect`, 프로필 연결(HFP/A2DP 등) 실패 시 ACL을 끊음(ESP32 쪽 reason 0x113), 00:38 헤드유닛 재부팅 뒤에는
  SDP도 실패. 휴대폰 자동 연결 우선순위에 끼어들 위험이 있어 ESP32 차량 접속을 멈춤(`SET car -`, 저장 안 함 — ESP32 재부팅 시 재개).
- 01:00 사용자 요청으로 페어링 해제: ESP32 쪽 `UNPAIR` + `SET car -` + `SAVE`(재부팅해도 차량 접속·페어링 요청 안 함, 앱이 다시 보내면 재개).
  **헤드유닛 쪽 "K8AMB-ESP32" 페어링은 아직 남아 있음** — 차량 ADB가 끊겨 못 지움. 헤드유닛 블루투스 목록에서 직접 삭제하거나,
  다음 연결 때 `removeBond`를 그 이름의 기기에만 호출하는 작은 DEX(app_process, root)로 지운다.
- 판단: 헤드유닛 블루투스(Mobis BTCM) 구조상 클래식 페어링 기기는 휴대폰 취급을 피하기 어렵다. 대안 후보: (1) ESP32 장치 분류(CoD)를 휴대폰이 아닌 값으로 바꿔 BTCM이 무시하는지 확인,
  (2) 헤드유닛 Wi-Fi(이미 무선 ADB로 쓰는 차량 Wi-Fi)로 TCP 통로 — 페어링 불필요, (3) 휴대폰을 계속 차량 신호 중계로 두고 ESP32는 조명만 구동(휴대폰→ESP32 BLE).

## C안 ESP32 연동 구현 — 2026-11-17 (Claude, ESP32 업로드·PC 검사·휴대폰 0.5.1 설정 전달 확인, 실차 확인 전)

- 사용자 요청: ESP32를 연결했으니 앰비언트 연동을 ESP32가 하고, 휴대폰은 색·밝기 설정을 넘기는 용도로만. 설계는 DECISIONS 2026-11-17.
- 차량 중계 0.4.0(`CarRelay`): 기존 휴대폰 서비스 유지 + 두 번째 RFCOMM 서비스 "K8 Ambient ESP32"(SPP UUID, 비보안).
  로그 `esp32 listening/connected/disconnected`. `private/nemesis-relay/k8ambient.relay-0.4.0.zip`, 설치 전.
- ESP32 펌웨어 0.1.0(`esp32/k8amb_esp32/`, Arduino esp32 코어 3.3.12, `PartitionScheme=huge_app`): 컴파일 통과(플래시 36%, RAM 13%).
  `k8core.*`가 Java 코어(매핑·드라이브 모드·톤·색별 밝기·페이드·SyncEngine·프레임)를 옮김. 차량 SPP 접속(이름으로 채널 선택, `K8AMB 1` 확인,
  30초 무응답 재접속), 조명 BLE 중앙, 휴대폰용 BLE 설정 서비스, NVS 저장. **조명 쓰기 기본 꺼짐**(시리얼에 `TX` 줄만).
  시리얼 명령: HELLO, STATUS, GET, SET…, THEME…, SAVE, DEFAULTS, CALC, 모의 SML/MODE/ALERT, REBOOT.
- core `EspConfig`(설정 줄 생성, 테스트 포함), `EspGolden`(보드 계산 비교 기대값), `tools/esp32_serial_check.py`(PC 시리얼 검사).
- 휴대폰 0.5.0(code 18): "ESP32 모드"(기본 꺼짐), ESP32 찾기, 설정 자동 전달, 상태 표시, 조명 쓰기 체크박스. ESP32 모드에선 휴대폰 중계·자동 시작 안 함.
  `private/phone-relay/k8-ambient-phone-relay.apk` 빌드만, 설치 전.
- 검사: check.py 통과(코어 389, Python 90·1 건너뜀, host 14, 진단 37, probe 24, API19 컴파일).
- PC 확인(같은 날): CP210x 드라이버(Silicon Labs 11.6.0.420) 설치 후 보드 업로드(해시 확인).
  `tools/esp32_serial_check.py`: 계산 232/232가 Java 코어와 같음, 앱 설정 18줄 적용·재부팅 후 유지, 범위 밖 값 거부, 모의 흐름 TARGET 7·드라이런 프레임 83.
  `tools/esp32_ble_check.py`(새, PC 블루투스로 앱과 같은 GATT 경로): 광고 발견, MTU 247, HELLO/STATUS/GET, 설정 16줄 쓰기 모두 OK·SAVE.
  빈 힙은 약 72~77KB(조명 BLE 연결 전). 휴대폰 APK는 `deliverables/k8-ambient-phone-relay-0.5.0.apk`.
- 휴대폰 확인(같은 날, Z Fold): 0.5.0 설치 → ESP32 찾기·상태 읽기 성공, 첫 설정 전달은 찾기 직후 GATT 연결 실패.
  0.5.1(code 19, `EspLink` 연결 실패 시 0.8초 뒤 한 번 재시도) 설치 후 "설정 다시 보내기" → 앱 기록 `esp 설정 17줄, OK 17`,
  ESP32 `GET`에 앱의 실제 값(차량·조명 주소, GRB, 전원 1, 페이드 700, 감마 1.4, 파랑 66, Lotus 최대 24, 색별 밝기 45,75,65,50,45,45, output 0)이 저장됨.
  ESP32는 차량 주소를 받은 뒤 차량 접속을 3~30초 간격으로 시도한다(차량 중계 0.3.5에는 ESP32 서비스가 없어 실패가 정상).
- 미확인: 헤드유닛이 비보안 SPP 서버·두 번째 ACL을 받는지, ESP32의 조명 BLE 쓰기. 실차 순서는 `docs/ESP32_FIELD_TEST.md`.

## 색별 밝기 보정 — 2026-11-14 (Claude, 휴대폰 0.4.8 설치, 2026-11-15 사용자 실차 확인: 잘 됨)

- 사용자 요청: 색마다 사제 조명 밝기 차이가 있어 통일하고 싶음. 따뜻한 색은 약 30%, 빨강·파랑·보라는 약 13%(Lotus 최대 20% 기준).
- 코어 `HueBrightness`: 색상환 기준점 6개(빨강 0°, 따뜻한 색 30~60° 평탄, 초록 120°, 하늘 185°, 파랑 235°, 보라 280°)의 밝기 비율을
  선형 보간해 `ColorTone` 결과 밝기에 곱한다(최대 100%). 채도 2/3 미만은 100% 쪽으로 섞어 흰색은 그대로.
  기본값: 빨강 65, 따뜻한 색 150, 초록 100, 하늘 100, 파랑 65, 보라 65 → Lotus 20% 기준 13%/30%.
  색상은 톤 보정 전 순정 색으로 판단한다. 두 조명 모두 적용(Zengge는 자기 최대 밝기 대비).
- 휴대폰 0.4.8(code 17): 앱에 "색별 밝기" 슬라이더 6개(20~300%, 바로 반영). 코어 352(순정 주황 00ff00a60046 → 약 30%,
  보라 009b002100ef → 약 13% 등), Python OK. 설치 후 서비스 재시작 확인.
- 2026-11-15 사용자 확인: 실차에서 잘 됨(기본 비율 그대로). 과속 경고 빨강도 빨강 비율(65%)이 적용된다.

## 간헐적 무반응 진단 기록 — 2026-11-14 (Claude, 휴대폰 0.4.7 설치)

- 사용자 보고: 대체로 잘 되지만 가끔 안 됨. 그 시각의 기록이 없어 원인 미확정(휴대폰 logcat은 약 10분이면 순환).
- 휴대폰 0.4.7(code 16): `DiagLog`가 앱 외부 파일 폴더에 `relay-events.log`(1MB + `.1` 백업)를 남긴다.
  기록: 앱 시작/중지와 버전, 차량 링크 상태(연결 중/연결됨/끊김/실패 사유/ACL 감지), Lotus·Zengge 링크 상태(끊김 status 코드 포함),
  쓰기 혼잡 재전송·쓰기 실패 사유, 받은 `SML`·`MODE`·`ALERT` 줄, 조명 목표(켜짐/밝기/RGB/경고/모드/연동), 엔진 예외.
  같은 태그의 같은 문장은 한 번만 쓰고 반복 횟수를 남긴다. 주소·식별자는 쓰지 않는다. 앱 화면에 경로 표시.
- 꺼내기: `adb -s <serial> pull /sdcard/Android/data/io.github.k8ambient.phone/files/relay-events.log`.
- 검사: 코어 335, Python OK. 18:23 USB 설치 후 파일 생성·기록 확인(차량 꺼짐 상태라 차량 재접속 실패 줄만). 차량 꺼짐 동안 "연결 중"이
  실패 줄과 번갈아 반복을 막아서 차량 "연결 중"은 기록하지 않게 바꿔 다시 설치(같은 0.4.7). 산출 `deliverables/k8-ambient-phone-relay-0.4.7.apk`.

## 시동 직후 약 30초 색 전환 지연 — 2026-11-11 (Claude, 휴대폰 0.4.6 설치, 실차 재확인 전)

- 사용자 보고: 시동 직후 처음 에코 색은 나오지만 다음 색 변경이 약 30초 동안 반영되지 않음.
- 근거(차량 relay.log, 휴대폰 logcat): 17:46:11 헤드유닛 부팅, 17:46:17 휴대폰-차량 ACL 연결, 17:46:34 중계 `listening`,
  17:47:01에야 휴대폰 앱 `phone connected`(27초 지연). 07:05 시동도 07:05:09 끊김 → 07:05:39 연결(30초).
  원인: 차량이 꺼져 있는 동안 앱의 차량 재접속 간격이 3→6→12→24→30초로 늘어나 시동 후 다음 시도까지 기다림.
  첫 에코 색은 조명 재연결 때 앱이 마지막으로 알던 상태를 다시 보낸 것.
- 수정(휴대폰 0.4.6, code 15, `RelayService`): 휴대폰이 차량 블루투스(ACL)에 연결되면 대기 중인 재접속을 즉시 깨우고,
  그 뒤 2분 동안 3초마다 재시도. 그 밖에는 기존처럼 최대 30초.
- 검사: 코어 335, Python OK. 설치 후 차량 중계에 자동 재연결 확인. 다음 시동에서 `phone connected`가 `listening` 직후인지 확인.
- 참고: 부팅 직후 relay.log에 `mode 03,02,06,02`(17:46:38~41), `06,02,06`(17:02:30~32)처럼 차량의 모드 보고가 몰려 나옴.
  콜드 부팅은 시동 신호 0→1이 없어 20초 06 무시 창이 적용되지 않는다. 17:02 부팅은 06(스마트)으로 끝남 — 계기판 에코였다면 다음 수정 대상.

## Lotus 시동 후 무반응 — 2026-11-11 (Claude, 휴대폰 0.4.5 설치, 실차 재확인 전)

- 사용자 보고: 정상이던 Lotus가 시동을 껐다 켜면 색 전환이 안 되고, 시간이 지나면 다시 됨. Zengge는 정상.
- 휴대폰 logcat·HCI(`private/captures/lotus-ign-*`) 원인 두 가지:
  1. 07:27:05 Lotus 쓰기 하나가 `GATT_CONNECTION_CONGESTED`(0x8f) → 일시적 혼잡을 실패로 보고 링크를 끊음.
  2. 끊은 직후 재연결이 Android의 남은 링크(닫은 뒤 약 4초 idle 타이머)를 재사용: 07:27:06~17 서비스 목록이 즉시 빈 채로 와서
     "서비스 없음"으로 다시 끊기를 반복(21초). 시동 07:29:47 링크 손실 뒤 07:29:49 새 링크에서 스택이 장치 이름 읽기 후
     서비스 탐색(0x10)을 보내지 않고 멈춤 → 8초마다 같은 링크로 재시도 → 07:30:29 링크 시간 초과 후 07:30:37에야 정상(약 50초).
- 수정(휴대폰 0.4.5, code 14, `GattTransport`): 혼잡·쓰기 대기열 가득은 같은 프레임을 최대 4번 재전송(20~80ms 간격), 링크 유지.
  아직 연결된 링크를 우리가 닫으면 다음 연결 전 5초 대기(새 링크 보장), 상대가 끊은 경우는 바로 재연결.
  서비스 탐색 제한 8→3초, Lotus는 연결 후 0.5초 기다렸다가 탐색 요청.
- 검사: 코어 335, Python OK. 설치 후 두 조명 재연결 확인(Lotus 133 한 번 뒤 0.57초 탐색 완료). 시동 껐다 켜기 재시험은 아직.

## 휴대폰 0.4.4 설치 — 2026-11-10 (Claude)

- Codex 커밋 5개(e75c7b0~fe75191)를 받아 검사 통과 확인: 코어 335, Python 90(1 건너뜀), host 14, 진단 모의 37, BLE probe 24, API 19 컴파일.
- 사용자 승인으로 `deliverables/k8-ambient-phone-relay-0.4.4.apk`를 USB 설치(0.4.1 → 0.4.4, 서명 일치로 업데이트 성공), 중계 서비스 자동 재시작 확인.
- 남은 확인: 평소 색 변경 때 Zengge 반응, 다음 과속 경고 때 Zengge 깜빡임, 시동 점등 페이드. 차량 중계는 0.3.5 그대로.

## Codex·Claude 작업 공유 — 2026-11-10

- 두 도구의 진입 파일을 [공용 인수인계](HANDOFF.md)와 같은 작업 브랜치로 연결했다.
- 공용 문서에 마지막 설치 확인 버전과 최신 배포 APK 0.4.4를 구분하고, 다음 현장 확인 항목을 반영했다.
- 문서 참조, 앱 매니페스트 버전, 로컬 APK 존재를 확인했다.

## Zengge 반응 속도와 경고 펄스 송신 — 2026-11-10 (휴대폰 APK 빌드, 실차 확인 전)

- 사용자 관측: 같은 과속 경고에서 Lotus는 깜빡였고 Zengge는 깜빡이지 않았다. Zengge의 평소 색 반응도 느렸다.
- PC에 보관된 이전 중계 HCI에서 Zengge 정적색 377건은 모두 21바이트였고 해당 연결에 MTU 200과 517 협상이 보였다.
  이번 증상 시각의 캡처는 없다. 기존 정상 색 캡처만으로 경고 펄스 누락 원인을 확정할 수 없다.
- 휴대폰 0.4.4(code 13). Zengge 평소 색은 중간 프레임 없이 목표를 보내고 경고의 켜짐·꺼짐 프레임은 도착 즉시 송신을 시도한다.
  기존 50ms 쓰기 간격과 재시도, 600ms 첫 점등, 경고 종료 페이드는 유지한다.
- Zengge 연결에서 MTU를 요청하고 상태 화면에 결과와 경고 켜짐/꺼짐 전송 완료 수를 표시한다. MTU가 24 미만으로 확인되면
  21바이트 색 프레임을 잘린 채 보내지 않는다. 미확인 상태에서는 기존 방식으로 계속 시도한다. 완료 수는 Android 콜백 기준이다.
- 코어 335개(경고 SML → GRB 적색·검정·적색 포함), Python 89개 통과(기존 1개 건너뜀), host 14·진단 모의 37·BLE probe 24, API 19 컴파일 통과.
  0.4.3과 APK 서명 일치. 배포 파일은 작업공간의 `deliverables/k8-ambient-phone-relay-0.4.4.apk`.

## 과속 경고 종료 후 색 복귀 페이드 — 2026-11-10 (휴대폰 APK 빌드, 실차 확인 전)

- 휴대폰 0.4.3(code 12). 경고 중 빨간색과 꺼짐 펄스는 즉시 반영한다. `ALERT 0` 이후 1.5초 동안 페이드를 막던 규칙을 제거했다.
- 경고 종료 시 같은 차량 연결에서 마지막으로 받은 정상 SML에 현재 드라이브 모드 색을 적용해 바로 복귀를 시작한다.
  다음 정상 SML이 다른 상태를 보내면 그 값으로 이어서 전환한다. 경고 도중 연결해서 정상 상태가 없다면 첫 정상 SML을 기다린다.
- 빨강이 켜진 상태에서는 앱의 색 전환 시간(기본 300ms)을 쓰고, 마지막 펄스가 꺼진 상태에서는 0.4.2의 점등 페이드(약 600ms)를 쓴다.
  전환 시간 0ms에서는 둘 다 즉시 반영한다.
- 코어 332개, Python 89개 통과(기존 1개 건너뜀), host 14·진단 모의 37·BLE probe 24, API 19 컴파일 통과.
  0.4.2와 APK 서명 일치. 배포 파일은 작업공간의 `deliverables/k8-ambient-phone-relay-0.4.3.apk`.

## 시동 후 첫 점등 페이드 — 2026-11-10 (휴대폰 APK 빌드, 실차 확인 전)

- 휴대폰 0.4.2(code 11). 조명 BLE 연결 직후 또는 꺼짐에서 켜짐으로 바뀔 때 검정부터 약 600ms 동안 목표 색까지 밝아진다.
  페이드 시간은 BLE 준비와 검정 전송 이후부터 계산한다. Lotus는 밝기 설정을 유지하면서 RGB를 올리고, 전원 옵션이 켜져 있으면
  검정과 밝기 프레임을 ON 프레임 앞에 보낸다. Zengge는 밝기가 곱해진 RGB를 올린다.
- 평소 색 전환 시간은 기존 설정을 따르며 전환 시간 0ms와 과속 경고는 첫 점등 페이드도 건너뛴다.
- 코어 329개, Python 89개 통과(기존 1개 건너뜀), host 14·진단 모의 37·BLE probe 24, API 19 컴파일 통과.
  휴대폰 APK 서명이 0.4.1과 일치한다. 배포 파일은 작업공간의 `deliverables/k8-ambient-phone-relay-0.4.2.apk`.
- 실차 첫 점등과 Lotus의 ON 전 검정 반영은 미확인이다. BLE 연결 전에 조명 컨트롤러가 저장된 밝기로 켜지는 경우는
  이번 앱 페이드가 시작되기 전의 동작이므로 정차 시동 시험에서 시점을 구분한다.

## 직접색과 드라이브 모드 연동 — 2026-11-10 (PC 검증)

- 직접색을 선택하면 예상 SML 상태는 RGB만 바꾸고 드라이브 모드 연동 플래그(byte 8)를 유지한다.
  휴대폰은 `MODE`를 받으면 마지막 SML을 다시 적용한다. 에코·스포츠 색 적용은 연동 플래그에 따르며 직접색과 테마를 구분하지 않는다.
- 합성 재생에 테마 → 직접색 명령 → 스포츠·에코·스마트 전환을 추가했다. 새 SML 없이도 스포츠 빨강, 에코 청록, 스마트 직접색을
  선택하고 밝기를 유지했다. 코어 322개 검사 통과. 이 검사는 차량의 실제 SML 응답이나 BLE 쓰기를 재현하지 않는다.
- 실제 증상은 미재현이다. 다음에 발생하면 시각과 선택 모드를 받아 순정 응답의 RGB 형식·byte 8, 중계의 `mode` 줄,
  휴대폰 앱의 모드·연동·목표 RGB, 두 조명의 HCI 쓰기 순으로 대조한다. 현장 순서는 [AI_HANDOFF.md](AI_HANDOFF.md) 참고.

## 시동 시 에코 색·응답 속도 — 2026-11-10 (빌드만, 설치 전)

- 버그(사용자 보고): 이전 주행이 스포츠/스마트였으면 시동 후 차는 에코로 돌아가는데 사제 조명은 에코 색이 안 됨.
  원인: 중계 0.2.3이 relay.log에서 이전 주행의 모드를 복원하고, 시동 때의 에코 복귀는 모드 줄로 보이지 않음(차량 꺼져 있어 로그 재확인은 못 함).
  수정(차량 중계 0.3.1): 헤드유닛 부팅 4분 이내 시작이면 모드를 에코로 시작(사용자 보고 기반 가정), 실제 모드 줄이 오면 덮어씀.
- 속도: 헤드유닛 명령 줄(`setSMLRGB`, `setSMLConfiguration` mode 5/8/9)이 상태 응답보다 약 0.3초 먼저 찍힘 →
  `RelayProtocol.predictStatus`로 예상 상태를 먼저 전송, 실제 응답이 다르면 바로잡음(캡처 쌍으로 테스트).
  휴대폰 0.4.1: BLE 연결 우선순위 HIGH, 기본 전환 800→300ms(저장값이 더 길면 1회 300으로).
- 검사: 코어 313, Python OK. 산출: `private/nemesis-relay/k8ambient.relay-0.3.1.zip`, `deliverables/k8-ambient-phone-relay-0.4.1.apk`.
- 17:50 차량 접속 후 증거 확인: 시동 11초 뒤 `SYS_DRIVE_MODE_DATA_M_RESP` byte 2 = **0x0f**(뒤따르는 0x00 없음), 다음 부팅에는 모드 줄 자체가 없음.
  → 0x0f는 모드가 아님으로 처리(코어 315), 부팅 직후 에코 기본값 유지. 복원이 자기 "restored … mode 15" 요약 줄을 16진수로 다시 읽던 버그 수정.
- 정차(속도 0) 확인 후 차량 중계 0.3.1 → 0.3.2 설치(DEX md5 일치), `restored … mode 6`, 휴대폰 자동 재연결 확인.
- 휴대폰 0.4.1 설치. HCI 확인: 18:01 모드 전환 색(스포츠 빨강·에코 청록·스마트 보라)이 두 조명에 실제로 전송됨.
- 사용자 보고: 시동 껐다 켜도 에코 색 안 됨. 원인: 짧은 시동 사이클에는 헤드유닛이 꺼지지 않아(업타임 22분) 부팅 규칙이 안 걸림.
  기록: 시동 꺼짐 `SYS_IGN_STATUS_M: 0`, 켜짐 `: 1`(18:00:56, 18:01:52→58, 18:10:34→38), 켜진 뒤 모드 값은 0x00뿐.
  차량 중계 0.3.3: 꺼짐 뒤 켜짐이면 모드를 에코로. 0.3.4: `(cmd)` 예상 상태 줄도 복원(0.3.3은 건너뛰어 옛 상태 복원).
  정차 확인 후 0.3.4 설치, `restored SML 01a70162017f0b0102`, 휴대폰 재연결. 코어 319.
- 0.3.4 실차: 시동 켜짐(18:17:30)에 에코로 바꿨지만 18:17:38·43에 차량이 0x06을 보내 스마트 색이 됨. 사용자: 그때 계기판 에코·순정 청록.
  사용자가 직접 스마트 선택 → 0x06(18:21:00) 확인: 0x06=스마트 맞음, 시동 직후 0x06은 차량의 잘못된 보고.
- 차량 중계 0.3.5: 로그를 `-v time`으로 읽어 시동 켜짐 후 20초 안에 다른 모드 보고 전에 오는 0x06 무시(실제 선택이 시작되면 해제).
  `handleLine` 분리, 오늘 기록 재생으로 확인(18:01 직접 선택 유지, 18:17 잘못된 06 무시, 18:21 스마트 반영). 정차 확인 후 설치, 휴대폰 재연결.
- 사용자 확인(18:2x): 스포츠 → 시동 껐다 켜기 → 에코 색 유지됨.
- 남은 일: 직접 고른 색 + 연동 상태 모드 전환 재현(아직 기록 없음). 엔진 사운드 세션의 0.3.0 변경과 함께 미커밋.

## 엔진 사운드용 주행 텔레메트리 채널 — 2026-11-09 (차량 설치 전)

- 목적: `gps-engine-sound` 앱 0.9.0이 GPS 대신 헤드유닛 차속을 쓰게 한다. 조명 채널은 그대로 둔다.
- 오늘 relay 기록(17:20~18:01, 읽기 전용 `nemesis root -c cat`) 분석: `D/SystemService SYS_SPEED_M: <km/h>`는
  값이 바뀔 때만(가속 중 초당 2~5회, 약 1.4 km/h 단위: 5,7,8,9,11,12,14…). `SYS_GEAR_STATUS_M` 0=P, 1=NONE(0xff, P 아님)뿐이고
  D/N/R 구분은 없다. 후진은 `V/… Reverse status changed to : true/false`. **엔진 RPM·페달은 MICOM 함수 목록에 없다.**
- 코어 `TelemetryProtocol`(Java 7): 별도 UUID `9f2d4c61-…f213`, `K8TEL 1` 후 `TEL <kmh> <park> <rev> <mode>`(미확인 -1)를
  변화 때와 500ms 주기로 보냄. 차량 중계 0.3.0: 두 번째 RFCOMM 서버("K8 Telemetry"), 별도 잠금(조명 스트림을 깨우지 않음),
  logcat 필터 `SystemService:V`(후진 줄이 V 레벨). 차량 명령 없음.
- 검사: 코어 302(텔레메트리 포함), host14, 진단 모의37, BLE probe 24, Python OK(skip 1). `private/nemesis-relay/k8ambient.relay-0.3.0.zip`
  빌드(DEX에 K8TEL 포함 확인).
- 18:22 사용자 승인·정차 확인(17:29부터 속도 0·P, 17:32 주차 브레이크, 18:22까지 변화 없음) 후 무선 ADB로 0.3.0 설치.
  relay.log에 `telemetry listening`·`listening`, 18:23 조명 휴대폰 앱 자동 재연결 확인. 임시 zip 삭제.
- 남은 일: 엔진 사운드 앱(0.9.3, "K8 차량" 입력)의 텔레메트리 연결이 relay.log에 아직 없다(앱 권한·기기 순서 버그 수정 후 차량 꺼짐).
  다음 시동 때 `telemetry client connected` 확인. 실주행 차속 추종은 미확인.

## 드라이브 모드 연동 — 2026-11-09 (사용자 확인: 잘 바뀜)

- SML 상태 byte 8 = 순정 "드라이브 모드 연동"(02 켜짐/01 꺼짐), byte 7 = 주행 중 감광(mode 8). 연동 중에도 상태 색은 사용자 색.
- 모드: `SYS_DRIVE_MODE_DATA_M_RESP` byte 2(0x02 에코, 0x03 스포츠, 0x06 스마트; 직후 0x00). 사용자 관측: 스포츠 빨강,
  에코 리프레싱 오션, 스마트 = 직전 사용자 색. 에코 기본값 (50,154,158)(테마 `0132019a019e` 색), 앱에서 R,G,B 변경 가능.
- 코어 `DriveModeLight`, `MODE xx` 줄. 차량 중계 0.2.2(모드 전달) → 0.2.3(재시작 때 자기 relay.log에서 마지막 상태·모드 복원:
  차량은 변할 때만 상태를 보내 재시작 직후 색 적용 대상이 없던 문제). 휴대폰 0.4.0. 정차 확인 후 설치, 사용자 확인.
- 경고(빨강 깜빡임)는 모드 색보다 우선. 남은 점: 리프레싱 오션의 정확한 코드는 미확정, 중계 정지 중 바뀐 설정은 다음 조작 때 반영.

## 과속 카메라 경고 연동·지연 단축 — 2026-11-09

- 사용자 확인: 과속 카메라 경고 때 사제 조명도 빨강 깜빡임을 따라옴(17:22, 약간 지연).
- 기록 분석: 내비 `com.mnsoft.navi.action.NaviCameraAlertState`(state 1) → OEM LightService `setAmbientLight - 1`
  → 80ms 뒤 SML 상태 빨강(00ff 0032 001d) 1초 간격 켜짐/꺼짐(raw 0b/01) → state 0에서 원래 색 복귀.
  지연 주원인은 휴대폰 페이드 800ms.
- 차량 중계 0.2.1: LightService(main 버퍼) 경고 줄을 읽어 `ALERT 1/0` 전송(상태보다 먼저). 휴대폰 0.3.3: 경고 중과
  종료 후 1.5초는 페이드 없이 즉시 전환, 연결 끊기면 해제. 정차(speed 0, P) 확인 후 둘 다 설치, 자동 재연결 확인.
- 남은 확인: 다음 실제 카메라 경고에서 지연 체감.

## 과속 경고 조사 시작·Zengge 밝기 분리 — 2026-11-09

- 휴대폰 0.3.2: Zengge 최대 밝기 별도(기본 100%), Lotus는 기존 20%.
- 과속 빨간불·깜빡임은 현재 중계로 연동 안 됨: 헤드유닛 SML 기능은 RGB/모드·밝기/상태 요청·응답 4개뿐.
  내비는 계기판에 `NaviTbtTrapInfo`(overSpeedAlarm 필드 포함)를 보내며 경고 조명은 다른 경로로 추정.
- 차량 중계 0.2.0(정차 speed 0 확인 후 무선 ADB `nemesis install --update`, 사용자 승인): 기존 중계 + 읽기 전용 기록기
  (ClusterService·NavigationCluster·SystemService·MicomD·Navi·CoNA 태그와 speed/camera/alarm 등 단어, 16MB×6 회전,
  `/data/nemesis/logs/k8ambient.relay/overspeed*.log`). relay.sh 작업 폴더 생성 재시도. 설치 후 휴대폰 자동 재연결 확인.
- 다음: 사용자가 자연 발생한 과속 경고 시각을 알려 주면 기록을 받아 신호를 찾고 휴대폰 앱에 빨강 깜빡임을 추가.

## 0.3.1 Zengge 지원·자동 시작·테마 즉시 반영 — 2026-11-09

- 원인: 시동 후 연동 안 됨 → 차량 중계는 정상, 휴대폰 중계 서비스가 꺼져 있었다. `AutoStartReceiver`로
  차량 ACL 연결·재부팅·앱 업데이트 때 자동 시작(사용자가 "중계 시작"을 눌러 둔 경우만). 업데이트 후 자동 재시작 확인.
- Zengge `LEDnetWF…`(광고: 제품 0xA3, BLE v5): Zengge 앱 HCI 캡처의 색 쓰기 `0x41` 정적색 프레임(8바이트 전송 헤더)을
  `ZenggeFrames`로 재현(캡처 바이트와 일치 테스트). 밝기는 RGB에 곱하고 꺼짐은 검정(밝기·전원 쓰기는 미캡처라 미사용).
  사용자 관측(빨강→초록, 초록→빨강, 파랑→파랑)대로 기본 색 순서 GRB, 앱에서 변경 가능. Lotus와 각자 스레드로 동시 구동.
- 테마 정책 변경: 색 미지정 테마는 직전 색 유지 → **테마 하위 바이트 색 사용**. 사용자가 그 색이 정확하고 이제 테마 변경을
  바로 따라간다고 확인(0.3.1). 코어 검사 257.
- 남은 확인: 실제 시동 재시작 시 ACL 자동 시작(업데이트 경로만 확인). 정리 후보는 0.2.0 항목과 같다.

## 0.2.0 부드러운 전환·톤 확정 — 2026-11-08

- 휴대폰 앱 0.2.0: 색·밝기 페이드(기본 800ms)와 톤 보정(색 깊이 1.8, 파랑 85%, 최대 20%). 사용자가 순정과 비슷하다고 확인해 기본값 확정.
- 남은 확인: 시동 재시작 후 자동 재연결, 배터리 최적화 영향. 정리 후보: 차량 BLE 점검 패키지 제거, USB 설명서 원복, 차량 중계 스크립트 mkdir 보강.

## B안 실차 동작 확인 — 2026-11-08

- 차량 중계(`k8ambient.relay` 0.1.0) → 휴대폰 앱(0.1.2) → `MELK-OA21PQ` 조명 경로가 실차에서 동작했다.
- 사용자 육안: 순정 색상·밝기 변경, 꺼짐(전원 옵션), 드림퍼플 테마 색 지정 모두 조명이 따라옴.
- 남은 확인: 시동 재시작 후 자동 재연결, 배터리 최적화 영향. [기록](research/PLAN_B_RELAY.md).

## B안(Galaxy 중계) 1차 구현 — 2026-11-08

- 차량 NEMESIS 서비스 `k8ambient.relay`: 순정 로그의 SML 상태를 읽어 클래식 RFCOMM으로 휴대폰에 보낸다(차량 명령 없음).
- 휴대폰 앱 `io.github.k8ambient.phone`: RFCOMM 수신 → `K8LotusMapping` → 관측 Lotus 프레임 BLE 송신. 전원 명령은 실험 옵션.
- 공용 로직 `RelayProtocol`, `K8LotusMapping`과 테스트 추가(코어 213). 차량 중계는 API 19로 컴파일, 휴대폰 앱은 SDK 36으로 빌드·서명.
- 실차·실기기 시험 전이다. 구조·절차는 [B안 문서](research/PLAN_B_RELAY.md).

## A안 종료: 차량 Bluetooth 스택에 GATT 없음 — 2026-11-08

- 정차 확인 후 `bplus.default.so`(Broadcom 스택 HAL)를 읽기 전용으로 분석했다. GATT 클라이언트 인터페이스가 빌드에 없다.
- 설정·컴포넌트로는 해결되지 않는다. 차량 직접 BLE(A안)를 종료하고 B안(Galaxy 중계)·C안(별도 장치)으로 넘어간다.
- 별도 발견: 차량 `persist.service.adb.tcp.enable=1`(무선 AA 충돌 이력). 바꾸지 않았고 사용자 판단 대기.

## GattService 활성화 시험 — 2026-11-08 (사용자 승인, 원복 완료)

- root로 GattService를 켜고 시작 신호까지 보냈다. 요청이 GattService에 도달했지만 네이티브 스택이 등록에 응답하지 않아 여전히 `SCAN_REJECTED`.
- `pm disable`로 원복했다. 서비스 목록은 시험 전과 같고 휴대폰도 재연결됐다. A안은 이 펌웨어에서 사실상 막혔다.
- 시험 중 차량이 주행 중(speed 38)인 것을 로그로 발견해 작업을 멈췄고, 주차 브레이크 확인 뒤에만 원복했다.
  다음 차량 작업은 명령 전에 로그로 정차부터 확인한다. 다음 후보는 B안(클래식 RFCOMM → Galaxy). [근거](research/K8_BLE_GATT_DISABLED.md).

## 실차 BLE 점검 결과: GATT 비활성 — 2026-11-08

- 사용자가 NEMESIS로 점검 패키지 0.2.0을 설치·실행했다. 설치·DEX 실행은 성공, 결과는 `SCAN_REJECTED`.
- 사용자 요청으로 무선 ADB에 접속해 읽기 전용으로 확인했다. `Bluetooth.apk`의 `profile_supported_gatt=false`,
  `GattService`는 매니페스트에서 그 값으로 꺼져 있고 실행 중이 아니다. Android API로는 BLE 스캔·연결이 불가하다.
- 차량 상태는 바꾸지 않았다. `Bluetooth.apk`/odex와 logcat 사본은 `private/`에만 저장했다.
- 다음 판단: A안 GATT 활성화 시험(시스템 설정 변경, 승인 필요) 또는 B안(Galaxy 중계)·C안. [근거](research/K8_BLE_GATT_DISABLED.md).

## 점검 USB 준비 — 2026-11-08

- 사용자 USB(D:, FAT32)의 기존 `NEMESIS.data`(K8 증거 수집기 실행)를 `NEMESIS-backup/before-bleprobe-20261108-164001/`에 백업했다.
- `NEMESIS.data`를 `type=install`, `packages/k8ambient.bleprobe-0.2.0.zip`으로 바꾸고 zip·설명서 해시를 원본과 대조했다. `k8-ble-probe.example.txt`도 넣었다.
- USB에만 있던 2026-11-07 16:24 수집 2건을 `private/vehicle-usb/`로 복사해 12개 파일씩 해시 대조했다.
  둘 다 OEM SML 0줄(`partial_oem_reader_failure`)이라 새 SML 근거는 아니다.
- 차량 설치·실행은 아직이다. 끝나면 백업한 `NEMESIS.data`로 되돌린다.

## BLE 점검을 DEX 실행 방식으로 전환 — 2026-11-08

- 사용자 지적대로 NEMESIS 패키지는 대부분 `kind=sh`가 DEX를 `app_process`로 실행한다. 점검 도구를 이 방식으로 바꿨다.
- `ble-probe-dex/`: 화면 없는 `HeadlessProbe`(스캔·연결·서비스 목록, 쓰기 없음)와 PC 테스트용 `ProbeLogic`, root 셸 앱 `probe.sh`.
- `tools/build_k8_ble_probe_dex.py`(API19 컴파일 → D8 DEX 035), `build_nemesis_bleprobe.py`를 kind=sh 0.2.0으로 변경.
- `check.py`에 ProbeLogic 24개 검사와 API19 `android.jar` 컴파일을 추가했다. Python 86개 중 85 통과·1 skip, Java 190·14·37·24.
- 패키지 zip SHA-256 `9d0aa3c3…939b1ad`. 차량 설치·실행은 하지 않았다. Context 없는 `connectGatt`와 root BLE 권한은 현장 확인 대상.

## 참고 패키지 분석: Ambient Sound 1.1.0 — 2026-11-08

- 사용자 제공 NEMESIS 패키지(Ioniq 5/Grandeur 대상)의 소스를 읽었다. 설치·실행하지 않았고 K8 대상도 아니다.
- 같은 `ISystemService` Binder 코드(밝기 77, 색상 78)를 K8 DEX에서도 확인했다.
- 밝기는 메뉴 단계+1, "brightness 1 = dimmest on" → raw1=OFF 근거 추가. 직접색은 `[0x00, 값]` → 하위 바이트가 RGB.
- 순정 팔레트는 차종별로 다르다. [참고 패키지](research/AMBIENT_SOUND_PACKAGE.md).

## 진단 APK NEMESIS 패키지 준비 — 2026-11-08

- NEMESIS 소스로 차량의 1.16.4에도 허용 목록 우회 설치(`pm_install_apk`)가 있음을 확인했다(1.15부터).
- `tools/build_nemesis_bleprobe.py`와 테스트 4개를 추가했다. 검증된 APK만 감싸고 root 훅·서비스는 없다.
- `private/nemesis-bleprobe/`에 zip(SHA-256 `3fdb11d2…6bcb6d7`)과 USB 설치 구성을 만들었다. 차량 설치는 하지 않았다.

## 진단 APK 설치 거부 원인 확인 — 2026-11-07 밤

- NEMESIS 공식 문서 04-Packages(`345f171`): 부팅 완료 상태에서 OEM 허용 목록 밖 서명자는
  처음 설치하는 패키지도 `INCONSISTENT_CERTIFICATES`로 거부된다. 우리 증상과 정확히 같다.
- 해결 경로는 NEMESIS 패키지 `kind=apk`(설치 순간만 `sys.boot_completed=0`). 모듈 설치라 사용자 승인 필요.
- 설치 로그 수집·`services.odex` pull 단계는 확인표에서 뺐다. [근거](research/GEN5W_INSTALL_ALLOWLIST.md).

## 남은 PC 분석 마무리(Claude Code) — 2026-11-07 밤

- baksmali로 OEM DEX를 다시 풀었다. `0x15E` 이름은 `SML_MOOD_FLOOR_MODE_THEM_BRIGHT`이고, SystemService는 mode/data·RGB를 해석 없이 전달한다.
- raw1=OFF를 작업 기준으로 채택했다. 2차 1→2, 11칸 사다리, 응답 8·9바이트 불변, 상류 EV6 규칙이 근거다. 육안 소등만 남았다.
  이 해석은 기존 `toEv6ReferenceState()`와 같다. 실제 K8 상태 3건 회귀 검사를 추가했다(코어 185→190).
- 드림퍼플 복원도 `setSMLRGB` 경로다. 테마 재선택 현장 기록은 제외했다. 테마일 때 사제 색 정책 A/B/C는 사용자 선택으로 남겼다.
- 최초 HCI에 전원 형식 쓰기는 0건이다. 설치 실패 원인은 PC 자료로 더 좁힐 수 없다.
- `NEXT_PARKED_VISIT.md`를 최종 현장 확인표 5단계로 정리했다.
- 검증: Python 81개 중 80 통과·1 skip, Java 코어190·host14·진단 모의37. `--diagnostic`은 HMG 소스 부재로 미실행.

## PC 재분석(Claude) — 2026-11-07

- 차량·휴대폰 접속 없이 노트북 자료만 분석했다. [PC 재분석](research/PC_ANALYSIS_2026-11-07.md).
- 진단 APK 두 개의 v1 서명·다이제스트·PKCS#7·ZIP 구조를 PC에서 직접 검증해 모두 정상이었다.
  순정 4.4 파서 기준으로는 INCONSISTENT_CERTIFICATES 조건이 없어 제조사 설치 정책이 유력하다(미확정).
- OEM DEX 정적 분석: SML 밝기는 명령 0x15E `[mode,data]`, RGB는 0x15F 6바이트이며
  16비트 RGB는 호출 앱이 만든다.
- 추가 SML 1→2와 계획된 조작 순서가 맞아 raw1=OFF가 유력하다. 임시 밝기 함수는 변경하지 않았다.
- RGB 상위 바이트 0x01/0x00이 테마/직접색으로 갈리는 가설을 기록했다.
- 최초 HCI 초기 쓰기는 시계 동기화로 판단했다. 별도 연결 6개 쓰기는 다른 기기다.
  추가 BTSnoop 0건은 조작 시각(19:3x) 이후 20:19부터만 남은 로그 순환 때문이다.
- 사용자 답변: 평소 테마=드림퍼플(상위 0x01 값), 2차 선택=자주 쓰는 푸른 보라 직접색(상위 0x00).
  약 10초 뒤 복원했다는데 RGB 요청이 로그에 없어 테마 재선택 경로는 미해결.
- 사용자 제공 Lotus APK(SHA 일치) 바이트코드로 전원 프레임을 확정:
  ON `7e 07 04 ff 01 01 02 01 ef`, OFF `7e 07 04 00 01 00 02 01 ef`. 암호화는 장치 필드라 밝기와 같이 평문.
  기존 정적 분석의 두 번째 값 `00`을 `01`로 정정했다. 모의 전용이며 HCI 대조 전이다.
- 코드 변경 없음. 이번 세션에서 `python tools/check.py`는 실행하지 않았다.
- 추가 보고서 재분석: Lotus GATT 세션은 19:39:44~19:46:37 1회(최초 캡처와 같은 조명).
  보고서 시점 가동 5분(20:41 재부팅), 20:17~20:19 SmartThings BLE 단독 모드 반복으로 HCI 파일이 순환됐다.
  메모리 요약(BTSNOOP_LOG_SUMMARY)은 ACL이 없어 조작 구간 쓰기는 기존 자료에서 복원 불가.
- 코드: `LotusE1Frames`(색상·밝기·정적 전원)와 `SyncEngine.forLotusWithStaticPower()` 모의 모드 추가.
  기존 `forObservedLotus()` 동작은 유지. ATT 분석기에 `static_power_events` 분류와 근접 오판 검사 추가.
  최초 캡처 TSV 재분석 결과는 RGB 4·밝기 120·미확인 7로 기존과 같고 전원 일치 0.
- 검증: 클라우드 세션에서 Python 81 통과, Java 코어185·host14·진단 모의37 통과.
  JDK 8이 없어 Java 수준만 8로 바꿔 실행했다. 노트북에서 `python tools/check.py`(source 7) 재실행 필요.
- 사용자 지적: 이미 수행한 차량 조작을 다시 할 일처럼 적었다. Codex가 확인표를 고쳤고,
  Claude 문서도 "조작 완료, 증거 부족" 기준으로 맞췄다.

## Claude 인수인계 — 2026-11-07

- 현재 사실, 임시 보정의 한계, APK 설치 오류, 로컬 전용 자료와 다음 PC 작업을
  [Claude 인수인계](CLAUDE_HANDOFF.md)에 통합했다.
- 기존 지침에 따라 작업 브랜치에 문서와 최신 실측 기록을 함께 올린다.

## Galaxy USB 보고서 확보 — 2026-11-07

- USB ADB에서 Samsung/API37 휴대폰 연결을 확인하고 추가 보고서 ZIP 50,966,231바이트를 저장했다.
- 내장 BTSnoop 두 파일의 ATT 쓰기는 모두 0건이다. 추가 전원·최저 밝기 명령은 미확보로 남긴다.
  기존 보고서 목록에는 이번 ZIP 한 개만 있었다.
- 사용자가 차량 밖이라 실제 조명 반응 확인은 대기다. 현장 조작을 즉시 다시 요구하지 않았다.
  원본 ZIP·HCI·분석 TSV는 private에만 보관했다.

## 정차 추가 실측 — 2026-11-07

- 읽기 전용 ADB에서 BLE 기능 선언과 Bluetooth 관리자 서비스를 관측했다.
  차량 패키지 관리자가 진단 APK 두 서명 형식을 동일한 인증서 오류로 거부했다.
  진단 앱은 등록되지 않았고 Gen5W의 직접 GATT 연결은 미확인이다.
- 순정 꺼짐·최저·색상 조작 중 SML 로그 22줄을 확보했다. 모드 5가 1→2로
  바뀌었고 RGB 요청은 1건이다. 실제 소등/점등 및 테마와 직접색의 대응을
  확인하기 전까지 전원과 테마 변환을 적용하지 않는다.

## 정차 방문 준비 — 2026-11-07

- 기존 K8 및 Galaxy 자료를 재사용한다. 남은 실측을 Gen5W의 직접 GATT 탐색,
  순정 OFF·테마, Lotus 전원·최저 밝기로 좁혔다.
- 기존 무선 ADB에서 실행하는 읽기 전용 BLE 상태 요약과 독립 진단 앱을 준비했다.
  공식 API19로 APK를 빌드하고 v1/v2 서명·정렬을 PC에서 검증했다.
  현장 순서와 결과 해석은 [다음 정차 방문](NEXT_PARKED_VISIT.md)에 있다.
  이 단계는 PC 준비이며 실제 차량에서의 설치·연결은 대기 중이다.
- Python 78 pass/기존 skip 1, Java 코어173·host14·진단 모의37 통과.

## LotusLamp X 무선 실측 — 2026-11-07

- 기존 페어링으로 Galaxy 버그 보고서를 가져왔고, 내장 BTSnoop를 TShark 4.6.9로 분석했다.
- 동일 LE 세션의 FFF0/FFF3에서 RGB 4개와 밝기 120개를 찾았다. 밝기 조작 묶음의
  끝값은 5/10/15/20이다. 같은 세션의 초기 쓰기 1개와 별도 연결의 6개는 미확인이다.
- ATT 분석기에 실측 밝기 형식을 별도 분류로 추가했다. 기존 공개 후보와 구분한다.
- 선택형 모의 재생에서 실측 프레임을 재현했다. 순정 11→사제 20과 순정 3→사제 5를
  검증했다. 1→2%는 초기 선형 근사다. 전원 프레임·Gen5W의 BLE/GATT 연결은 검증 전이다.
- Python 74 pass/기존 1 skip, Java 코어173·host14·진단 모의37 통과.
  [Lotus HCI 실측](research/LOTUS_HCI_CAPTURE.md)에 분석 범위와 다음 검증을 기록했다.

## 실차 순정 SML 수집 — 2026-11-07

- 무선 ADB 연결 후 K8/API19를 확인했다. 최초 기존 캐시 선별 결과는 0줄.
- 5분 읽기 전용 감시에서 선별 39줄(RGB 요청 7, 밝기 요청 6, 상태 응답 26)을 private에 저장했다.
  밝기 요청 1,3,4,8,7,11은 각 직후 응답 7번째 바이트와 일치한다.
- 사용자 확인: 1단계는 실제로 켜진 최소다. K8 임시 정규화는 1~11을 모의 사제 2~20%로 보정한다.
  OFF 상태 원시값·RGB 팔레트 대응·Lotus 통신은 아직 미확인이다.
- 사용자는 Galaxy HCI 조작·버그 보고서 생성을 완료했다고 했으나 PC 원본은 아직 없다.
- ADB 권한 부족으로 일부 HMG 파일은 미수집. 기존 USB 사본은 이미 보존돼 있다.
- 기본 검사: Python 71 pass/1 기존 skip, Java 코어167·host14·진단 모의37 통과.
  Android API19 진단 계약 컴파일은 이번에 실행하지 않았다.

## USB 실행 실패와 ADB 경로 — 2026-11-07

- 차량과 노트북이 같은 휴대폰 핫스팟에 연결됐고 차량 IP의 ping/ARP 응답이 있다.
  기존 ADB 5555 포트는 닫혔으며 ADB 기기는 0대다. 실제 실행 포트 확인 후 수집한다.

- 차량 Wi-Fi 활성화 요청을 받았다. 현재 ADB 연결 0대라 원격 전환은 불가하다.
  과거 기록상 일반 Wi-Fi는 꺼져 있고 무선 AA용 AP는 실행 중이었다.
  OEM Wi-Fi 설정 화면 열기는 성공했지만 활성화 여부는 확인되지 않았다.
  레거시 ADB TCP는 무선 AA 충돌 때문에 다시 켜지 않는다.

- 사용자가 차량 USB 실행 불가를 알리고 기존 차량 ADB 사용을 요청했다.
- 이 PC의 scrcpy ADB 실행 파일을 찾았지만 현재 연결 기기 0대, mDNS 광고 0건이다.
- GL3HEV.KOR.*.260902/API19를 확인한 뒤 OEM 캐시를 먼저 읽고 기존 파일을 private에 저장하는
  PC용 읽기 전용 수집기를 준비했다. 파일 권한·심볼릭 링크 실패를 기록한다.
- 새 ADB fixture 5개와 Python 전체 69개 중 68개 통과·기존 권한 skip 1개.
  미연결 실행은 자료 생성 없이 중단했다. 실차 자료는 아직 없다.

## 사제 밝기 보정 — 2026-11-07

- 사용자 관찰: 사제 앱 약 20%가 순정 최대 밝기와 비슷하다. 스크린샷은 연결 전 19%다.
- 모의 동기화의 정규화 입력 0~100을 기본 0~20%로 선형 변환한다. 전원은 독립적이다.
  이는 초기 근사이며 K8 raw 값과 Lotus 기기 통신의 실측 검증은 아직 없다.
- JDK 17의 Java 7 대상 오프라인 검사 통과: Python 64개 중 63개 통과·1개 skip,
  코어 148·호스트 14·진단 모의 37 checks. Android API19 계약 컴파일은 이번에 실행하지 않았다.

## 동일 방문 보충 수집 준비 — 2026-11-07

- USB 기존 OEM 캐시 보존에 더해 BLE 기능 선언과 HMG 지정 프로세스의 읽기 전용 순간 관측을 준비했다.
- 캐시 확보 뒤 순정 색상·밝기를 수동 변경하고 USB를 재실행하면 새 결과 폴더에 별도로 담는다.
  로그 출력 여부는 실차 확인 전 미상이다. Galaxy Lotus HCI는 별도로 수집해야 한다.
- USB 기존 파일은 `NEMESIS-backup/before-ambient-extra-20261107-143947`에 보관하고 교체본 SHA256을 대조했다.
  USB collector fixture 15개 통과. 실제 차량에서 새 항목은 검증하지 않았다.

기준일: 2026-11-07 (Asia/Seoul). 현재 단계는 **K8 JSON·선택 맵 확보 / SML 배열 전달 시도 흔적 확인**이다.
실제 차량에서 동작하는 완성품 또는 배포용 모듈을 만든 상태는 아니다.

## OEM 로그 캐시 수집 보완 — 2026-11-07

- 이전 수동 변경은 수행 완료로 유지하고 원시값 기록 경로를 조사했다.
  기존 automotive-service/automotive ODEX의 MD5가 실제 맵의 파일 목록과 일치한다.
- REA의 caller-supplied JADX provider가 없어 기존 Android SDK dexdump와 로컬 DEX 메타데이터를 사용했다.
  최적화된 DEX의 class flags 때문에 분석 파생본의 검증을 건너뛰었고 quick opcode 일부는 미해석이다.
  선택한 문자열과 정적 호출만 근거로 삼았다.
- SystemService SML 응답의 byteToHexString/Slog.d, RGB 및 configuration 설정 로그 위치를 확인했다.
  순정 system 버퍼에 해당 출력이 남으면 값을 확보할 수 있다. 런타임 출력/캐시 잔존은 아직 미확인이다.
- 수집기에 SystemService의 기존 system 버퍼를 읽는 logcat -d를 추가했다.
  세 SML 문구만 builtin read/case로 선별하고 종료 코드·줄 수·원본을 USB에 남긴다.
  기존 HMG 파일 복사와 구분해 oem_logcat_exit/oem_sml_log_lines로 검사한다.
  기존 USB 스크립트와 안내를 백업한 뒤 D드라이브에 복사하고 SHA256을 대조했다.
  이 보완판의 실제 OEM 로그 확보는 차량 실행 결과를 대기한다.
- Python61개 중60개 통과/기존 symlink 권한1개 skip, collector12개 fixture와 셸 구문 검사가 통과했다.
  고정 read-only 인자, 관련 문구만 보존, 읽기 실패와0건 캐시를 검사했다.
  Java/API19 검증은 변경이 없어 이전 결과를 재사용했다.

## USB 재수집 분석 — 2026-11-07

- 사용자가 이전 RGB·밝기 수동 변경을 이미 수행했다고 확인했다.
  조작 완료와 숫자 보존 여부를 구분한다. 기존 수집 로그의 재검색에서도 원시 RGB/밝기/hex 값은 없었다.
  다음 단계는 해당 값을 보존할 관측 경로 검토이며 단순 조작 반복을 요청할 근거는 없다.

- 수정본으로 기존 Car Report2개와 맵/검사/설치/로그의7개 파일을 복사했다.
  collection.txt 포함8개를 private에 보관하고 USB 사본과 SHA256을 대조했다.
- map.status=probed, hooks_on=true, 과거342/342 resolve를 JSON으로 확인했다.
  map-check의 폴더와 파일 해시 목록은 수집한 car.json과 일치한다.
- 현재 맵은 unknown/gl3hev/kor/260902다. missing은 themesettings.split_item.type 하나다.
  분할화면 편집 항목이며 확인한 SML/relay 의존 목록에 직접 포함되지 않는다.
- 세 SML 후보는 선택 framework.json에 있고 두 보고서의 탐색 서명과 일치한다.
  loader/class_loader/intercept_key도 제공된 맵에 있다.
- HMG 로그의 LightRelay 등록14개와 전달 실패14개를 확인했다. 실패는 모두 Connection refused다.
  onReceive가 SML_STATUS 배열을 받으면 send를 호출하는 고정 소스에 따라, 기록된 배열 수신 후
  전달 시도를 추론할 수 있다. 중복 오류 억제가 있어 실제 이벤트 수는 알 수 없다.
  raw RGB/밝기/길이·정규화·현재 서비스 상태는 미확인이다. vehicle.log는 수집되지 않았다.
- 보고서2개에는 활성 nemesis-shortcut 하나만 있다. vehicle 서비스의 on-demand 수명과
  일치할 가능성이 있으나 당시 services.txt/프로세스 상태를 확보한 것은 아니다.
- 기존 car_report_evidence.py를 실제 보고서와 맵 사본에 실행해 정상 요약을 확인했다.
  runtime reception=unknown인 자동 요약은 맵만 분석한 결과다. 로그를 대조한 추가 판단은
  private/k8-sml-evidence-review.json과 K8_SML_MAP_CHECK 문서에 기록했다.
- 이번 변경은 조사·진행 문서이며 기존 Python57개/Java190개 통과 결과를 재사용했다.
  실제 원시값 취득과 Gen5W BLE 및 종단 동기화 검증이 다음 단계다.

## K8 Map checked 대조와 USB 수집 준비 — 2026-11-07

-2277aa3에서 시작해 원격 codex/device-evidence와 일치함을 확인했다.
  사용자는 GL3HEV 한국 Gen5W, HMG-API1.0.2, NEMESIS1.16.4를 확인했고 차량 화면에서
 342/342 resolve, probed/kor260902, 미매핑1개와 Map checked를 읽어 전달했다.
  원본 Car Report JSON은 로컬 private 및 연결된 K8 USB에서 발견하지 못했다.
- HMG-API1.0.2 고정 소스의 CarMap/MapCheck/Report/Input/LightRelay/CarCallbacks를 대조했다.
  세 SML 후보는 RGB 설정·configuration 설정·상태 요청이다. 방송 수신은 별도 relay 경로다.
  Input의 loader/class_loader/intercept_key와 기본 진입 Hook도 수신 경로 의존성에 포함된다.
  Map checked와 맵의 verified 표시, 실행 중 Hook과 실제 이벤트를 각각 구분했다.
- 이전 로컬 오프라인 후보는330개이며 themesettings.split_item.type이 missing이었다.
  현재342개 맵과 동일하다고 판정하지 않았다. 현재 미매핑 지점의 이름과 영향은 JSON 대기다.
- car_report_evidence.py와 합성8개 검사를 추가했다. 기존 Car Report와 선택 framework 사본의
  후보/항목/누락을 분리하고 private에 요약한다. 실제 장치에 접속하는 기능은 없다.
- 기존 source-only passive 진단을 재사용하고 RGB3색·밝기 변경·unsigned raw 보존 fake 검사를 추가했다.
  K8 정규화와 Lotus E1/E2 분기 확인에 연결할 관측 데이터 계약을 K8_SML_MAP_CHECK에 기록했다.
- 사용자 추가 지시로 D드라이브 USB에 기존 파일 수집용 descriptor·스크립트·안내를 복사하고 해시를 확인했다.
  이전 descriptor는 NEMESIS-backup/before-k8-ambient-readonly-*에 백업했다.
  스크립트는 GL3HEV.KOR/260902/API19에서 기존 보고서·맵·map-check·설치 기록·HMG/vehicle 로그를
  USB에 복사한다.
- 사용자가 첫 실행을 마친 USB에서 collection.txt를 확보했다. 기존 보고서2개는 찾았지만
  SIZE_UNAVAILABLE로 복사0개였다. wc 실패 fixture에서 같은 실패를 재현한 뒤 의존성을 제거했다.
  GL3HEV 맵의 제조사 경로도 탐색하도록 보완했다. 첫 기록은 private에 보관하고 수정본을 USB에 해시 확인 후 반영했다.
  현재 원본 JSON은 차량에 있어 재실행 결과를 대기한다.
- python tools/check.py에서 Python55개 중54개 통과/기존 symlink 권한1개 skip,
  Java 코어139+host14+진단37=190개 통과. 이후 수집기9개 fixture가 통과했다.
  최종 전체 Python58개 중57개 통과/기존 symlink 권한1개 skip이다.
  실제 API19 계약 컴파일은 production Java 변경이 없어 이전 결과를 재사용했다.
- 차량 시스템/맵/설정 변경, 새 Hook·모듈 설치, BLE·차량 조명 명령, map 검사/생성/적용/재시작은 수행하지 않았다.
  실차의 raw RGB·밝기 수신과 추가 하드웨어 필요 여부는 미확인이다.

## 후속 진단 준비 — 2026-11-07

- 사용자 지정 `codex/device-evidence` / `ec12e13`에서 이어서 작업했다.
  이 채팅의 엔진사운드 작업보다 본 엠비언트 진단을 우선한다.
- 기존 `phone_probe.py`/`att_evidence.py`의 명령과 제한을 재사용했다.
  RGB 후보 3색은 구분 가능하다. 설치본 E1 기본 길이 `04` 밝기, E2, 암호화는 현재 후보 decoder에서
  미확인으로 남는 것을 합성 검사로 명시했다. 실제 HCI와 동작 시각표가 있어야 대상 분기를 판단한다.
- `k8_probe.py`를 추가했다. 이미 허용된 ADB에서 한국 K8/API19를 먼저 확인하고 고정 속성·BLE 선언·
  HMG 앱 버전·enable 마커와 **이미 있는** map-check의 요약만 읽는다. root/engine 명령은 없다.
  원본 오류/JSON·장치 serial을 저장하지 않고 결과를 private에 제한한다.
  과거 map-check/마커와 현재 runtime Hook·ambient 수신을 구별하며 후자는 계속 미확인이다.
- `CAPTURE_SESSION.md`와 빈 조작 기록표를 추가했다. 차에서는 버전·기존 상태 화면을 먼저 기록하고,
  Galaxy에서는 연결 전 HCI 시작 → 3색 → 밝기 3단계 → 복원 → 비공개 보고서 → 로깅 복원의 순서다.
- 새 Python 검사 포함 41개 중 **40개 통과/1개 skip**, passive 메타데이터와 diff 공백 검사 통과.
  skip은 기존 Windows 디렉터리 symlink 권한 제한이다. Java/Hook 코드는 바꾸지 않아 기존 179개와
  API19 계약 컴파일은 재실행하지 않았다.
- 이 PC에서는 TShark가 없어 실제 HCI→TSV 실행을 검증하지 못했다. AOSP HCI와 Wireshark ACL 공식
  문서, HMG-API 1.0.2 고정 커밋의 map-check/enable 경로만 필요한 범위로 확인했다.
- 차량·조명 접속, 실제 BLE 송신·스캔, root·설치·시스템 변경은 하지 않았다.
  갤럭시/차량 자료 원본이나 계정·키를 외부로 전송하지 않았다.
- 저장소가 지정한 MiniMax skill 파일은 이 PC에 없어 외부 자문을 실행하지 않고 Codex가 직접 검토했다.
- 변경은 같은 기능 브랜치에 커밋·Push한다. main 병합·강제 Push는 하지 않는다.

## 완료
- 빈 로컬 Git 폴더와 기준 GitHub 저장소를 확인했다. 원격은 private=true, size=0, default_branch=main이었다.
  첫 세션에서는 사용자 선택에 따라 로컬 작업만 진행했다.
- 후속으로 사용자가 GitHub 로그인 완료를 알렸다. 인증·쓰기 권한·비공개 상태·원격 브랜치 부재를
  다시 확인했고 원격 동기화를 재개했다. main과 현재 구현 브랜치를 분리해 보존한다.
- 필수 문서, AGENTS 규칙, 민감정보·빌드·원본 로그 제외 규칙을 만들었다.
- HMG-API 1.0.2, Light Vibes, Gen5W Ambient Sound, HMG-Seogaemo, 공식 NEMESIS와
  Lotus 공개 구현 3개를 실제 소스/최근 커밋/라이선스 기준으로 조사했다.
  `docs/research/`의 SHA 고정 소스 링크 74개는 로컬에서 경로/줄 범위를 대조했다.
- HMG 순정 SML 방송 경로와 public ambient 읽기 API 부재를 확인했다. K8 지원 증거와 혼동하지 않았다.
- Java7/API19 raw 파서, 테마 보존, 명시적 EV6 참조 변환, **공개 후보** 패킷 코덱을 작성했다.
- 모의 transport 동기화 코어: 기본 OFF, 필드별 중복 제거, 최신값 병합, 색상·밝기 공정 처리,
  OFF 억제, 연결 복원, 실패 시 100ms~5초 backoff, 민감정보 없는 카운터를 구현했다.
- PC용 마지막 상태 파일의 입력 검증·원자 교체·복원을 구현했다. 복원해도 자동 활성화되지 않는다.
- passive 진단 Hook 소스와 `.disabled`/diagnostics=false 기본값을 만들었다.
  실제 수신기 코드는 fake에서 검사하고 실제 Android/HMG 소스와도 별도로 컴파일했다.
- 정차 상태 Galaxy HCI 수집·익명 요약·Gen5W 읽기 진단·후속 승인 조건을 문서화했다.
- 후속 단계에서 사용자 요청대로 Galaxy에 기존 무선 ADB로 연결했다.
  Android 17/API37, LotusLamp X `com.szelk.ledlamppro` 5.21.03(425), BLE feature 선언을 실제로 읽었다.
  이 선언은 Gen5W BLE나 실제 컨트롤러 연결 성공의 증거가 아니다.
- 고정 앱 base APK만 로컬 private에 확보했다. 269,476,264바이트,
  SHA256 `d0eca36d1f1bd47b0647a47d27e68fedcd57118932d8a73f4f15317b117749e2`.
  APK·원본 DEX·설치 경로·IP·기기 serial·원본 dump는 커밋하지 않는다.
- 읽기 전용 Galaxy 버전 수집 도구와 TShark 선별 ATT TSV 분석 도구를 만들었다.
  출력은 private에 제한하고 후보 형식·연결 세션·입력 범위·정보 비출력을 검사한다.
- 실제 설치본의 E1/E2 builder·조건부 암호화·GATT write와 FFF0/FFF3 기본값 경로를 DEX 위치로 확인했다.
  [설치본 분석](research/LOTUS_APK_5_21_03.md)에 근거를 남겼다. RGB 일부가 공개 후보와 일치하지만
  밝기·전원·암호화 분기는 일반화할 수 없다. 실제 선택된 컨트롤러 분기는 미확인이다.

## 진행 중
재수집 자료에서 SML 배열 수신·전달 시도를 대조했다. 원시값·현재 서비스 상태와 실제 HCI 자료가 남아 있다.
실제 transport와 raw→K8 정규화 통합은 아래 증거가 확보될 때 진행한다.

## 미확인
1. 실행 중 서비스의 현재 상태, SML payload 길이·RGB·밝기 원시값과 K8 정규화.
   현재 맵과 미매핑 ID는 JSON 대조를 완료했고 SML 배열 수신의 간접 흔적을 확보했다.
2. 순정 테마의 실제 RGB, dim/drive-mode/자동 밝기, 마지막 저장값·현재 출력의 구분.
3. Gen5W 칩셋·BLE Central/GATT API/권한·통화/미디어/Android Auto 병행.
4. 실제 APK 서명·실행 중 선택된 통신 분기와 사제 컨트롤러 장치군·UUID·초기화/인증·명령·keepalive.
5. 시동/QuickBoot 자동실행, system_server 설정/OFF 전달, 차량용 영구 저장과 실제 재연결 수명.
6. 실제 광색/밝기 대응, 안전한 전송 간격, 종단 200ms 성능. 50ms는 모의 간격일 뿐이다.

## 테스트
- `python tools/check.py --diagnostic` **성공**.
- 코어 139 + host 복원 14 + 진단 fake 26 = **179개 assert 검사 통과**.
- 기기 자료 도구 추가 후 `python tools/check.py` 성공: Python unittest 31개 중 **30개 통과/1개 skip**,
  기존 Java 179개 검사 통과. 실제 Windows 디렉터리 symlink 생성은 권한 부족으로 skip했다.
  Java 소스는 변경하지 않아 API19 계약 컴파일을 다시 수행하지 않았다.
- Java7 코어·진단을 실제 API19 bootclasspath와 HMG-API 1.0.2 전체 runtime 소스에 대조해 컴파일했다.
  프로젝트 코드 경고 없음. 상류 runtime의 기존 unchecked/serial 경고 8개는 남아 있다.
- 독립 검토가 연속 RGB 변경의 밝기 starvation을 재현했다. 실패하는 회귀검사 확인 후 공정 처리로 수정했다.
  전송 중 최신 이벤트를 이미 보냈다고 오인하지 않도록 상태 snapshot도 검사한다.
- Git diff 공백 검사 통과. 원본 조사·도구·캡처·로그·빌드 디렉터리는 추적하지 않는다.
- **실행하지 않음:** 차량·조명 접속, BLE scan/connect/write, 상태 요청/Binder/CAN/LIN 명령,
  시스템·펌웨어 수정, DEX 패키징/서명/설치, 실차 성능 시험.

## GitHub
- 기준 원격: `https://github.com/dlatnqls0815-spec/k8-ambient-sync.git`.
- main 초기 기준: `4348d3a` — 프로젝트 규칙·필수 문서.
- 초기 작업 브랜치: `codex/bootstrap-offline-sync`.
- 현재 작업 브랜치: `codex/device-evidence` (원격 최신 bootstrap에서 분기).
- 기기 자료 도구 커밋: `4e1d20e` — 읽기 전용 수집·로컬 분석과 합성 테스트.
- 조사 커밋: `3f529d9` — 소스 근거·진단 절차·라이선스.
- 구현 커밋: `9a3b8b2` — 코어·host·진단 소스·검사 도구와 테스트.
- 이 진행 기록과 README의 후속 커밋은 `git log -1`로 확인한다.
- 커밋 작성자는 Codex다. 전역 Git 설정/로그인/provider/MCP를 변경하지 않았다.
- GitHub 로그인 확인 후 초기 main과 작업 브랜치의 원격 동기화를 재개했다.
  강제 Push/PR/merge는 하지 않는다. GitHub 업로드와 Codex Cloud 환경 연결은 별도이며 후자는 미설정이다.
- 로그인 직후 동기화 작업은 문서만 변경했다. 이후 기기 자료 도구를 별도 브랜치에 추가했다.
  업로드 전 민감정보 패턴·바이너리·제외 디렉터리 추적을 재점검한다.
- `dist/k8-ambient-sync-local.bundle`은 PC 이동용 export다. GitHub를 대신하는 기준 저장소가 아니다.
  최종 문서 커밋 후 생성하고 `git bundle verify`로 확인한다. 복제 방법은 README에 있다.

## 다음 작업
1. 정차 상태에서 펌웨어/소프트웨어 버전, 현재 HMG Hook 요약, 백업/복구 경로를 확보한다.
   K8에서 passive 수신 소스를 설치할 수 있는지 판단할 최소 자료다.
2. Galaxy 기존 공식 앱으로 정상 조작한 HCI를 로컬 비공개 폴더에 수집한다.
   원본을 외부 AI/저장소에 보내지 않고 필요한 조명 제어 동작만 익명 요약한다.
   현재 사용자는 차량에 접근할 수 없으므로 수집을 진행하지 않았다.
3. 기존 승인된 Gen5W 진단 환경에서 BLE 노출을 읽기 전용으로 확인한다.
4. K8 상태 매핑·대상 프로토콜을 확정한다. 설치물/롤백을 준비한 뒤 사용자 확인을 받아 정차 시험한다.
   확인 전에는 실제 송신을 추가하지 않는다.
5. A안 직접 BLE가 우선이며 실제 제약이 확인될 때 B안 Galaxy 중계, 그 뒤 C안을 검토한다.
6. 음악/속도/순환 확장은 기본 실차 동기화가 안정화된 이후로 남긴다.
