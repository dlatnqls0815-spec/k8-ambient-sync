# 미해결 문제

1. K8 맵과 실제 SystemService SML 로그는 확보했다. 설치할 진단 Hook의 실행과 수명은 미검증이다.
2. RGB 테마 코드, dimmed/drive-mode, 자동 밝기와 실제 발광색의 대응은 미확인이다.
3. Gen5W BLE Central/GATT 및 통화·미디어·Android Auto 병행 근거가 없다.
4. Lotus Lamp X 앱은 여러 기기 유형을 지원한다. 공개 후보 프로토콜은 대상 기기 증거가 아니다.
5. 시동 후 실행·복구, 실제 연결 유지·지연·색/밝기 보정은 실차 검증 전이다.
6. 독립 BLE 진단 APK는 두 서명 형식 모두 차량에서
   `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES`로 설치 거부됐다.
   차량에 진단 패키지는 등록되지 않았고 직접 GATT 시험은 대기 중이다.
   PC 재분석상 APK 자체는 정상이며 제조사 설치 정책이 유력하다. 설치 로그로 확정 전이다.
7. GitHub 동기화를 재개했다. 다른 PC에서는 현재 구현 브랜치 `codex/device-evidence`를 사용한다.
8. PC 파일 저장은 구현했지만 차량용 영구 저장·자동실행·종료 복구는 아직 통합하지 않았다.
9. 진단의 system_server 설정/OFF 콜백 전달과 QuickBoot 수명은 미검증이다.
   화면 스위치만으로 즉시 중지된다고 보장하지 않는다. 현재 모드는 disabled 상태다.
10. 순정 방송의 송신자를 인증하는 경로는 아직 없다. 진단값을 곧바로 실제 송신에 연결하면 안 된다.
11. 최초 Galaxy HCI로 FFF0/FFF3의 RGB와 밝기 쓰기를 확인했다. 사제 전원·재연결·최저 밝기
    반응은 미확인이다. 추가 USB 보고서의 ATT 쓰기는 0건이었다.
12. TShark→TSV→분석의 실측 경로는 검증했다. TSV 분석 도구의 지원 범위는
    고정 CID4/송신 ATT unsigned write request·command다.
13. Windows 권한 제한으로 실제 디렉터리 심볼릭 링크 생성 검사는 skip했다.
    일반 출력 경계·상위 경로 탈출·오류 원문 비출력과 기존 파일 보존 검사는 통과했다.
14. K8 raw 밝기 1의 의미는 최초 사용자 답변과 추가 OFF→최저 조작을 다시 대조해야 한다.
    현재 K8 밝기 변환은 임시 선형 보정이며 전원 상태를 추론하지 않는다.

현재 인계 기준은 [Claude 인수인계](CLAUDE_HANDOFF.md)를 사용한다.
