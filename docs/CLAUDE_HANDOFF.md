# Claude 인수인계

기준일은 2026-11-07, 작업 브랜치는 `codex/device-evidence`다.
저장소는 `https://github.com/dlatnqls0815-spec/k8-ambient-sync`다.
이 문서의 현재 상태를 먼저 읽고 `AGENTS.md`, `docs/DECISIONS.md`,
`docs/PROGRESS.md` 순으로 확인한다. PROGRESS와 AI_HANDOFF의 아래쪽은 과거 기록이다.

## 사용자 목표와 선호

2021 K8 GL3 HEV Gen5W의 순정 엠비언트 색상·밝기를 읽어 기존 LotusLamp X 사제 조명에
동기화한다. 차량 직접 BLE, Galaxy 중계, 별도 장치 순으로 검토한다.
사용자는 선호 테마가 **드림퍼플**이고, 사제 앱 **약 20%가 순정 최대 밝기와 비슷하다**고 했다.
체감 밝기 기준이며 중간 단계의 선형성은 검증 전이다.

사용자는 반복해서 차량에 다녀오는 것을 부담스러워한다. 기존 자료를 먼저 분석하고,
다음 실차 시험 전에 PC에서 재현·검증과 한 번에 끝낼 절차를 준비한다.
기존 RGB와 5/10/15/20% 조작을 수집 없이 반복 요청하지 않는다.

## 현재 구현과 확인한 사실

- 차량은 GL3HEV.KOR, 펌웨어 260902, Android 4.4.2/API19다.
  기존 HMG-API 1.0.2, NEMESIS 1.16.4 사용 기록이 있다.
- 기존 보고서·선택 맵은 확보했다. 342/342 resolve와 과거 map-check 일치를 확인했다.
  미매핑은 `themesettings.split_item.type`이다. 실제 runtime Hook 성공과는 별도로 다룬다.
- 순정 SystemService 로그에서 SML 응답, RGB 설정, mode=5 밝기 요청을 관측했다.
  최초 실시간 수집 39줄에서 밝기 요청값 1,3,4,8,7,11과 응답 7번째 바이트가 일치했다.
- 추가 정차 수집은 22줄이다. mode=5 값 1→2와 RGB 요청 1건, 이후 밝기 요청 3~11이 있다.
  사용자는 드림퍼플을 선택했고 원래 설정으로 복원했다고 했다.
  테마와 직접색을 각각 로그의 특정 RGB에 대응시키는 작업은 아직 끝나지 않았다.
- 순정 RGB는 채널당 16비트이며 255를 넘는 값도 있다.
  상위 바이트 의미, 테마·직접색 인코딩, 실제 광색 대응은 미확인이다.
- Galaxy는 Samsung Android17/API37, LotusLamp X 5.21.03(425)다.
  최초 HCI에서는 같은 LE 연결의 FFF0 서비스와 FFF3 쓰기 특성을 관측했다.
  ATT Write Command 0x52로 RGB 4개 및 밝기 형태 120개가 전송됐다.
  밝기 슬라이더 네 묶음의 끝값은 5/10/15/20이다.
- 실측 밝기 형태는 `7e 04 01 <level> 01 ff 02 01 ef`다.
  `tools/att_evidence.py`는 5~23 범위의 일치 형식을 `observed_events`에 구분한다.
  기타 형식은 별도로 검증한다.
- `SyncEngine.forObservedLotus()`는 색상·밝기 형태의 오프라인 재생 모드다.
  기본 출력 상한은 사제 20%다. 전원 OFF 입력은 재생을 멈춘다.
  이 모드는 HCI에서 본 프레임만 쓰며 전원 송신이 없다.
- `SyncEngine.forLotusWithStaticPower()`는 앱 바이트코드로 확인한 전원 프레임을 더한 별도 모의 모드다(2026-11-07 Claude).
  `tools/att_evidence.py`는 같은 전원 프레임을 `static_power_events`로 따로 분류한다.
  기존 `WporterE1Candidate`는 별도 공개 후보이며 기존 호출·테스트를 유지했다.

## 중요한 미확정과 실패

