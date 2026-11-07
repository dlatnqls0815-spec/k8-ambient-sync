# 다음 정차 시험: 필요한 자료만 수집

기준 브랜치 `codex/device-evidence`, 시작 커밋 `ec12e13`.
후속 작업 기준은2277aa3이며 현재 차량 수집은 아래의 **기존 자료 USB 복사** 단계를 우선한다.
현재 목표는 **순정 K8 상태를 읽는 경로와 기존 Lotus 앱의 실제 통신 증거**다.
엔진사운드 APK 작업과 별개이며 이번 도구는 조명에 접속하거나 송신하지 않는다.
차량에 접근할 수 없는 동안에는 아래 절차 준비까지만 완료한 것으로 기록한다.

## 현재 차량 수집 단계

수정본의 재실행 자료를 확보했다. 기존 보고서2개 등7개 파일이 복사됐고 원본은 private에 보관했다.
미매핑 ID와 SML 매핑은 대조를 마쳤으며 추가 수집보다 기존 로그의 해석을 우선한다.
원시 RGB·밝기와 현재 서비스 상태는 남은 확인 항목이다.
이번 자료의 판단은 [K8 SML 분석](research/K8_SML_MAP_CHECK.md)에 기록했다.

사용자가 전달한 화면에는 GL3HEV/kor260902의342/342 resolve, probed, 미매핑1개와
Map checked가 표시됐다. 원본 JSON과 실제 SML 수신 여부는 대조할 자료가 필요하다.
이번 차량 작업은 기존 파일 복사 범위다. 아래 Galaxy 색상/밝기 조작 계획은 후속 단계로 보존한다.

사용자 요청에 따라 D드라이브 USB에 수집용 `NEMESIS.data`, `CODEX-collect-k8-ambient.sh`,
`K8-AMBIENT-README.txt`를 준비했다. 이전 descriptor는 USB의
`NEMESIS-backup/before-k8-ambient-readonly-*` 아래에 백업했다.

1. 정차·P 상태에서 정상 내비게이션 화면이 나온 뒤 준비한 USB를 꽂는다.
2. NEMESIS의 USB 실행 확인에 예를 누르고 실행 완료 안내를 기다린다.
3. USB를 노트북으로 가져와 `K8-AMBIENT-EVIDENCE-*`의 collection.txt와 복사된 JSON을 확인한다.

기존 Car Report와 선택 맵·과거 map-check·HMG 로그·vehicle 로그를 읽어 USB에 저장한다.
미확보 항목과 복사 실패는 collection.txt에 남는다.
첫 실행 기록은 확보했지만 크기 확인 실패로0개가 복사됐다. 차량 내부에는 기존 보고서2개가 있었다.
wc 의존성을 제거한 수정본을 USB에 반영했다. 재실행 후 새 결과 폴더를 확인한다.
GL3HEV 맵은 제조사 폴더를 탐색해 기존 하위 경로를 보존한다.
자세한 대상과 실행 형식은 [USB 안내](../tools/usb/README.md)에 있다.
Hook Map 조작, Check the map, Map this car, Apply changes, Restart unit은 이번 수집에서 금지된다.
새 모듈·Hook·서비스 설치와 BLE/차량 조명 명령도 현재 수집 범위에서 금지된다.

기존 ADB로 복사하려면 PC에서 다음 명령을 사용한다.
대상 ID는 이미 허용된 차량 연결을 로컬에서 선택한다.

```powershell
New-Item -ItemType Directory -Force private/car-report | Out-Null
adb -s "<기존 K8 연결 ID>" pull /data/hmgapi/public/reports private/car-report
adb -s "<기존 K8 연결 ID>" pull /data/hmgapi/maps/kia/gl3hev/kor/260902/framework.json private/framework.json
```

읽기 권한이 없으면 그 결과를 미확인으로 남긴다.
보고서 생성 명령을 새로 실행하는 대신 기존 JSON의 위치와 화면의 미매핑 지점 이름을 기록한다.

PC의 오프라인 분석 명령은 다음과 같다.

```text
python tools/car_report_evidence.py "<복사된 기존 Car Report.json>" --framework-map "<복사된 framework.json>"
```

framework 파일을 확보하지 못했으면 해당 옵션을 생략한다. 요약은 private/car-report-evidence.json에 저장한다.
분석 결과와 수신 경로 설계는 [K8 SML 분석](research/K8_SML_MAP_CHECK.md)에 있다.

## PC에서 먼저 준비

