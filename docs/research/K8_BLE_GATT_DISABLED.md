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
