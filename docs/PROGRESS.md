# 진행 상황

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
