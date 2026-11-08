# 테스트

`python tools/check.py`로 Python 자료 도구 검사, 코어 컴파일, assert 기반 동작 검사,
문서/메타데이터 검사를 실행한다.
JDK 8과 Python 3.9+ 외 의존성은 없다. 자세한 검사 결과는 PROGRESS에 기록한다.

2026-11-08부터 `check.py`는 BLE 점검 DEX의 순수 로직(`tests/bleprobe/ProbeLogicTestMain.java`)도 실행하고,
`.tools/android19/android-4.4.2/android.jar`(또는 `--android-jar`)가 있으면 `ble-probe-dex/src`를 API 19로 컴파일한다.
DEX 변환은 `tools/build_k8_ble_probe_dex.py`가 따로 한다(D8 필요).

2026-11-07 검증: 코어 **139**, host **14**, 진단 fake **26** = **179개 검사 통과**.
코어는 golden 패킷·필드 손상·테마/unknown 밝기 거부·OFF·중복·최신값·부분 실패·재연결·
재시도 상한·재진입 이벤트·연속 색상/밝기 변경을 검사한다.
host는 파일 복원·원자 덮어쓰기·손상/과대 입력 거부·복원 후 disabled 유지를 검사한다.
진단은 실제 수신기 코드를 fake Context/Handler 큐에서 실행해 수명·검증·최신 로그 병합을 검사한다.

## Android 계약 컴파일

```text
python tools/check.py --diagnostic
```

현재 PC의 로컬 입력은 `.research/HMG-API`의 고정 커밋
`180185de2c57afb2cca4858d0be7828e8f1051d6`과
`.tools/android19/android-4.4.2/android.jar`다. 다른 경로는 `--hmg-source`, `--android-jar`로 지정한다.
의존성을 자동 다운로드하거나 상류 빌드 스크립트를 실행하지 않는다.
실제 Android API19를 bootclasspath로 사용해 코어/진단이 PC 전용 API를 참조하지 않는지 확인한다.
HMG runtime/stubs도 실제 소스에서 컴파일하며 테스트 fake를 해당 classpath에 넣지 않는다.
상류 runtime 컴파일에는 기존 unchecked cast/serial 경고 8개가 있다. 프로젝트 코어/진단은 경고 없이 컴파일됐다.
DEX 변환·패키징·서명·차량 설치는 하지 않는다.

JDK는 공식 Temurin 8.0.512+9 ZIP을 SHA-256 검증 후 `.tools/`에만 풀었다.
Android API19 archive는 Google 공식 repository 메타데이터의 SHA-1과 대조했다.
전역 PATH/JAVA_HOME/로그인 설정은 변경하지 않았다. 이 파일들은 저장소에 포함하지 않는다.
다른 PC에는 JDK8을 준비하고 필요한 경우 공식 Android SDK의 API19 플랫폼을 별도로 준비한다.

검사 출력은 이전 클래스가 성공을 가리지 않도록 매번 새 `build/check-*` 디렉터리에 만든다.
`build/`는 Git에서 제외한다.

실제 차량·Bluetooth 연결, APK/모듈 설치, CAN/LIN/Binder 요청은 실행하지 않는다.
가상 이벤트·후보 패킷·모의 ACK·가상 시계만 사용한다.
모의 연결 성공은 BLE 지원, 물리 조명 반영 또는 200ms 성능을 증명하지 않는다.

독립 코드 검토에서 연속 RGB 변경이 밝기 전송을 영구 지연시키는 문제를 재현했다.
실패하는 회귀검사를 먼저 확인한 뒤 공정 처리로 수정했고 전체 검사로 확인했다.
연결 세션에서 바뀐 최신 상태를 이미 보냈다고 오인하지 않도록 전송 시점 상태 snapshot도 검사한다.

실차 검증 순서는 펌웨어/복구/백업 확인 → Hook 읽기 → Galaxy 기존 앱 통신 확인 →
Gen5W 수동 기능 조회 → 대상 확인 후 별도 승인된 BLE 시험이다.

## 기기 자료 도구 검사

```text
python -m unittest discover -s tests/python -p "test_*.py"
```