1. **K8 밝기 1의 의미를 다시 대조해야 한다.**
   이전 사용자 답변을 켜진 최저로 해석해 `toK8ProvisionalBrightnessPercent()`는
   1~11을 선형 정규화한다. 1→모의 사제2%, 11→20%다.
   추가 OFF→최저 시험에서는 raw1→2가 기록됐고 실제 소등/점등 반응은 확인되지 않았다.
   raw1을 최저 점등이나 OFF로 최종 확정하지 말고, 현재 함수가 임시임을 유지한다.
2. **진단 APK 설치 실패가 직접 연결 시험을 막는다.**
   사용자에게 설치·실행 승인을 받았지만 두 APK 모두
   `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES`로 거부됐다.
   동일 코드·동일 인증서의 원래 v1/v2와 SHA-1/RSA v1 전용 APK를 비교했다.
   PC 서명·ZIP·정렬 검사는 통과했고 차량에는 패키지가 등록되지 않았다.
   원인을 SHA256 문제로 확정할 근거는 없다.
3. 차량 BLE 기능 선언, Bluetooth 관리자 서비스, `bluetooth_on=1`, Android Bluetooth 패키지는
   관측했다. 직접 GATT 연결은 설치 실패 때문에 실행하지 못했다.
   별도 GATT Binder 서비스가 목록에 없다는 사실만으로 BLE 불가를 판정하지 않는다.
4. 추가 Galaxy USB 보고서는 확보했지만 현재/이전 BTSnoop에 ATT 쓰기 명령이 모두 0건이었다.
   조작 구간 불일치나 로그 순환 가능성이 있다. 전원·최저 밝기·재연결 동작은 미확인이다.
   사용자는 조작은 했지만 당시 실제 발광을 다시 보려면 차에 가야 한다고 했다.

## PC 재분석 결과 (2026-11-07 Claude)

자세한 근거는 [PC 재분석](research/PC_ANALYSIS_2026-11-07.md)에 있다.

- 두 진단 APK는 PC에서 v1 서명·다이제스트·ZIP 구조가 모두 정상이다. 순정 4.4 파서가
  INCONSISTENT_CERTIFICATES를 낼 조건이 보이지 않아 **제조사 설치 정책이 유력**하다. 재서명은 중단한다.
  거부 당시 설치 로그가 없어 확정하지 않는다.
- 추가 SML의 `1 → 2`가 계획된 "꺼짐 → 1단계"와 맞아 **raw1=OFF, raw2~11=1~10단계**가 유력하다.
  raw 값과 실제 소등을 함께 기록한 자료가 없어 임시 함수는 바꾸지 않았다.
- RGB 8개 모두 세 채널 상위 바이트가 같다. 사용자 답으로 드림퍼플 테마=상위 0x01,
  자주 쓰는 푸른 보라 직접색=상위 0x00이 대응됐다. 테마 복원이 RGB 요청으로 안 남은 이유는 미해결.
- Lotus 전원 프레임을 앱 바이트코드로 확정했다. ON `7e 07 04 ff 01 01 02 01 ef`, OFF `7e 07 04 00 01 00 02 01 ef`.
- 최초 HCI의 미확인 초기 쓰기는 시계 동기화(`83 hh mm ss 요일`)다. 별도 연결의 6개는 다른 기기다.
- 추가 BTSnoop 두 파일은 20:19 이후만 담고 조명 연결이 없다. 0건은 조작 구간이 순환으로 빠진 결과다.

## 우선 이어갈 작업

PC 분석은 끝났다([PC 재분석 5장](research/PC_ANALYSIS_2026-11-07.md)).

1. **사용자 선택:** 순정이 테마(상위 0x01)일 때 사제 조명 색 정책 A/B/C(5-3)를 고른다.
2. **차량 1회 방문:** [최종 현장 확인표](NEXT_PARKED_VISIT.md)를 그대로 진행한다.
3. 방문 후: raw1 소등이 확인되면 `toK8ProvisionalBrightnessPercent()`를 제거하고 OFF 사다리로 일원화한다.
   전원 프레임이 HCI와 맞으면 `forLotusWithStaticPower`를 기본 Lotus 경로로 올린다.
   설치 거부는 OEM 서명자 허용 목록으로 확인됐다([근거](research/GEN5W_INSTALL_ALLOWLIST.md)).
   진단 APK를 NEMESIS 패키지(`kind=apk`)로 만들어 승인 후 설치하고 BLE 탐색 결과로 A안을 판단한다.

