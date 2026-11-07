# 정차 상태의 비파괴 진단 절차

이 문서는 차량에 접근 가능한 때 수행할 절차다. 초기 세션에서는 기기에 접속하지 않았다.
후속 세션에서는 사용자 요청에 따라 Galaxy에 기존 무선 ADB로 연결해 OS/앱 메타데이터와
고정 Lotus 앱의 base APK만 읽었다. 차량·조명 접속, Bluetooth 설정 변경·송신은 하지 않았다.
차량 설치 및 시스템 수정은 별도 사용자 확인 후 진행한다.

자료 수집·로컬 분석 명령은 [EVIDENCE_TOOLS](EVIDENCE_TOOLS.md)를 참조한다.
다음 실차 방문의 최소 순서와 작성용 기록표는 [CAPTURE_SESSION](CAPTURE_SESSION.md)에 있다.
이번 수집에서는 기존 map-check만 읽는다. `verify-map`/`report`/`map-car`를 새로 실행하지 않는다.

## 1. 최소 준비 자료

- 정차·주차 상태에서 인포테인먼트의 모델/소프트웨어/펌웨어 버전만 기록한다. 사진에 VIN, 계정, 목적지, 차량번호가 있으면 가린다.
- 현재 NEMESIS와 HMG-API의 설치 버전, 활성 모듈, Hook 성공/실패 상태를 기록한다. `1.0.2 설치됨`을 Hook 호환성 증거로 대신하지 않는다.
- 갤럭시 설정의 앱 정보에서 LotusLamp X 버전을 기록하고 해당 앱의 Play 항목 링크에 있는 패키지 ID를 비교한다. 공개 후보 패키지는 `com.szelk.ledlamppro`다. 설정 화면에 패키지가 표시되지 않으면 추측해 채우지 않는다. 이미 승인된 ADB가 있다면 해당 패키지의 로컬 `dumpsys package`에서 versionName/versionCode만 추려 기록할 수 있다.
- 순정 색상/밝기/ON/OFF의 현재 값을 수기로 남긴다. 차량 펌웨어·원본 바이너리·전체 백업은 Git 밖에 보관하고 복구 경로를 확인한다.
- 기존 HMG-API에 HMG-Seogaemo를 혼합 설치하지 않는다. 새 모듈 설치·자동 실행 등록·펌웨어 변경·CAN/LIN 송신은 이 진단 범위에 없다.

## 2. 갤럭시: 기존 앱의 정상 통신만 관찰

