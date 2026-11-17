# ESP32(C안) 실차 확인 — 한 번에 묶어서

설계는 `docs/DECISIONS.md`의 "2026-11-17 — C안 전환"을 본다. 기존 B안(휴대폰 중계 0.4.8)은 그대로 동작하도록 남겨 두었으므로
아래 어느 단계에서 막혀도 휴대폰 앱의 "ESP32 모드"를 끄고 "중계 시작"을 누르면 원래대로 돌아간다.

## 가기 전에 PC에서 끝낼 것

| 항목 | 명령·확인 |
|---|---|
| 펌웨어 빌드 | `arduino-cli compile --fqbn esp32:esp32:esp32:PartitionScheme=huge_app esp32/k8amb_esp32` (esp32 코어 3.3.12) |
| 업로드 | `arduino-cli upload -p COMx --fqbn esp32:esp32:esp32:PartitionScheme=huge_app esp32/k8amb_esp32` (CP210x 드라이버 필요) |
| PC 시리얼 검사 | `python tools/esp32_serial_check.py --port COMx --jdk <JDK17>` → `ESP32 serial check: passed` (Java 코어와 계산 일치, 설정 저장·재부팅 유지, 모의 신호 흐름) |
| 휴대폰 앱 0.5.0 설치 | `adb -s <serial> install -r private/phone-relay/k8-ambient-phone-relay.apk` (사용자 승인 후) |
| 앱→ESP32 설정 전달 | 앱에서 "ESP32 모드" 켜기 → "ESP32 찾기" → 선택 → 토스트 "ESP32에 설정 전달 완료", 앱의 ESP32 상태에 펌웨어 `0.1.0`. ESP32 시리얼에 `PHONE SET …` 줄 |

차량 주소(`SET car`)는 휴대폰 앱이 이미 저장한 차량 주소를 그대로 넘긴다. 조명 주소도 같다.

## 차에서 (정차·P단, 각 단계 전 승인)

1. **정차 확인**: 차량 logcat에서 `BCMManager.getSpeed_KMHUnit() = 0.0` 또는 `SYS_SPEED_M: 0`.
2. **차량 중계 0.4.0 설치** (승인 필요): `MSYS_NO_PATHCONV=1 adb push private/nemesis-relay/k8ambient.relay-0.4.0.zip /data/local/tmp/`
   → `nemesis install --update /data/local/tmp/k8ambient.relay-0.4.0.zip` → 설치된 DEX md5 비교 → 임시 zip 삭제.
   - relay.log: `started`, `listening`(휴대폰용), **`esp32 listening`**. 휴대폰 B안이 여전히 붙는지(`phone connected`)도 본다.
   - `esp32 listening`이 없거나 오류면: 헤드유닛 스택이 비보안 SPP 서버를 거부 → 로그 그대로 받아 오고 0.3.5로 되돌릴지 판단.
3. **ESP32 전원**: 차량 USB 또는 시거잭 USB 어댑터. 처음엔 노트북 USB에 꽂아 시리얼을 보면서 한다(115200).
4. **휴대폰을 ESP32 모드로**: 앱에서 "ESP32 모드" 켬 → 휴대폰 중계가 멈추고 설정이 ESP32로 간다. 이때 조명 쓰기는 아직 꺼짐.
5. **차량→ESP32 연결 확인** (조명 쓰기 꺼진 채): ESP32 시리얼에 `CAR SML …`, `TARGET …` 줄이 나오고
   relay.log에 `esp32 connected`. 휴대폰 앱 ESP32 상태의 "차량 연결"이 연결됨.
   - 순정 앰비언트 색을 한 번 바꿔 `CAR SML` → `TARGET` → `TX lotus|zengge` 줄이 바로 나오는지 본다(이미 확인한 RGB·밝기 단계 반복 아님, 한 번이면 충분).
   - 실패하면 ESP32 시리얼의 채널 탐색 줄과 relay.log를 받아 온다. 휴대폰과 ESP32가 동시에 붙는지(두 번째 ACL)도 여기서 판명.
     채널을 직접 정해야 하면 시리얼에서 `SET carch <n>`.
6. **조명 BLE 쓰기 시험** (**별도 승인 필요**): 앱의 "ESP32가 조명에 실제로 쓰기" 체크 또는 시리얼 `SET output 1` + `SAVE`.
   - `STATUS`의 `ST lotus …연결 / 쓰기 n 실패 0`, `ST zengge …`가 늘어나고 조명 색·밝기가 순정을 따라가는지.
   - 과속 카메라 경고(지나갈 때 자연히), 드라이브 모드 에코·스포츠·스마트 전환(연동 켜짐일 때) 한 번씩.
7. **시동 껐다 켜기 한 번**: ESP32가 차량에 자동 재접속(3→30초 간격)하고, 첫 색이 600ms 페이드로 들어오는지.
   휴대폰 없이(휴대폰 블루투스를 꺼도) 동작하는 게 이 안의 목적이므로 휴대폰 블루투스를 끈 상태로도 한 번 본다.

## 되돌리기

- 휴대폰 앱: "ESP32 모드" 끄기 → "중계 시작". (ESP32 전원을 빼면 조명이 휴대폰에 다시 붙을 수 있다 — 조명은 한 번에 한 중앙만.)
- 차량 중계: 0.4.0도 휴대폰 서비스는 그대로라 되돌릴 필요가 없다. 문제가 있으면 `nemesis install --update` 0.3.5 zip.
- ESP32: 시리얼 `SET output 0` + `SAVE`, 또는 전원 분리.

## 받아 올 것

차량 `relay.log`(esp32 줄 전후), ESP32 시리얼 기록(노트북 연결 시), 휴대폰 `relay-events.log`의 `esp` 줄. 주소·식별자는 커밋하지 않는다.
