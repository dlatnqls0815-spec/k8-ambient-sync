# 진단 준비 인계 — 2026-11-07

## 다음 정차 방문 준비

기존 Galaxy 캡처를 재검토했다. RGB/5·10·15·20% 반복 수집은 생략한다. 대상 세션의
전원 프레임, 재연결 동작 및 2% 반응은 남아 있다. K8 로그는 켜진 밝기와 일부 색상을
확인했으며 OFF 원시 상태와 테마 의미는 미확인이다.
읽기 전용 BLE 상태 수집기와 지정된 조명 한 대의 GATT 탐색을 위한 독립 진단 앱을 준비했다.
진단 APK는 공식 API19 플랫폼으로 빌드했다. 로컬에서 v1/v2 서명과 정렬을 검증했다.
기본 검사 Python 78 pass/기존 skip 1, Java 코어173·host14·진단 모의37 통과.
[다음 정차 방문](NEXT_PARKED_VISIT.md)에 실행 순서와 결과별 판단 기준을 정리했다.

## LotusLamp X 실측과 오프라인 재생

Galaxy의 기존 무선 페어링으로 버그 보고서를 PC에 저장했다. 현재 BTSnoop의 ATT 쓰기
131개 중 RGB 4개와 밝기 120개가 같은 LE 세션의 FFF0/FFF3에서 관측됐다.
밝기 조작 네 묶음의 끝값은 5/10/15/20이다. 전원과 초기 쓰기의 의미는 미확인이다.
정확한 밝기 프레임 분류와 선택형 모의 재생을 추가했다. 11단계→20%, 3단계→5% 검증 통과.
Python 74 pass/기존 1 skip, Java 코어173·host14·진단 모의37 통과.
원본은 private에만 보관했다. 다음은 Gen5W에서 BLE 연결 및 GATT 탐색 가능 여부 확인이다.
자세한 근거는 [Lotus HCI 실측](research/LOTUS_HCI_CAPTURE.md)에 있다.

## 실차 SML 원시값 확보

무선 ADB 연결 성공 후 최초 캐시는 선별 SML 0줄이었고, 실시간 감시 5분에서 39줄을 얻었다.
RGB 요청 7, mode=5 밝기 요청 6, 상태 응답 26. data는 1,3,4,8,7,11이며
각 요청 직후 응답 7번째 바이트와 일치했다. 사용자가 1단계가 실제 점등된 최소라고 확인했다.
K8 전용 임시 밝기 정규화는 1→사제 약2%, 11→약20%로 모의 코어에만 연결된다.
전원 OFF raw·RGB 16비트 변환·Lotus 실제 패킷은 미확인이다.
원본은 private/vehicle-adb에만 보존. 자세한 제한은 docs/research/K8_SML_LIVE_CAPTURE.md.
휴대폰 HCI 조작 및 버그 보고서 생성은 사용자가 완료했다고 했지만 파일은 PC에 아직 없다.
검증: Python 72개 중 71 pass/1 기존 skip, Java 코어167·host14·진단 모의37 통과.
Android API19 진단 계약 컴파일은 이번에 실행하지 않았다.

## USB 실행 불가에 따른 ADB 수집 전환

사용자가 차량과 노트북을 같은 휴대폰 핫스팟에 연결했다. 현재 차량 IP에 ping/ARP 응답이
확인됐으나 기존 ADB 포트 5555는 닫혀 있고 `adb devices -l`은 0대다.
차량의 무선 ADB 앱/설정에서 실제 실행 상태와 포트를 확인하는 사용자 답변을 기다린다.
이 IP는 비공개·일시적이라 문서에 기록하지 않는다. 실차 자료 수집은 아직 시작하지 못했다.

사용자가 USB 삽입 시 실행이 안 되며 차량에 ADB를 설정했다고 알려왔다.
PC의 scrcpy 디렉터리에서 ADB 실행 파일을 찾았지만 현재 `adb devices -l`에 연결 기기 0대,
`adb mdns services`에도 광고 0건이다. 차량 연결이 생기기 전 실차 수집은 불가능하다.
새 `tools/collect_k8_adb.py`는 GL3HEV.KOR.*.260902/API19 게이트 뒤 기존 OEM 캐시를 먼저 읽고
USB 수집과 같은 선별 로그·기존 파일을 private에 저장한다. `docs/ADB_COLLECTION.md` 참고.
현재 수집기를 실행했을 때 차량 미연결로 안전하게 중단됐고 결과 폴더는 만들지 않았다.
새 ADB fixture 5개, Python 전체 68 pass/1 skip. USB 자동 실행을 다시 요구하지 않는다.
후속 사용자 요청으로 차량 Wi-Fi를 켜려 했으나 PC에 ADB 연결 0대라 원격 변경은 불가능하다.
과거 D: 기록은 일반 Wi-Fi 클라이언트 disabled, 무선 AA AP `wlan0` 실행, OEM Wi-Fi 설정
화면 시작 성공만 보여준다. legacy ADB TCP 속성은 1→0으로 껐고 대체 포트는 기록되지 않았다.
차량 화면에서 Wi-Fi를 켜더라도 ADB가 자동으로 살아난다고 보장할 수 없다.

