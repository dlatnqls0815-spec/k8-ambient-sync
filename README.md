# K8 Ambient Sync

2021 기아 K8 하이브리드(GL3 HEV)의 Gen5W 순정 엠비언트 설정을 읽어
Lotus Lamp X 사제 조명과 동기화하는 프로젝트다.

**현재 실차 동기화 프로그램은 아니다.** 공개 소스 분석과 오프라인 코어를 먼저 검증한다.
순정 방송 경로는 HMG-API 소스에 있으나 K8 수신 가능성, RGB 테마 의미,
Gen5W BLE Central 지원, 실제 사제 컨트롤러 프로토콜은 아직 확인되지 않았다.

구현된 부분은 raw SML 파서, 모의 전용 E1 후보 코덱, 최신 상태 병합·중복 제거·
색상/밝기 공정 처리·재연결 코어, PC용 마지막 상태 파일, 기본 OFF인 진단 Hook 소스다.
실제 BLE transport, 차량 설치 패키지, 시동 자동실행은 아직 제공하지 않는다.

## 재개와 실행

`AGENTS.md` → [진행 상황](docs/PROGRESS.md) → [결정 기록](docs/DECISIONS.md)을 읽는다.
기본 검사는 Python 3.9+와 JDK 8이 필요하다. 외부 Python/Java 라이브러리는 사용하지 않는다.

```text
python tools/check.py
```

`JAVA_HOME` 또는 PATH의 JDK를 사용한다. 로컬 `.tools/jdk8/*/bin`도 탐색한다.
검사는 차량, Bluetooth, 인터넷에 접근하지 않는다.
현재 환경에서는 `python tools/check.py --diagnostic`로 실제 API19/HMG 소스 계약 컴파일도 가능하다.
기본 검사는 179개 assert 검증을 포함한다. 검증 범위와 의존성 경로는 [TESTING](docs/TESTING.md)을 참조한다.
실차 작업은 [진단 절차](docs/DEVICE_DIAGNOSTICS.md)를 확인한 뒤 별도로 승인받는다.

## 자료

- [구조와 A/B/C 비교](docs/ARCHITECTURE.md)
- [검증 방법](docs/TESTING.md)
- [미해결 사항](docs/KNOWN_ISSUES.md)
- [출처·라이선스](docs/research/)

기준 저장소: https://github.com/dlatnqls0815-spec/k8-ambient-sync (비공개).
현재 사용자의 선택에 따라 원격 변경 없이 로컬에서 작업한다.

새 코드의 라이선스는 AGPL version 3이며 [NOTICE](NOTICE.md)에 외부 자료의 출처를 기록했다.
