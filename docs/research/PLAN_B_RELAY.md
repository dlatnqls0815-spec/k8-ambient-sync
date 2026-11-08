# B안: Galaxy 중계 — 구조와 시험 절차 (2026-11-08)

```text
순정 SystemService 로그 ──(읽기)──▶ [차량] K8 Ambient Relay (NEMESIS 서비스, app_process DEX, root)
   "SYS_SML_STATUS_M_RESP: 0x.."            │ 클래식 Bluetooth RFCOMM 서버, UUID 6b38a0d2-…b904
                                            ▼  줄 단위: "K8AMB 1", "SML <hex>", "PING"(10초)
                                    [휴대폰] K8 앰비언트 중계 앱 (포그라운드 서비스)
                                            │ K8LotusMapping → SyncEngine(관측 Lotus 프레임)
                                            ▼ BLE GATT FFF0/FFF3 Write Without Response
                                    LotusLamp X 조명
```

## 구성

| 위치 | 역할 |
|---|---|
| `core/.../RelayProtocol.java` | 순정 로그 줄 파싱, 중계 줄 형식 인코딩·디코딩. PC 테스트 |
| `core/.../K8LotusMapping.java` | raw1=꺼짐, raw2~11=10~100%, 직접색=하위 바이트, 테마=사용자 지정 색(없으면 이전 색 유지). PC 테스트 |
| `car-relay/src/…/CarRelay.java` | `logcat -b system -s SystemService:D`를 읽어 최신 상태 보관, RFCOMM 서버로 휴대폰 1대에 전송 |
| `car-relay/nemesis/` | NEMESIS 서비스 `svc/relay.sh`(부팅 완료 대기 후 실행, `mode=restart`), 상태 확인 `bin/status.sh` |
| `phone-relay/` | Android 13+ 앱. 차량·조명 선택, 테마 색 지정, 전원 옵션(실험), 상태 화면 |
| `tools/build_api19_dex.py --target relay`, `tools/build_nemesis_relay.py`, `tools/build_phone_relay.py` | 빌드(결과는 `private/`) |

## 안전 범위

- 차량 쪽: 로그 읽기와 RFCOMM 서버뿐이다. MICOM·조명·Bluetooth 설정 명령이 없다. 설치 훅도 없다.
- 휴대폰 쪽: 조명에 보내는 색·밝기 프레임은 HCI 실측 형식이다. 전원 프레임은 옵션을 켤 때만 보낸다.
  옵션이 꺼져 있으면 순정 꺼짐 때 조명은 마지막 상태로 남는다.
- 화면의 기기 주소는 마지막 두 바이트만 보인다.

## 빌드 (2026-11-08)

| 산출물 | SHA-256 |
|---|---|
| `private/car-relay/k8-ambient-relay.dex` | `856a4e92…71b8ccdeb` |
| `private/nemesis-relay/k8ambient.relay-0.1.0.zip` | `2e34af89…a88274377` |
| `private/phone-relay/k8-ambient-phone-relay.apk` | `1b81c478…96034ffb` |

PC 검사: Python 90개 중 89 통과·1 skip, 코어 213, 차량 중계를 API 19 `android.jar`로 컴파일.
휴대폰 앱은 Android SDK 36으로 컴파일·서명 검증만 했다. **실기기·실차 동작은 아직 확인하지 않았다.**

## 첫 시험 절차 (정차·P단, 로그로 속도 0 확인 후)

1. 차량: USB 또는 무선 ADB로 `k8ambient.relay` 설치. 설치 후 서비스가 자동 시작된다.
   Manager에서 Launch하면 마지막 로그 줄이 보인다(`listening`이면 RFCOMM 서버 준비).
2. 휴대폰: APK 설치 → 권한 허용 → 차량 선택(페어링된 헤드유닛) → LotusLamp X 앱 종료 → 조명 찾기 → 중계 시작.
3. 순정 앰비언트에서 직접색·밝기를 한두 번 바꿔 조명이 따라오는지 본다. 앱의 원시값·목표·보낸 패킷 수를 사진으로 남긴다.
4. 순정을 드림퍼플로 바꾸고 앱에서 원하는 색(R,G,B)을 입력해 "지금 순정 테마에 이 색 지정".
5. 끝나면 앱에서 중계 중지. 차량 서비스는 그대로 두거나 Manager에서 Uninstall.

## 아직 모르는 것

- 차량 `app_process`(root)에서 RFCOMM 서버 등록이 되는지. 순정 `HfDeviceBTCMSppApi`가 RFCOMM 서버를 쓰므로 가능성은 높다.
- 휴대폰이 차량에 RFCOMM 연결할 때 기존 HFP/A2DP 연결과 함께 동작하는지.
- 시동 직후 첫 상태: 로그 버퍼에 마지막 상태가 남아 있지 않으면 첫 조작 전까지 동기화가 늦을 수 있다.
- 삼성 배터리 최적화가 포그라운드 서비스를 멈추는지. 필요하면 앱을 "제한 없음"으로 둔다.

## 첫 실기기 시험 — 2026-11-08 17:21~17:40

| 단계 | 결과 |
|---|---|
| 정차 확인 | `getVehicleSpeed speed: 0`, 내비 `speed(0)` 확인 후 진행 |
| 차량 설치 | 무선 ADB로 zip 전송(md5 일치) → `nemesis install` 성공, `compat=full`. 서비스 `running`, 로그 `listening` |
| 차량 상태 읽기 | `state SML 009b002100ef0b0101` 등 순정 상태를 로그에서 읽음 |
| 휴대폰(Galaxy Z Fold, Android 17) | USB 디버깅으로 0.1.0 → 0.1.2 설치. 아래 버그 2개 수정 |
| 차량 ↔ 휴대폰 RFCOMM | **연결 성공**. `0078001c00ef0b0101` 수신 → 목표 켜짐 100% RGB 120,28,239 |
| 휴대폰 ↔ 조명 | 처음 저장된 `…74:00`은 다른 LED 기기(LEDnetWF, FFF0 없음)라 쓰기 없이 거부. `MELK-OA21PQ`(`…0E:1A`) 선택 후 **준비됨** |
| 송신 | 3패킷(전원 ON 실험 옵션, 색, 밝기 20% `7e04011401ff0201ef`) 전송 확인 |

수정한 버그:
1. 차량·조명 미선택 상태에서 시작하면 `ForegroundServiceDidNotStartInTimeException`으로 앱 종료 → 항상 `startForeground` 후 종료, 화면에서도 미선택이면 시작 막음.
2. target SDK 35 edge-to-edge로 상단 버튼이 제목 표시줄 밑에 숨음 → 제목 표시줄 숨기고 시스템 바 inset만큼 여백.

남은 확인: 조명 실제 색·밝기 반응(사용자 육안), 순정 변경 추종, 꺼짐/켜짐, 테마 색 지정, 시동 재시작 후 자동 재연결.

### 사용자 육안 확인 — 2026-11-08 17:4x

- 첫 패킷 후 Lotus 조명이 보라색·20%로 바뀌었다.
- 순정 앰비언트의 **색상 변경과 밝기 변경을 조명이 그대로 따라왔다.** B안 기본 동기화는 실차에서 동작한다.
- 꺼짐(실험 전원 명령)과 드림퍼플 테마 색 지정은 결과를 따로 확인하지 않았다.
