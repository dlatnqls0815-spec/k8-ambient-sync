# 진행 상황

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