Android 공식 문서는 개발자 옵션의 Bluetooth HCI snoop log를 활성화하고 Bluetooth를 재시작한 뒤 수집하도록 설명한다. Samsung/One UI 버전에 따라 메뉴와 로그 제공 방식은 다를 수 있다. 이 단계는 사용자가 수동으로 수행하며 다른 기기 통화·오디오가 없는 시간에 짧게 진행한다([AOSP Bluetooth 디버깅](https://source.android.com/docs/core/connect/bluetooth/verifying_debugging)).

1. 휴대폰의 현재 로깅 설정을 기록한다. 기존 개발자 옵션이 없다면 휴대폰에서만 활성화한다. 차량의 개발자/엔지니어링 설정을 새로 열지 않는다.
2. 휴대폰 Bluetooth HCI snoop log를 켜고 휴대폰 Bluetooth를 재시작한다. 정상 연결 중인 다른 기기의 연결도 끊길 수 있으므로 통화 중에는 수행하지 않는다.
3. 이미 사용 중인 LotusLamp X로 **알고 있는 본인 조명만** 정상 연결한다. 타사 클라이언트나 자동 탐색 도구는 실행하지 않는다. 연결 전부터의 기록이 있어야 초기화 누락을 피할 수 있다.
4. 아래 순서로 한 번에 한 항목만 바꾼다. 각 동작 사이 3–5초를 두고 시각·앱 표시·조명의 실제 반응을 수기로 기록한다. 음악/장면/속도 효과는 끈 상태로 시작한다.

| 순서 | 기존 앱에서 수행할 동작 | 확인 목적 |
|---|---|---|
| 0 | 연결 후 5초 대기 | 초기화, discovery, 자동 keepalive 관찰 |
| 1 | 빨강 → 초록 → 파랑 | RGB 바이트와 채널 순서 구분 |
| 2 | 같은 색상에서 밝기 25 → 50 → 75 | 별도 밝기 명령, 단위, RGB 변경 여부 |
| 3 | 앱의 OFF → ON | 전원 명령과 재점등 시 색상 보존 |
| 4 | 앱 정상 종료 → 재실행·정상 재연결 | 초기화 반복, 재연결 동작 |
| 5 | 앱에서 원래 색상·밝기로 복원 | 사용자의 기존 설정 복구 |

앱이 숫자 입력을 제공하지 않으면 표시된 값/위치를 기록하고 정확한 25/50/75라고 쓰지 않는다. 원래 상태를 모르면 임의로 복원했다고 보고하지 않는다. 공장 초기화·장치 삭제·페어링 재설정·전원 배선 분리·무작위 패킷 시도는 하지 않는다.

5. Android 개발자 옵션의 버그 보고서 또는 이미 승인한 USB 디버깅 환경의 `adb bugreport`로 로컬 비공개 폴더에 저장한다. 여러 기기가 있으면 대상 serial을 로컬에서 명시한다. 버그 보고서는 앱 로그와 계정·네트워크·장치 식별정보를 포함할 수 있다([Android 버그 보고서](https://developer.android.com/studio/debug/bug-report)).
6. HCI snoop를 원래 설정으로 되돌린다. 원본 ZIP/BTSnoop는 Git에 추가하지 않는다. `/data/misc/bluetooth/logs`에 접근이 막혀도 root·권한 변경을 하지 않는다. 기기별 버그 보고서 추출 경로를 확인한다. AOSP의 `btsnooz.py`는 공식 출처를 확인한 뒤 로컬에서 사용하는 대안이다.

## 3. HCI 오프라인 분석

캡처 분석은 로컬에서만 수행한다. 원본 파일이나 전체 로그를 외부 AI에 전송하지 않는다.

- 연결 이벤트로 LE/Classic을 먼저 구분한다. 사용자 대상 연결에 일관된 `controller-A` 별칭을 부여한다.
- LE라면 앱이 수행한 GATT service/characteristic discovery와 attribute handle을 연결한다. 캡처에 discovery가 없으면 UUID를 추측해 채우지 않는다.
- 시각표의 단일 동작에 해당하는 ATT write의 handle, write 방식(응답 유무), payload를 비교한다. 초기화, keepalive, notify와 명령을 섞지 않는다.
- RGB 세 개 이상의 단색, 밝기 세 단계, ON/OFF를 반복 비교한다. 상수 바이트를 체크섬으로 단정하지 않는다. 재연결 후 같은 동작도 일치하는지 확인한다.
- 보안 절차가 있으면 유무만 기록한다. pairing key, IRK/LTK, 토큰, 인증 payload를 공개하지 않는다.
- 후보 자료와 바이트가 다르면 새로운 미확인 장치군으로 남긴다. 이름·UUID가 같다는 이유로 기존 프로필을 활성화하지 않는다.

커밋 가능한 요약에는 상대 시간, 동작명, 공개 표준 UUID, 별칭, 검토된 조명 제어 payload와 해석만 남긴다. MAC/serial/VIN/광고명의 고유 접미사/절대 시각/계정/주소/전화번호/페어링·인증 자료를 제거한다. 단순 문자열 치환한 원본 캡처를 익명화 완료본으로 간주하지 말고 필요한 필드로 새 요약표를 작성한다.

## 4. Gen5W: 먼저 실행 환경과 BLE 접근성 확인

Gen5W를 최신 Android Automotive로 가정하지 않는다. 조사한 HMG-API는 Android 4.4/API 19 Dalvik Hook을 대상으로 하지만, 사용자의 실제 펌웨어와 Hook 호환성은 별도로 확인해야 한다([HMG 조사](research/HMG_API.md)). **기존에 승인되어 사용 중인 진단 세션**에서만 다음 읽기 전용 항목을 확인한다. 접근 수단이 없으면 새 진단 서비스를 열거나 설치하지 말고 미확인으로 남긴다.

| 단계 | 읽기 전용 자료 | 해석의 한계 |
|---|---|---|
| OS/CPU/API | `uname -a`, `/proc/version`; Android가 확인되면 `getprop ro.build.version.sdk`와 `getprop ro.build.version.release` | 사용자 펌웨어를 공개 소스의 대상 버전과 비교한다. 전체 getprop 출력은 수집하지 않는다. |
| Bluetooth 드라이버 | `/sys/class/bluetooth` 존재 및 `hci*` 장치의 driver 경로, 기존 로그의 칩/스택 정보 | HCI 장치가 보여도 BLE Central/GATT Client 권한·동시 사용은 미확인이다. |
| Android BLE 노출 | Android가 확인되면 `pm list features`의 `android.hardware.bluetooth_le`, `dumpsys bluetooth_manager`, `service list`의 Bluetooth/GATT 서비스 유무 | 제조사 스택이 API를 실제로 노출하는지 확인한다. 명령이 실패하거나 feature가 없어도 칩 자체의 기능 부재와 구별한다. |
| 다른 스택이 발견된 경우 | BlueZ와 도구가 실제로 있을 때만 `bluetoothctl list`, `bluetoothctl show`, `btmgmt info`; D-Bus `org.bluez` 존재 | Android에 BlueZ 도구가 있다고 가정하지 않는다. 도구 부재만으로 BLE 불가 판정 금지. |
| HMG Hook | 기존 HMG 상태/로그의 firmware map, ambient 관련 함수 resolve 및 이벤트 유무 | 전체 로그 대신 필요한 항목만 익명 요약한다. |

이 단계에서 `scan on`, connect/pair, `power on`, `hciconfig ... up`, 서비스 재시작, 권한 변경, `setprop`, CAN/LIN write를 실행하지 않는다. 기존 로그에 BLE/GATT 증거가 없으면 직접 연결은 계속 미확인이다. 일반 Android의 BLE Central/GATT API 문서는 갤럭시 대안의 근거일 뿐 이 Gen5W의 근거가 아니다([Android BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview), [GATT Client](https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server)).

## 5. 실차 쓰기 단계로 넘어가는 조건

먼저 차량 펌웨어/복구 경로/백업, HMG-API 호환성, 읽기 전용 순정 이벤트, 대상 장치의 실측 프로토콜을 확보한다. 설치 파일과 롤백 절차를 검토 가능한 형태로 준비한 다음 사용자 확인을 받는다. 이후에도 정차 상태에서 단일 사제 조명만 대상으로 시작하고 순정 제어 신호는 조작하지 않는다.

직접 BLE 연결 시험을 승인받기 전까지 A안은 실현 가능성이 미확인인 우선 후보다. 통화/미디어/Android Auto 동시 연결, 시동 후 자동 복구, 실제 200ms 지연은 별도 실차 검증 항목이며 이 문서로 보장되지 않는다. A안이 실제로 불가능하거나 불안정하다는 증거가 나올 때 B안 갤럭시 중계를 평가한다. 추가 장비는 A/B안의 한계를 확인한 뒤 검토한다.
