# K8 Ambient Sync

## Codex·Claude 공용 작업 순서
1. **[공용 인수인계](docs/HANDOFF.md)** → 이 파일 → `docs/PROGRESS.md`(위쪽이 최신) → `docs/DECISIONS.md` 순으로 읽는다.
2. 이 저장소에서 `git status --short --branch`와 remote를 확인한다. 공용 브랜치는 `codex/device-evidence`다.
   현재 브랜치가 공용 브랜치임을 확인한 뒤 변경 전에 `git fetch origin`, `git pull --ff-only origin codex/device-evidence`를 실행하고 최근 커밋을 확인한다.
   다른 세션이 같은 체크아웃에서 편집 중이면 넘겨받은 뒤 작업한다. 기존 변경을 덮어쓰거나 임의로 stash하지 않는다.
3. `docs/research/`의 SHA 고정 근거를 활용하고 재조사는 변경 또는 부족한 근거에 한한다.
4. 세션을 넘길 때 `docs/PROGRESS.md` 위에 변경·검증·미확인·다음 작업을 기록한다. 설치 버전이나 다음 확인 항목이 바뀌면
   `docs/HANDOFF.md`도 갱신하고, 세션별 상세 인계는 `docs/AI_HANDOFF.md`에 기록한다.
5. 공유할 소스와 문서만 커밋하고 공용 브랜치에 일반 Push한다. Push가 거절되면 상대 변경부터 확인한다.

## 목표와 우선순위
2021 K8 GL3 HEV Gen5W에서 **순정 설정을 읽어** 기존 Lotus Lamp X 사제 조명을 동기화한다.
Gen5W 직접 BLE(A) → Galaxy 중계(B) → 별도 장치(C) 순서로 검토한다.
B안은 실차에서 동작이 확인됐다. 최신 빌드의 설치 여부와 실차 확인 범위는 `docs/HANDOFF.md`를 따른다.

## 변경과 검증
- main은 안정 기준, 기능 작업은 `codex/` 브랜치. 기능 단위로 커밋한다.
- 2026-11-07 사용자가 GitHub 로그인 완료를 알려 원래 지시서의 Push 작업을 재개했다.
  비공개 기준 저장소에 작업 브랜치를 Push할 수 있다. main 병합·강제 Push는 별도 요청 없이 하지 않는다.
- Java 코어는 Java 7/API 19에서 제공되는 API만 사용한다. host 도구는 별도 디렉터리에 둔다.
- 비자명한 로직에는 작은 자동 검증을 붙인다. `python tools/check.py`가 오프라인 기본 검사다.
- 알 수 없는 RGB 테마/밝기/패킷은 오류 또는 미확인으로 남기고 임의 변환하지 않는다.
- 후보 프로토콜 코드는 모의 전용이다. UUID/명령을 추정해 실제 장치에 보내지 않는다.

## 차량과 개인정보
- 차량 설치, 펌웨어/시스템 파일 변경, CAN/LIN/Binder 명령 송신은 별도 사용자 확인 전 금지.
- 실차 진단은 정차 상태에서만. 펌웨어/백업/복구/Hook 호환성/롤백을 먼저 확보한다.
- HMG-API와 HMG-Seogaemo를 혼합 설치하지 않는다. 조사한 외부 스크립트를 실행하지 않는다.
- VIN, 실제 장치 식별자, 인증/페어링 키, 원본 로그·펌웨어·바이너리는 커밋하지 않는다.
- `.research/`, `.tools/`, `captures/`, `private/`, `work/`는 로컬 전용이다.
- 외부 자문은 MiniMax만 허용. 필요 시 사용자 홈의 `.codex/skills/minimax/SKILL.md`를 읽는다.
  비밀/실제 로그/저장소 전체를 외부 AI에 보내지 않는다. Codex가 편집·검증·판단한다.
- Codex 로그인, 모델, provider, MCP와 다른 프로젝트 설정을 변경하지 않는다.