## 사용자 밝기 보정 기준

사용자는 사제 앱 20%가 순정 최대 밝기와 비슷하다고 했다. 미연결 화면에는 19%가 표시된다.
모의 SyncEngine의 기본 최대를 20%로 두고 정규화 입력에 선형·반올림을 적용한다.
0%와 OFF는 별개이고 최대값은 조절 가능하다. 실제 K8 raw 정규화, 선형 광도와 Lotus 명령은 미확인이다.
HCI 밝기 기록은 5/10/15/20%로 하고 순정 최대:사제 20%, 중간:10%를 현장에서 비교한다.
JDK17의 Java7 대상 기본 오프라인 검사 통과(Python 63 pass/1 skip, Java 148/14/37 checks).

## 한 번의 차량 방문에 추가할 자료

사용자 요청으로 기존 OEM 로그 캐시 수집에 BLE 기능 선언과 지정된 HMG 프로세스의
읽기 전용 순간 관측 요약을 보강했다. 실제 BLE 연결·vehicle 서비스 작동 증거는 아니다.
기존 캐시를 먼저 수집하고, 캐시 소멸에 대비해 순정 설정을 수동으로 바꾼 직후
같은 USB 수집을 다시 실행하면 별도 결과 폴더로 보존할 수 있다. 출력 자체는 미확인이다.
Galaxy Lotus HCI는 별도의 휴대폰 수집이며 USB만으로 얻을 수 없다.
수집기 fixture 15개 통과, USB의 이전 파일을 백업하고 교체본 해시를 확인했다.

## 최신 작업

원시값 보존을 위해 기존 시스템 사본의 OEM 로그 경로를 조사했다.
automotive-service/automotive ODEX는 실제 car.json의 파일 MD5와 일치한다.
REA JADX provider 미설정으로 로컬 Android SDK dexdump와 DEX 메타데이터를 사용했다.
파생 DEX의 최적화 class flags/quick opcode 제한 때문에 전체 제어 흐름 복원은 미확인이다.
SystemService 태그의 SYS_SML_STATUS_M_RESP, setSMLRGB, setSMLConfiguration 출력 위치를 확인했다.
근거·offset·제한은 docs/research/K8_OEM_SML_LOG.md에 있다. 바이너리와 전체 출력은 private다.

USB 수집기에 logcat -b system -d -v brief -s SystemService:D '*:S'와 builtin read/case 필터를 추가했다.
기존 캐시의 세 문구만 logs/oem-sml.log에 보존하고 종료 코드와 줄 수를 별도 기록한다.
기존 D드라이브 collector/안내를 NEMESIS-backup/before-oem-sml-log-*에 백업하고 검증본으로 교체했다.
실제 raw 값은 다음 실행 전까지 미확인이다. 이미 한 색상·밝기 조작을 반복하는 안내는 하지 않는다.
현재 Python61개 중60개 통과/기존 symlink1개 skip이며 collector12개 fixture를 포함한다.
Java는 변경이 없어 기존190개와 API19 계약 검증을 재사용한다.

사용자가 이전에 RGB 색상과 밝기를 직접 변경하는 조작을 이미 했다고 재확인했다.
수동 변경은 완료된 조작으로 기록한다. 재수행을 기본 다음 단계로 안내하지 않는다.
확보 로그를 다시 검색했지만 raw RGB/brightness/SML hex 값은 없었다.
현재 수집은 기존 HMG 로그 복사이며 LightRelay는 원시값을 로그에 쓰지 않는다.
다음 검토 대상은 관측값을 실제로 보존할 경로다. 기존 차량 변경/설치/송신 제한을 유지한다.

USB 재실행으로7개 파일 복사에 성공했고 collection.txt 포함8개를 private에 해시 대조해 보관했다.
기준 입력은 private/vehicle-usb/K8-AMBIENT-EVIDENCE-20261107-140107-1783다.
실제 JSON을 car_report_evidence.py에 적용했다. 원본과 로그를 외부 전송/커밋하지 않는다.

현재 map은 unknown/gl3hev/kor/260902, probed, hooks_on=true다.
342/342 resolve와 map-check의 folder/files 일치를 확인했다.
미매핑은 themesettings.split_item.type이다. SML3개와 relay loader/class_loader/intercept_key는 맵에 있다.
세 SML 서명은 두 보고서의 후보와 제공된 framework 맵에서 일치한다.

로그에는 LightRelay 등록14개와 vehicle 전달의 Connection refused14개가 있다.
고정1.0.2 소스에서 onReceive의 SML_STATUS byte[]가 null이 아닐 때 send가 실행되므로,
이 실패는 기록상 배열 수신 후 전달을 시도한 흔적이다. 전체 이벤트 횟수·raw 값·길이·현재 수명은 미확인이다.
vehicle.log는 없고 보고서에는 활성 nemesis-shortcut 하나만 있다. on-demand 서비스 수명과
일치할 가능성이 있지만 services.txt/현재 프로세스를 읽어 확인하지 않았다.
자세한 판단은 K8_SML_MAP_CHECK와 private/k8-sml-evidence-review.json을 읽는다.
이번 문서 변경은 기존 Python57개+Java190개 통과 결과를 재사용했다.

