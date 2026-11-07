# Galaxy 설치본 LotusLamp X 5.21.03의 제한적 정적 분석

조사일: 2026-11-07. 본인 Galaxy에 설치된 `com.szelk.ledlamppro`의 읽기 전용 메타데이터 확인과
로컬 base APK 사본을 대상으로 했다. 앱·APK를 실행하거나 조명에 연결/송신하지 않았다.
**실제 컨트롤러에 적용되는 프로토콜 분기와 패킷은 아직 미확인이다.**

## 분석 입력과 재현 위치

| 항목 | 확인값 |
|---|---|
| 설치 패키지 | `com.szelk.ledlamppro` |
| versionName / versionCode | `5.21.03` / `425` |
| base APK 크기 | 269,476,264 bytes |
| SHA-256 | `d0eca36d1f1bd47b0647a47d27e68fedcd57118932d8a73f4f15317b117749e2` |
| 원본 위치 | 로컬 Git 제외 디렉터리 `private/lotus-apk/base.apk` |
| 범위 | APK 안의 5개 DEX 중 관련 클래스·메서드·일부 호출 위치만 확인 |

Python 표준 라이브러리로 ZIP과 DEX의 string/type/method/class 정의 및 code_item을 읽었다.
아래 offset은 **압축 해제된 해당 DEX 파일의 시작을 0으로 한 byte offset**이다.
code_item 주소와 실제 invoke 위치를 구별한다. 원본 APK, 전체 문자열·디스어셈블 출력,
앱 구현 소스는 저장소나 외부 AI에 보내지 않는다. 이 문서는 인터페이스·패킷 구조 사실만 요약한다.

## 실제 패킷 생성 코드

`classes2.dex`에서 다음 구현과 호출을 확인했다. 클래스명은 `com.szelk.ledlamppro` 아래이며,
예전 공개 자료에 적힌 다른 패키지명을 이 설치본의 소스 위치로 대신 사용하지 않는다.

| 클래스 / 메서드 | method_id / code_item | 실제 명령에서 확인한 동작 |
|---|---|---|
| `ble.achieve.E1Achieve.achieve(ElkCMD, byte[], boolean, Byte): byte[]` | 48830 / `0x367868` | 9-byte 배열 생성, `7E`/명령별 길이/명령값/초기 payload/`00`/`EF` 배치 후 입력 payload 복사 |
| `E1Achieve.protocolLen(ElkCMD): byte` | 48831 / `0x367830` | 기본 BRIGHTNESS 길이 필드는 `04`; DATA_TIME은 `06`; 그 외는 `07`. 호출자 achieve의 optional Byte 인자로 길이를 override할 수 있음 |
| `E1Achieve$WhenMappings.<clinit>()` | 48827 / `0x3677ac` | 위 길이 분기에 사용되는 BRIGHTNESS/DATA_TIME enum 대응 확인 |
| `ble.achieve.E2Achieve.achieve(ElkCMD, byte[], boolean, Byte): byte[]` | 48846 / `0x367d70` | `8E` 시작·payload 길이+1·명령값·payload를 이어 붙이는 별도 형식 |
| `ble.device.connect.BlePackagingDevice.fromAchieve()` | 50035 / `0x37fc74` | `instructionVersion` enum에 따라 E1 또는 E2 구현체를 선택 |
| `BlePackagingDevice.dataFrom(...)` | 50022 / `0x379c78` | E1/E3/FishTank 및 공통 IAchieve 호출 등 여러 실제 경로 존재 |

E1에는 boolean 조건과 명령 종류에 따라 암호화 전처리 후
`EncryptionDecryptionKt.encryptBytes(byte[])`를 호출하는 경로가 있다.
조건 분기는 `0x367962`부터, helper invoke는 `0x367982`에서 확인했다.
대상 명령 집합에는 ON_OFF/MODE/BRIGHTNESS가 들어간다.
**앱 전체가 고정된 평문 E1만 사용한다고 볼 수 없다.** 키·인증 자료·암호 알고리즘은 조사하거나 공개하지 않았다.

### 평문 E1 경로의 제한적 구조 확인

아래는 해당 builder와 아래 메서드의 입력 배치로 확인한 구조다. 실제 장치 송신용 명령표가 아니다.
다른 device type·암호화·길이 override·LampAS/Colorful/두 채널 경로에서는 달라질 수 있다.

