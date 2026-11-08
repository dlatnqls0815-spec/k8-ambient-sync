# K8 헤드유닛 BLE GATT 비활성 — 2026-11-08 실차 점검

## 결과

NEMESIS 패키지 `k8ambient.bleprobe` 0.2.0(DEX, root)을 사용자가 설치·실행했다. 결과는 `RESULT SCAN_REJECTED`다.
차량 결과 파일(`/data/local/tmp/k8-ble-probe/result.txt`)은 `PROBE 0.2.0 sdk=19 release=4.4.2 mode=scan-only` →
`SCANNING seconds=12` → `RESULT SCAN_REJECTED`였다. 패키지 설치와 DEX 실행 경로 자체는 **정상 동작**했다.

## 원인 (무선 ADB, 읽기 전용 확인)

| 확인 | 결과 |
|---|---|
| `pm list features` | `android.hardware.bluetooth`, `android.hardware.bluetooth_le` 선언 |
| 실행 중인 `com.android.bluetooth` 서비스 | AdapterService, A2dp, Headset, HfDevice(BTCM 포함), MapClient, AvrcpController. **GattService 없음** |
| `Bluetooth.apk` 리소스 `bool/profile_supported_gatt` | **`false`** (a2dp·hs_hfp·avrcp_ct·mce·map_1_2·hfdevice만 `true`) |
| `Bluetooth.apk` 매니페스트 | `.gatt.GattService`가 있으나 `android:enabled=@bool/profile_supported_gatt` → 꺼진 컴포넌트 |

Android 4.4에서 `BluetoothAdapter.startLeScan()`과 `BluetoothDevice.connectGatt()`는 GATT 서비스(`IBluetoothGatt`)가
없으면 실패한다. 따라서 **이 차량 펌웨어는 Android API로 BLE 스캔·연결을 할 수 없도록 GATT 프로필을 꺼 두었다.**
`bluetooth_le` 기능 선언만으로 BLE 사용 가능을 판단하면 안 된다는 이전 주의가 맞았다.
칩·네이티브 스택이 BLE를 지원하는지는 별개이며 미확인이다.

원본 사본(Git 제외): `private/oem-bt/Bluetooth.apk`(SHA-256 `2c00ccdd…a53a324`), `Bluetooth.odex`(`bc63593d…40f23`),
`private/vehicle-adb/logcat-bleprobe-20261108.txt`(로그 버퍼가 반복 상태 조회로 차서 점검 시점 줄은 남지 않음).

## A안(차량 직접 BLE)의 남은 길 — 모두 시스템 변경, 별도 승인 필요

| 방법 | 내용 | 위험 |
|---|---|---|
| GattService 컴포넌트 활성화 | root로 `pm enable com.android.bluetooth/.gatt.GattService` 후 Bluetooth 재시작. 4.4의 BluetoothManagerService는 켜짐 시 GATT 서비스에 bind한다. 파일은 바꾸지 않고 `packages.xml` 설정만 바뀐다. 되돌리기는 `pm disable`(기본값 복귀가 아니라 명시적 비활성) | 네이티브 스택에 GATT가 없거나 불안정하면 Bluetooth 전체(전화·무선 AA 연결)가 영향을 받을 수 있다. 정차 시험·즉시 원복 필요 |
| HMG-API 훅으로 설정값 `true` 반환 | Bluetooth 프로세스 안에서 `profile_supported_gatt`를 바꾼다. 파일 변경 없음 | 새 모듈 개발·설치. 위와 같은 Bluetooth 안정성 위험 |
| `Bluetooth.apk` 리소스 수정 | 시스템 파티션 변경 + odex 재생성 | **하지 않음** |

## 다른 안

- **B안 Galaxy 중계:** 휴대폰은 이미 Lotus와 BLE로 동작한다. 차량 → 휴대폰으로 SML 상태(9바이트)만 넘기면 된다.
  차량에서 쓸 수 있는 통로 후보는 기존 Bluetooth 클래식(RFCOMM) 또는 네트워크다. 클래식 프로필은 켜져 있다.
- **C안 별도 장치:** 예) ESP32가 차량에서 클래식 Bluetooth나 USB로 상태를 받아 BLE로 Lotus에 보낸다.

## GattService 활성화 시험 — 2026-11-08 16:48~16:54 (사용자 승인)

무선 ADB + `nemesis root -c`(root)로 진행했다. 셸 권한에는 컴포넌트 변경·Bluetooth 관리 권한이 없다.

| 단계 | 결과 |
|---|---|
| 사전 상태 | `dumpsys package com.android.bluetooth`에 컴포넌트 재정의 없음. `packages.xml` 백업은 명령 따옴표 오류로 **실패**(변경 전 dumpsys로 상태를 기록) |
| `pm enable …/.gatt.GattService` | 성공. Android가 Bluetooth 앱 프로세스를 재시작했고 시스템이 GattService에 bind |
| 점검 1회 | `SCAN_REJECTED` |
| `am startservice … STATE_CHANGED STATE=12`(root) | GattService 시작 명령 전달 |
| 점검 2회 | `BtGatt.GattService registerClient()`까지 도달. **네이티브 등록 응답 없음** → 10초 뒤 `SCAN_REJECTED` |
| 원복 `pm disable …/.gatt.GattService` | 성공. 서비스 목록이 시험 전과 같아졌고(GattService 없음) 휴대폰 재연결 확인 |

해석: Java GATT 서비스는 켤 수 있지만 그 아래 Broadcom 네이티브 스택이 GATT 클라이언트 등록에 응답하지 않았다.
네이티브 BLE 클라이언트가 빌드에서 빠졌거나 꺼져 있을 가능성이 크다. 이 펌웨어에서 **A안(차량 직접 BLE)은 사실상 막혔다.**
더 시도하려면 네이티브 라이브러리 분석이 필요하고, 성공해도 시스템 수준 변경이 계속 필요하다.

남은 차이: 시험 전에는 컴포넌트 재정의가 없었고 지금은 `disabledComponents: GattService`가 명시돼 있다.
기본값(리소스 `false`)과 동작은 같다. 4.4 `pm`에는 기본값 복귀 명령이 없다.

**안전 기록:** 사용자는 정차 중이라고 했으나 16:51:51 점검 시점 로그에 `onVehicleSpeedChanged() speed: 38`과 감소하는
내비 잔여거리가 있어 주행 중으로 판단하고 작업을 멈췄다. 16:52:49 주차 브레이크 체결 확인 후에만 원복했다.
다음 차량 작업부터는 **명령 전에 로그로 속도 0·주차 브레이크를 먼저 확인**한다.

시험 중 로그 사본: `private/vehicle-adb/logcat-gatt-enable-test-20261108.txt`.

## 참고: 클래식 Bluetooth SPP는 동작 중

원복 후 로그에 순정 `HfDeviceBTCMSppApi`가 RFCOMM 서버 소켓으로 휴대폰과 SPP 연결을 맺는 기록이 있다.
차량 → 휴대폰으로 상태를 넘기는 B안(Galaxy 중계)의 통로 후보로 클래식 RFCOMM이 유력하다.
