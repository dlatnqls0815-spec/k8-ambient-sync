# 다음 정차 방문에서 묶어서 확인할 항목

다음에 차를 사용할 때 한 번에 진행한다. 순정 1~11 단계의 밝기 자료와 Lotus의
RGB 및 5·10·15·20% 캡처를 재사용한다. 남은 실측은 Gen5W 직접 연결, 순정 꺼짐·테마,
Lotus 전원·최저 밝기다.

## 노트북에서 준비한 도구

- `tools/k8_ble_preflight.py`는 기존에 승인된 ADB 연결에서 정확한 K8 펌웨어와
  Android 4.4.2를 확인한다. BLE 기능 선언, Android 서비스 및 어댑터 상태의 요약을
  `private/k8-ble-preflight.json`에 저장한다. 직접 BLE 연결 성공은 다음 단계에서 판단한다.
- 진단 APK는 `private/ble-diagnostic/gen5w-ble-diagnostic.apk`에 둔다.
  앱은 화면에서 확인한 조명 주소 한 대만 찾아 GATT에 연결하고, 관측된 FFF0 서비스와
  FFF3 특성이 있는지 표시한다. 연결이 끝나거나 시간이 지나면 세션을 닫는다.
  공식 API 19 플랫폼으로 빌드하며 `tools/build_k8_ble_diagnostic.ps1`로 다시 만들 수 있다.
  APK와 개발 서명 키는 `private/ble-diagnostic/`에 보관한다.
- `tools/watch_k8_sml_adb.py`는 순정 조작과 동시에 기존 SML 로그의 선별 기록을 저장한다.
  Galaxy 기록은 기존 무선 ADB 및 TShark 분석 경로를 재사용한다.

## 현장 순서

1. **정차·P 상태**에서 이미 승인된 차량 ADB 연결을 사용한다. 차에 가기 전에 PC에서
   `adb` 실행 파일 경로와 APK를 확인한다. 차량에서 다음 요약을 먼저 저장한다.

   ```powershell
   python tools/k8_ble_preflight.py --adb "<기존 adb.exe 경로>"
   ```

2. 직접 연결 진단이 필요하면 **별도로 설치·실행을 승인한 뒤**, 검증된 차량만
   지정해 독립 진단 APK를 설치한다. 설치 전에 같은 패키지가 없는지 확인한다.
   Lotus 앱에서 사제 조명 연결을 잠시 해제하고, 기존 HCI 캡처에서 확인한 조명 주소를
   진단 앱에 입력한 다음 화면의 **연결 점검**을 누른다. 앱은 지정한 주소만 찾고
   서비스 목록과 FFF3의 Write Without Response 속성까지 확인한다.
   `CONNECTED_FFF0_FFF3_WRITE_NO_RESPONSE`가 나오면 해당 세션에서 직접 연결과
   특성 탐색에 성공한 것이다. 시간 초과는 조명 점유, 주소 변경, 접근 권한, 스택 상태를
   구분하지 못한다. 종료 뒤 진단 앱을 제거하려면 별도로 승인한 범위에서
   `io.github.k8ambient.bleprobe`만 제거한다.
3. **순정 조명**은 감시를 먼저 켜고 **꺼짐 → 약 5초 → 켜짐 1단계**를 한 번 누른다.
   다음으로 **평소 테마 하나 → 직접색 하나**만 구분해 선택한다. 각 조작의 시각과
   실제 점등 여부를 적고 원래 상태로 복원한다. 기존 밝기 전 구간 시험은 생략한다.

   ```powershell
   python tools/watch_k8_sml_adb.py --adb "<기존 adb.exe 경로>" --seconds 300
   ```

4. 진단 앱 연결을 종료한 뒤 휴대폰 개발자 옵션에서 **HCI snoop**를 켜고 블루투스를
   다시 켠다. **LotusLamp X**에서 기존 조명에 연결한다.
   **앱 OFF → 약 5초 → 앱 ON → 약 5초 → 2% 또는 앱에서 선택 가능한 최저값**
   순서로 조작한다. 각 시각, 앱 표시값, 실제 소등·점등 여부를 적는다. 여유가 있으면
   앱으로 한 번 재연결해 복원 상태를 적는다. 원래 밝기와 snoop 설정으로 돌아간다.
   Galaxy 버그 보고서는 이전처럼 무선 ADB로 PC에 가져올 수 있다.

직접 연결과 서비스 탐색이 확인되면 Gen5W 직접 연동 개발을 우선한다. 연결 단계에서
막히면 관측 결과를 검토해 Galaxy 중계를 다음 후보로 본다. 실제 전원·색상·밝기 송신과
시동 후 자동 동작은 별도의 통합 검증 항목이다.

진단 앱은 API 18에 추가된 BLE 탐색, 연결과 서비스 조회 API를 Android API 19에서 쓴다.
[Android BLE 탐색 API](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#startLeScan(android.bluetooth.BluetoothAdapter.LeScanCallback))
[Android GATT 서비스 탐색 API](https://developer.android.com/reference/android/bluetooth/BluetoothGatt#discoverServices())