기존 명령과 제한은 [EVIDENCE_TOOLS](EVIDENCE_TOOLS.md)에 있다.
현재 준비된 도구는 다음과 같다. 실제 serial/IP는 로컬 터미널에서만 지정한다.

| 명령 | 하는 일 | 하지 않는 일 |
|---|---|---|
| `python tools/phone_probe.py --adb <기존-adb>` | 승인된 Galaxy의 OS·Lotus 버전·BLE 선언 읽기 | 앱 설치·실행, 캡처·BLE 조작 |
| `python tools/att_evidence.py private/att-writes.tsv` | 이미 추출한 ATT TSV의 후보 형식·세션·상대 시간 정리 | 원본 HCI 재조립, UUID 추정, 암호 해독 |
| `python tools/k8_probe.py --adb <기존-adb>` | 한국 K8/API19 확인 후 펌웨어·HMG 앱 버전·기존 map-check 요약 읽기 | root·서비스 시작·Hook 검증 실행·차량 쓰기 |

여러 ADB 기기가 있으면 대상 선택을 위해 `--serial`을 추가한다. 자동 IP 탐색·페어링·연결은 없다.
K8에 **이미 허용된** ADB 세션이 없으면 K8 도구는 실행하지 않고 아래 화면 기록으로 대체한다.
레거시 TCP ADB를 다시 켜지 않는다. USB 메모리를 PC에 꽂는 것만으로 차량 ADB가 연결되지는 않는다.
이번 PC에서는 TShark가 발견되지 않아 실측 HCI→TSV 전체 경로는 아직 검사하지 못했다.

## 1. 차량에서는 먼저 화면 두 종류만 기록

1. 정차·P 상태에서 설정의 소프트웨어/펌웨어 정보 화면을 기록한다. VIN·계정·목적지는 가린다.
2. 기존 HMG-API 상태 화면이 있다면 버전, 선택된 맵, Hook 성공/실패 요약만 기록한다.
   설치된 버전, Hook 활성화 요청, 맵 resolve 결과, 실행 중 Hook, 이벤트 수신은 각각 별개다.
   해당 항목을 보여주는 기존 화면/로그가 없으면 미확인으로 둔다.

기존 ADB 연결이 있을 때만 PC에서 `k8_probe.py`를 실행한다. 결과는 `private/k8-probe.json`이다.
수집 대상은 네 개의 getprop, BLE feature 선언, `hmgapi.app`의 versionName,
`/data/hmgapi/hook/enable`의 읽기 가능 여부와 **이미 있는**
`/data/hmgapi/state/map-check.json`이다. 원본 package dump·JSON은 출력 파일에 남기지 않는다.

권한 부족·파일 부재는 구분할 수 없으므로 `unknown`/`unavailable`로 남긴다.
맵 파일의 성공 결과는 과거 결과이며 `same_product=true`여도 파일 일치·최신성·실행 성공을 보장하지 않는다.
ambient 관련 실패 지점 이름만 선별하며, 실패가 없다는 이유로 해당 지점이 검사 목록에 있다고 추정하지 않는다.
이 도구로 **현재 runtime Hook 또는 SML 이벤트 수신을 확인할 수 없다**.

`engine verify-map`, `engine report`, `engine map-car`, `publish-state`는 실행하지 않는다.
이름이 진단처럼 보여도 파일을 생성하거나 실행 조건을 바꿀 수 있다. 모듈 설치·수신기 등록,
방송·상태 요청·Binder/CAN/LIN 호출, `setprop`, 재부팅·서비스 재시작도 이번 수집에 포함하지 않는다.

