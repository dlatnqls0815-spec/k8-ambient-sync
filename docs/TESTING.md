# 테스트

`python tools/check.py`로 코어 컴파일, assert 기반 동작 검사, 문서/메타데이터 검사를 실행한다.
JDK 8+와 Python 3.9+ 외 의존성은 없다. 자세한 검사 결과는 PROGRESS에 기록한다.

실제 차량·Bluetooth 연결, APK/모듈 설치, CAN/LIN/Binder 요청은 실행하지 않는다.
가상 이벤트·후보 패킷·모의 ACK·가상 시계만 사용한다.
모의 연결 성공은 BLE 지원, 물리 조명 반영 또는 200ms 성능을 증명하지 않는다.

실차 검증 순서는 펌웨어/복구/백업 확인 → Hook 읽기 → Galaxy 기존 앱 통신 확인 →
Gen5W 수동 기능 조회 → 대상 확인 후 별도 승인된 BLE 시험이다.
