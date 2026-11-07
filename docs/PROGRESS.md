# 진행 상황

기준일: 2026-11-07 (Asia/Seoul). 현재 단계는 **오프라인 기반 구현 완료 / 실차 통합 대기**다.
실제 차량에서 동작하는 완성품 또는 배포용 모듈을 만든 상태는 아니다.

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

## 진행 중
로컬 구현·검사는 종료했다. 실행 중인 차량 작업이나 백그라운드 개발 작업은 없다.
실제 transport와 raw→K8 정규화 통합은 아래 증거가 확보될 때 진행한다.

## 미확인
1. K8 펌웨어·Hook resolve/수신 상태. 상류에는 K8 맵이 없고 US EV6 기록만 있다.
2. 순정 테마의 실제 RGB, dim/drive-mode/자동 밝기, 마지막 저장값·현재 출력의 구분.
3. Gen5W 칩셋·BLE Central/GATT API/권한·통화/미디어/Android Auto 병행.
4. 실제 Galaxy APK 버전/서명과 사제 컨트롤러 장치군·UUID·초기화/인증·명령·keepalive.
5. 시동/QuickBoot 자동실행, system_server 설정/OFF 전달, 차량용 영구 저장과 실제 재연결 수명.
6. 실제 광색/밝기 대응, 안전한 전송 간격, 종단 200ms 성능. 50ms는 모의 간격일 뿐이다.

## 테스트
- `python tools/check.py --diagnostic` **성공**.
- 코어 139 + host 복원 14 + 진단 fake 26 = **179개 assert 검사 통과**.
- Java7 코어·진단을 실제 API19 bootclasspath와 HMG-API 1.0.2 전체 runtime 소스에 대조해 컴파일했다.
  프로젝트 코드 경고 없음. 상류 runtime의 기존 unchecked/serial 경고 8개는 남아 있다.
- 독립 검토가 연속 RGB 변경의 밝기 starvation을 재현했다. 실패하는 회귀검사 확인 후 공정 처리로 수정했다.
  전송 중 최신 이벤트를 이미 보냈다고 오인하지 않도록 상태 snapshot도 검사한다.
- Git diff 공백 검사 통과. 원본 조사·도구·캡처·로그·빌드 디렉터리는 추적하지 않는다.
- **실행하지 않음:** 차량/휴대폰 접속, BLE scan/connect/write, 상태 요청/Binder/CAN/LIN 명령,
  시스템·펌웨어 수정, DEX 패키징/서명/설치, 실차 성능 시험.

## GitHub
- 기준 원격: `https://github.com/dlatnqls0815-spec/k8-ambient-sync.git`.
- main 초기 기준: `4348d3a` — 프로젝트 규칙·필수 문서.
- 작업 브랜치: `codex/bootstrap-offline-sync`.
- 조사 커밋: `3f529d9` — 소스 근거·진단 절차·라이선스.
- 구현 커밋: `9a3b8b2` — 코어·host·진단 소스·검사 도구와 테스트.
- 이 진행 기록과 README의 후속 커밋은 `git log -1`로 확인한다.
- 커밋 작성자는 Codex다. 전역 Git 설정/로그인/provider/MCP를 변경하지 않았다.
- GitHub 로그인 확인 후 초기 main과 작업 브랜치의 원격 동기화를 재개했다.
  강제 Push/PR/merge는 하지 않는다. GitHub 업로드와 Codex Cloud 환경 연결은 별도이며 후자는 미설정이다.
- 로그인 후 변경은 문서뿐이다. 기존 179개 검사/API19 컴파일 결과를 유지하고 동일 검사는 반복하지 않는다.
  업로드 전 전체 커밋에서 민감정보 패턴·바이너리·제외 디렉터리 추적을 재점검했다.
- `dist/k8-ambient-sync-local.bundle`은 PC 이동용 export다. GitHub를 대신하는 기준 저장소가 아니다.
  최종 문서 커밋 후 생성하고 `git bundle verify`로 확인한다. 복제 방법은 README에 있다.

## 다음 작업
1. 정차 상태에서 펌웨어/소프트웨어 버전, 현재 HMG Hook 요약, 백업/복구 경로를 확보한다.
   K8에서 passive 수신 소스를 설치할 수 있는지 판단할 최소 자료다.
2. Galaxy 기존 공식 앱으로 정상 조작한 HCI를 로컬 비공개 폴더에 수집한다.
   원본을 외부 AI/저장소에 보내지 않고 필요한 조명 제어 동작만 익명 요약한다.
3. 기존 승인된 Gen5W 진단 환경에서 BLE 노출을 읽기 전용으로 확인한다.
4. K8 상태 매핑·대상 프로토콜을 확정한다. 설치물/롤백을 준비한 뒤 사용자 확인을 받아 정차 시험한다.
   확인 전에는 실제 송신을 추가하지 않는다.
5. A안 직접 BLE가 우선이며 실제 제약이 확인될 때 B안 Galaxy 중계, 그 뒤 C안을 검토한다.
6. 음악/속도/순환 확장은 기본 실차 동기화가 안정화된 이후로 남긴다.