Galaxy 도구는 모의 ADB로 대상 혼동/미승인 연결/비차량 제한, 버전·BLE 선언 파싱,
식별자·오류 원문 비출력, 출력 경계와 기존 파일 보존을 검사한다.
ATT 도구는 합성 TSV로 정확한 후보 형식·입력 범위·다중 occurrence 거부,
재연결 handle 재사용·미확인 세션·unknown payload 비출력을 검사한다.
실제 Windows 디렉터리 심볼릭 링크 생성 검사는 권한이 없으면 skip으로 명시한다.
테스트 자체는 휴대폰/네트워크/실제 로그에 접근하지 않는다.

별도의 수동 검증으로 Galaxy 무선 연결 후 고정 패키지의 실제 OS/버전 정보를 읽었다.
이 성공은 실차 컨트롤러의 프로토콜·연결·실제 조명 반영을 증명하지 않는다.
현재 실제 HCI export가 없어 TShark→실측 TSV 전체 경로는 아직 검증하지 않았다.

## 후속 진단 준비 검사 — 2026-11-07

`python -m unittest discover -s tests/python -p "test_*.py" -v`:
41개 중 40개 통과, 기존 Windows 디렉터리 symlink 권한 검사 1개 skip.
새 K8 모의 ADB 검사는 대상 기기 제한, 고정 읽기 명령, 비공개 정보 비출력,
권한 부족의 unknown 처리, 과거 map-check와 runtime Hook 구분, 잘못된 요약 거부를 확인했다.
RGB 3색과 Lotus 기본 길이 밝기/E2 합성 입력으로 현재 decoder의 지원 범위를 명시했다.
passive 메타데이터·Git diff 공백 검사도 통과했다.

Java와 Android Hook 코드는 변경하지 않아 기존 179개/실제 API19 계약 컴파일 결과를 재사용했다.
TShark가 이 PC에 없고 실측 캡처가 없어 실제 파이프라인·기기 동작은 검사하지 않았다.

## Map checked 대조와 USB 수집 검사 — 2026-11-07

`python tools/check.py` 통과했다.
Python55개 중54개 통과, 기존 Windows symlink 권한1개 skip이다.
Java 코어139개, host14개, 진단 fake37개가 통과해 총190개다.
새 Car Report 합성8개는 probed/checked와 runtime 구분, 후보와 제공된 맵의 구분,
relay 의존 지점 누락, private 요약·입력 보존과 잘못된 입력 거부를 검사한다.
추가 진단 fake는 RGB3색과 밝기·dim·drive-mode의 unsigned raw 보존을 검사한다.

첫 실제 USB 실행은 SIZE_UNAVAILABLE로 복사0개였다.
wc 실패 fixture에서 같은 증상을 실패하는 회귀검사로 재현한 뒤 크기 확인 의존성을 제거했다.
최종 수집기9개 fixture는 기존 파일 보존·USB root/펌웨어 제한·자료 부재·복사 실패·wc 부재·
다른 제조사 경로의 probed 맵과0개 복사 실패를 확인한다.
host의 POSIX 셸을 사용하며 getprop/pwd/Android PATH와 source root를 가상 환경으로 대체한다.
수정본의 실제 파일 복사는 두 번째 차량 실행에서 확인할 항목이다.
수정본9개와 스크립트 구문 검사를 통과했다.
마지막 전체 Python 검사58개 중57개 통과, 기존 symlink 권한1개 skip이다.
production Java 변경이 없어 API19 계약 컴파일은 기존 통과 결과를 재사용했다.

## OEM SML 캐시 수집 보완

전체 Python61개 중60개 통과, 기존 symlink 권한 검사1개 skip이다.
collector12개 fixture는 기존9개에 SystemService system 버퍼의 고정 dump 인자,
세 문구만 보존하는 필터, reader 실패와 빈 캐시 기록을 추가로 확인한다.
셸 구문과 diff 공백 검사도 통과했다.
실제 OEM 로그의 존재·런타임 출력 조건과 원시값은 다음 USB 실행에서 확인한다.
Java190개와 API19 계약 검증은 해당 소스 변경이 없어 재사용했다.