| 관찰한 함수와 조건 | 구조 사실 | 근거 code_item |
|---|---|---|
| `sendRGBColor(int,int,int,ELKPaletteType)`에서 일반 RGB palette, 평문 E1 선택 | `7E 07 05 03 RR GG BB 10 EF` 형식과 일치 | `0x384c84`; payload 배치 `0x384d40`–`0x384d7e` |
| `brightness(int,ELKLampType)`의 일반 경로 | payload는 입력 byte·lamp type·`FF`·appType·appVersion. 길이는 기본 `04` 또는 device별 override | `0x37cd3c`; 일반 경로 `0x37cdda`–`0x37ce3a` |
| `rgbOpen(boolean)`의 일반 경로 | payload 첫 값은 ON=`FF`/OFF=`00`; 다음 두 값은 상수 `01`과 전원 boolean; 이후 appType·appVersion (2026-11-07 재확인으로 `00`→`01` 정정, [PC 재분석](PC_ANALYSIS_2026-11-07.md)) | `0x383034`; 배치 `0x383054`–`0x3830aa` |

enum 초기화에서도 COLOR=`05`, BRIGHTNESS=`01`, ON_OFF=`04`, RGB palette=`03`을 확인했다.
`AppConstant$ElkCMD.<clinit>`의 code_item은 `0x337edc`,
`AppConstant$ELKPaletteType.<clinit>`는 `0x335d70`이다.
appType/appVersion은 이 클래스의 필드명이며 설치 앱의 versionName과 같은 값이라고 가정하지 않는다.
실제 컨트롤러의 값·단위는 채우지 않는다.

RGB 형식 일부가 공개 후보와 일치해도 밝기·전원·초기화까지 같은 프로토콜이라는 뜻은 아니다.
기존 `WporterE1Candidate`를 이 설치본의 일반적인 실제 장치 드라이버로 승격하지 않는다.

## GATT 호출과 UUID 적용 경로

| 확인 위치 | 실제 호출 근거 |
|---|---|
| `classes2.dex` `BlePackagingDevice.writeValue(byte[])`, code_item `0x38bc6c` | `0x38bed4`에서 AndroidBleManager.writeValue 경로, `0x38bf9c`에서 Heaton `Ble.write` 호출 |
| `classes5.dex` `util.AndroidBleManager.writeValue$lambda$45(...)`, code_item `0x4350d4` | `0x4350fa`에서 `BluetoothGatt.writeCharacteristic(BluetoothGattCharacteristic)` 호출 |
| `classes5.dex` `AndroidBleManager.connect(...)`, code_item `0x436a68` | `0x437140`에서 `BluetoothDevice.connectGatt(..., transport)` 호출 |
| `classes3.dex` `cn.com.heaton.blelibrary.ble.BleRequestImpl.executeGattOperation(...)`, code_item `0x327b1c` | `0x327c20`에서 `BluetoothGatt.writeCharacteristic(...)` 호출 |
| `classes3.dex` `BleRequestImpl.connect(...)`, code_item `0x32779c` | `0x3278d6` / `0x3278e8`에서 connectGatt overload 호출 |

따라서 **이 앱에 Android BLE/GATT 연결·쓰기 구현이 있다는 사실**은 문자열 존재 이상의 근거로 확인했다.
이것이 현재 사제 조명이 그 구현을 사용하는지, Gen5W가 같은 API를 지원하는지는 별도다.

`classes2.dex` `AppConstant.<clinit>` (`0x339844`)는 short UUID `fff0`와 `fff3`를
`UuidUtils.uuid16To128` 및 `UUID.fromString`으로 변환해 service/write 필드에 넣는다.
service 필드 배치는 `0x339888`, write 필드 배치는 `0x3398a6`이다.
helper는 `classes3.dex` code_item `0x332f2c`에서 Bluetooth base UUID 형식을 구성한다.
이 경로의 service는 `0000fff0-0000-1000-8000-00805f9b34fb`,
write characteristic은 `0000fff3-0000-1000-8000-00805f9b34fb`다.
`BlePackagingDevice.writeValue`가 `getUuidService()` / `getUuidWriteCha()`를 호출하는 위치는
각각 `0x38bea6` / `0x38beb2`다. 이들 값은 **확인한 전송 분기의 기본값**이며 대상 기기의 GATT discovery 결과가 아니다.
notify UUID, write-with-response 선택, 인증·keepalive와 실제 대상 분기는 이번 범위에서 확정하지 않았다.

## 남은 검증

- 지금 차량/조명에 접근할 수 없어 HCI 및 실제 반응을 대조하지 못했다.
- 실제 광고명·서비스 discovery·연결 초기화·단일 동작별 payload로 controller branch를 확정해야 한다.
- K8 순정 이벤트·Gen5W BLE 지원·물리 조명 반영/200ms·재연결 성능은 여전히 미확인이다.
- 원본 코드 재사용 허가를 확인한 것이 아니다. 앱 구현을 복사·배포하거나 그대로 프로젝트에 넣지 않는다.

이 문서로 완료한 것은 **설치본에 존재하는 builder/GATT 경로의 정적 근거 확보**다.
실제 송신·APK 수정/설치·차량 변경·기기 인증 우회는 수행하지 않았다.