수집 경로/필드 근거는 조사한 HMG-API 1.0.2의
[MapCheck](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/engine/MapCheck.java#L80-L88)와
[설치 마커](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/install/common/install.sh#L155-L157)다.

## 2. Galaxy에서 짧은 HCI 수집

차량/조명을 실제 사용할 수 있을 때 본인이 기존 LotusLamp X로 수동 조작한다.
에이전트의 직접 BLE scan/connect/write는 계속 비활성 상태다.

1. 원래 색상·밝기를 기록하고 음악/자동 장면을 끈다. HCI snoop의 원래 설정도 기록한다.
2. Galaxy 개발자 옵션에서 Bluetooth HCI snoop를 켜고 휴대폰 Bluetooth만 다시 켠다.
   연결 전부터 기록해야 discovery/초기화와 연결 세션을 구분할 수 있다.
3. 기존 Lotus 앱으로 본인의 조명에 정상 연결하고 5초 기다린다.
4. **빨강 → 5초 → 초록 → 5초 → 파랑 → 5초 → 같은 색상에서 밝기 낮음 → 중간 → 높음**.
   밝기 사이에도 5초씩 기다린다. 숫자가 표시되면 25/50/75를 쓰고 실제 표시값을 적는다.
   숫자가 없으면 슬라이더 위치만 적고 퍼센트로 확정하지 않는다.
5. 각 조작 시각과 실제 조명 반응을 적은 뒤 원래 색상·밝기로 되돌린다.
6. 휴대폰의 버그 보고서 또는 이미 승인된 ADB의 `adb -s <휴대폰> bugreport private/galaxy-capture.zip`으로
   PC의 private에 보관하고 HCI snoop를 원래 설정으로 돌린다.

PC의 `private/`는 먼저 만든다. [기록표](templates/capture-actions.csv)를 private에 복사해 작성한다.
`local_time_note`는 다른 기기 기록과 맞추는 비공개 메모다. `capture_relative_seconds`는 실제
캡처 시작과 대조한 뒤 채운다. 스톱워치의 0초를 HCI `frame.time_relative`의 0초로 가정하지 않는다.
OFF/ON·재연결 후 같은 동작 재현은 첫 자료의 완전성을 확인한 다음 두 번째 짧은 시험으로 남긴다.
키·인증 자료·전체 bugreport를 채팅이나 Git에 올리지 않는다.

HCI 활성화/재시작과 bugreport의 btsnooz 추출 근거는
[AOSP 공식 문서](https://source.android.com/docs/core/connect/bluetooth/verifying_debugging#debugging-with-logs)에 있다.
Samsung 보고서에 full snoop가 없거나 payload가 필터링되면 수집 실패/부분 자료로 남긴다.
root·권한 변경으로 경로를 강제로 열지 않는다.

## 3. RGB와 밝기를 어느 수준까지 구분할 수 있나

| 확보 자료 | 현재 도구의 판단 | 추가 확인 |
|---|---|---|
| 정확한 평문 E1 RGB 후보 | R/G/B 값을 정리하여 세 단색을 구분 | 조작 시각·발광 반응·동일 handle/세션과 대조 |
| 공개 후보 밝기 9바이트 형식 | 해당 후보와 정확히 일치할 때만 percent 후보 | 설치본의 실제 단위/선택 분기는 별도 |
| 설치본 E1 기본 길이 `04` 밝기 | `unknown_count`에 포함 | 원본을 로컬 Wireshark에서 시각표와 비교 |
| E2·암호화·알 수 없는 형식 | 의미/단위는 미확인, raw는 결과에 복사하지 않음 | 밝기 조작과 시간상 동반되더라도 복호화/프로토콜 확정으로 해석 금지 |
| UUID discovery 또는 연결 시작 누락 | UUID/연결 세션 미확인 | 다음 수집을 연결 전부터 시작 |

설치본 E1의 기본 밝기 길이 `04`와 공개 후보의 `07`은 다르다. `att_evidence.py`에서
`unknown_count > 0`은 반드시 누락이나 실패를 뜻하지 않는다. 실제 앱의 brightness 경로가
어느 쪽인지 확인하기 전 기존 후보 decoder를 넓히거나 transport에 연결하지 않는다.
합성 RGB 3개·기본 길이 밝기 3개·E2 1개 검사는 이 제한을 확인할 뿐 실측 검증이 아니다.

TShark는 기존 [선별 export 명령](EVIDENCE_TOOLS.md#3-tshark-선별-필드--후보-분석)을 사용한다.
설치된 버전에 필요한 필드가 있는지 먼저 `tshark -G fields`를 로컬에서 확인한다.
`bthci_acl.chandle`/`connect_in`은 [공식 필드 문서](https://www.wireshark.org/docs/dfref/b/bthci_acl.html)에 있다.
출력에서 방향/연결 세션 필드가 비었다고 값을 추측해 채우지 않는다.
TSV는 발견(discovery)·초기화·인증/notify 전체를 담지 않으므로 원본 캡처는 private에 보존한다.

## 다음 판단에 필요한 최소 결과

- 펌웨어 화면과 기존 HMG Hook 요약; ADB가 이미 있으면 `private/k8-probe.json`.
- 연결 전부터 수집한 Galaxy HCI/bugreport와 6회 조작·실제 반응 기록표.
- 로컬 ATT 요약과 알 수 없는 형식의 존재 여부. 원본을 자동 업로드하지 않는다.

이 자료를 비교해 컨트롤러 분기와 K8 읽기 가능성을 정한다. 실제 송신·차량 변경은 계속 별도 단계다.