## 이전 작업 기록

2277aa3 이후 사용자에게 K8 화면의342/342 resolve, probed GL3HEV/kor260902,
미매핑1개·Map checked를 전달받았다. HMG-API1.0.2/NEMESIS1.16.4다.
기존 JSON은 로컬 private/USB에서 발견하지 못했다.
`docs/research/K8_SML_MAP_CHECK.md`와 CAPTURE_SESSION의 현재 차량 수집 단계부터 읽는다.

사용자 추가 지시로 D드라이브 USB의 NEMESIS.data를 백업하고 기존 파일 수집용으로 교체했다.
수집 스크립트·안내와 descriptor의 해시를 확인했다. 원래 descriptor는
NEMESIS-backup/before-k8-ambient-readonly-*에 있다. 결과는 USB의 K8-AMBIENT-EVIDENCE-*다.
차량에서 기존 보고서/맵/map-check/설치 기록/HMG와 vehicle 로그를 USB에 복사한다.

새 car_report_evidence.py는 로컬 Car Report와 선택 framework 사본을 대조한다.
맵의 eligibility/과거 resolve/탐색 후보/실제 수신을 구분한다.
기존330개 오프라인 후보의 missing은 themesettings.split_item.type이지만 현재342개와의 동일성은 미확인이다.
현재 missing의 실제 이름과 SML/relay에 미치는 영향은 기존 JSON 확보 후 판단한다.
세 SML 메서드는 설정/요청용이며 이벤트는 LightRelay의 방송 경로를 조사한다.

첫 USB 실행에서 기존 보고서2개를 찾았지만 SIZE_UNAVAILABLE로0개 복사했다.
wc 실패 회귀검사를 실패로 재현한 뒤 의존성을 제거하고 제조사 폴더를 탐색하도록 수정했다.
첫 collection.txt는 private/vehicle-usb에 보존했다. 수정본을 USB에 반영하고 해시를 확인했다.
현재 Car Report 원본 확보는 재실행 결과 대기다.

Python55개 중54개 통과/기존 symlink1개 skip, Java139+14+37=190개 통과.
수정된 collector9개 fixture와 구문 검사 통과. 최종 Python58개 중57개 통과/기존 symlink1개 skip이다.
API19 계약은 production Java 변경이 없어 재사용했다.
차량 변경·설치·실제 BLE/조명 명령·map 검사/생성/적용/재시작은 수행하지 않았다.
사용자가 USB를 노트북으로 가져오면 원본을 private에 보관해 미매핑 ID와 기존 로그부터 확인한다.

## 이전 진단 준비

작업 브랜치 `codex/device-evidence`, 시작 커밋 `ec12e13`.
`AGENTS.md`, `PROGRESS.md`, `CAPTURE_SESSION.md`, `EVIDENCE_TOOLS.md`부터 읽는다.
사용자는 이번 엠비언트 진단을 엔진사운드 앱보다 먼저 진행하도록 정정했다.

현재는 Galaxy HCI와 K8의 기존 펌웨어/Hook 상태를 수집할 준비 단계다.
새 `tools/k8_probe.py`는 승인된 ADB에서 고정 읽기 명령만 사용하고 private에 요약한다.
map-check를 만들거나 Hook/모듈을 실행하지 않는다. 과거 결과로 현재 Hook 성공을 판정하지 않는다.
HCI는 기존 TSV 분석 도구를 재사용한다. 평문 E1 RGB 후보는 구분하지만 설치본의
기본 길이 밝기/E2/암호화는 unknown으로 유지한다. `docs/templates/capture-actions.csv`는 빈 수동 기록표다.

새 도구/지원 범위 검사 포함 Python 41개 중40개 통과/1개 기존 symlink 권한 skip.
메타데이터와 diff 공백 검사 통과. Java179개/API19 검사는 Java/Hook 변경이 없어 재사용했다.
실측 캡처·TShark가 없어서 원본 HCI→TSV 전체 경로를 검증하지 않았다.
차량·조명 접속·BLE 송신·스캔·시스템 변경은 하지 않았다.
외부 자문은 허용된 MiniMax skill이 이 PC에 없어 실행하지 않았다.

다음에는 사용자가 정차한 차량/조명을 사용할 수 있을 때 CAPTURE_SESSION의 화면2종과
RGB/밝기6회·연결 전 HCI를 수집한다. 원본은 private/captures에 보관하고 외부 전송하지 않는다.
기존 승인된 K8 ADB가 없으면 화면 기록으로 대체하며 레거시 TCP ADB를 켜지 않는다.
실제 쓰기 transport, vehicle 상태 요청, 설치·Hooks 활성화는 여전히 별도 단계다.
기존 브랜치에만 일반 Push하고 main에는 병합하지 않는다.
