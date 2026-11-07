# USB 실행 대신 기존 ADB로 K8 자료 수집

사용자가 USB 삽입 실행이 안 된다고 알려 기존 승인된 차량 ADB 연결을 사용한다.
차량이 정차·P 상태이고 노트북에서 `adb devices -l`에 차량이 `device`로 보일 때 실행한다.
이 프로젝트는 기존 연결을 읽을 뿐 새 ADB 서비스나 레거시 TCP ADB를 켜지 않는다.
특히 과거 무선 Android Auto와 충돌한 `persist.service.adb.tcp.enable=1`을 다시 적용하지 않는다.
과거 차량 기록에서 일반 Wi-Fi 클라이언트는 꺼져 있었지만 무선 Android Auto용 AP는 실행 중이었다.
당시 OEM Wi-Fi 설정 화면 `com.hkmc.wifisettings/.WifiClientSettings`의 시작은 성공했으나
Wi-Fi 활성화까지 확인한 것은 아니다. ADB 연결이 없으면 PC에서 이 화면을 열 수 없다.
일반 Wi-Fi를 켜도 ADB TCP 서비스가 별도로 꺼져 있으면 연결되지는 않는다.

```powershell
python tools/collect_k8_adb.py --adb '<PC의 adb.exe 경로>'
```

연결된 기기가 여러 대여도 GL3HEV.KOR.*.260902 / Android 4.4.2 / API 19에 맞는
차량 하나를 찾는다. `--serial`로 기존 차량을 지정할 수도 있지만 값은 로컬에서만 사용한다.
읽기 전에 대상이 맞는지 확인한다. 이번 수집은 차량 설정·시스템 파일을 바꾸지 않는다.

`private/vehicle-adb/K8-AMBIENT-ADB-*`에 `collection.txt`, 선별된
`logs/oem-sml.log`, 기존 보고서·맵·설치 기록·HMG 로그를 저장한다.
OEM 로그는 SystemService 태그의 SML 응답/RGB/configuration 문구만 보존한다.
`oem_logcat_exit`와 `oem_sml_log_lines`로 읽기 실패와 캐시 0건을 구분한다.
BLE 선언 및 HMG 프로세스는 현재 순간의 요약만 기록한다.
파일이 없거나 접근 불가면 `UNAVAILABLE`로 기록하며 원본은 변경하지 않는다.

기존 캐시 확보 후 사용자가 순정 색상·밝기를 수동 변경하면 즉시 새 출력 폴더로
수집기를 한 번 더 실행한다. 로그 출력 자체와 실차 값 존재 여부는 결과로 판단한다.
Galaxy Lotus HCI 기록은 차량 ADB 수집과 별도다.