## 코드와 근거의 위치

| 경로 | 역할 |
|---|---|
| `core/src/io/github/k8ambient/core/` | SML 파서, 임시 밝기 보정, 오프라인 동기화 |
| `tools/collect_k8_adb.py` | 정확한 차량/펌웨어 확인 후 기존 증거 읽기 |
| `tools/watch_k8_sml_adb.py` | 최대 5분 SML 선별 실시간 기록 |
| `tools/k8_ble_preflight.py` | 읽기 전용 BLE 선언·서비스 요약 |
| `tools/att_evidence.py` | 선별 ATT TSV 분석 |
| `ble-diagnostic/` | 설치 실패한 독립 BLE 연결·서비스 탐색 APK 소스 |
| `tools/build_k8_ble_diagnostic.ps1` | API19 APK의 로컬 빌드·서명·검증 |
| `docs/research/K8_SML_LIVE_CAPTURE.md` | 최초 K8 실측 근거 |
| `docs/research/LOTUS_HCI_CAPTURE.md` | 최초 Galaxy 실측 근거 |
| `docs/research/LOTUS_APK_5_21_03.md` | 앱의 제한적 정적 분석 |
| `docs/research/K8_OEM_SML_LOG.md` | OEM 로그 출력 경로·정적 분석 제한 |

## 이 노트북에만 있는 자료

아래 경로는 저장소 루트 기준이다. 별도 PC에서 clone하면 이 자료가 없으므로
원본이 필요한 작업은 로컬 자료 보유 여부부터 확인한다.

| 경로 | 내용 |
|---|---|
| `private/vehicle-usb/K8-AMBIENT-EVIDENCE-20261107-140107-1783/` | 기존 보고서·맵·HMG 로그 |
| `private/vehicle-adb/K8-AMBIENT-LIVE-20261107-164855/` | 최초 SML 39줄 |
| `private/vehicle-adb/K8-AMBIENT-PARKED-OFF-THEME/` | 추가 SML 22줄 |
| `private/captures/galaxy-bugreport-20261107-171247.zip` | RGB·밝기를 확인한 최초 보고서 |
| `private/captures/phone-current.btsnoop`, `phone-previous.btsnoop` | 최초 보고서 HCI 추출물 |
| `private/att-writes.tsv`, `private/att-evidence.json` | 최초 HCI 선별·분석 결과 |
| `private/captures/galaxy-parked-power-20261107-204650.zip` | 추가 USB 보고서, 50,966,231바이트 |
| `private/captures/galaxy-parked-power-20261107-204650/` | 추가 HCI 두 파일과 쓰기 0건 TSV |
| `private/ble-diagnostic/` | 설치 실패한 원본·legacy APK와 로컬 개발 서명 키 |
| `.tools/android19/`, `.tools/wireshark/` | 공식 API19 플랫폼 및 TShark 4.6.9 |

ADB 주소·휴대폰 serial·조명 MAC은 변동 가능하고 private로 취급한다.
USB 삽입 실행은 사용자 차량에서 실패해 기존 승인된 ADB 경로를 사용했다.
Galaxy 핫스팟 사용 중에는 무선 디버깅이 어려웠고 이후 USB ADB 연결에 성공했다.

## 검증과 승인 범위

마지막 전체 기본 검사는 Python 79개 중 78 통과·기존 권한 skip 1,
Java 코어173·host14·진단 모의37 통과다.
검사 명령은 `python tools/check.py`다. 로컬 JDK를 `JAVA_HOME`으로 지정해야 할 수 있다.
독립 진단 APK는 실제 공식 API19로 컴파일하고 DEX035, 서명과 정렬을 검증했다.
HMG 진단 계약 컴파일은 이번 변경에서 재실행하지 않았다.

사용자는 독립 진단 앱의 설치·실행과 현재 점검 진행을 승인했다.
차량 시스템/펌웨어 변경, 차량 CAN/LIN/Binder 제어, 다른 모듈 설치 등은 별도 승인 범위다.
외부 AI 자문은 사용자 지침상 MiniMax만 허용하며 실제 로그·비밀을 보내지 않는다.
원본 로그, APK, SDK 및 서명 키는 Git 제외 경로에 유지한다.
