# 진단 준비 인계 — 2026-11-07

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
