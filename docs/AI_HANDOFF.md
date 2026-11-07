# 진단 준비 인계 — 2026-11-07

## 최신 작업

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
