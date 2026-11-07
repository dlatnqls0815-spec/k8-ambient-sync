# K8의 Map checked와 SML 관측 경로

2026-11-07 사용자가 실제 차량 화면에서 읽어 전달한 정보를 기준으로 정리했다.
차량은 한국형 GL3HEV Gen5W, HMG-API1.0.2, NEMESIS1.16.4다.
로컬 프로젝트 private와 연결된 K8 USB에서는 기존 Car Report JSON을 발견하지 못했다.
아래 화면 정보는 사용자가 전달한 관측 기록이며 JSON 대조는 다음 수집 단계다.
후속 USB 실행 기록에서 차량 내부의 기존 보고서2개를 찾았다.
첫 수집은 파일 크기 확인 단계의 SIZE_UNAVAILABLE로 복사0개였고 원본 JSON은 차량에 남아 있다.
수정본은 wc 의존성을 제거했으며 수집 재실행 결과를 대기한다.

| 화면 항목 | 전달받은 값 | 현재 해석 |
|---|---|---|
| Map check | 342 of342 hook points resolve | 검사 목록342개에서 클래스·메서드·필드 resolve 성공 |
| Hook Map | probed for GL3HEV / kor260902 | 장치 파일에서 생성된 맵 상태 |
| missing | 1 point(s) not mapped | 해당 지점의 이름 확보 필요 |
| 검증 표시 | Map checked, verified 아님 | 장치 검사 기록과 맵의 배포 상태를 구분 |
| 순정 RGB/밝기 수신 | 미확인 | 실제 SML 이벤트 또는 원시값 자료 필요 |

## resolve와 실행 상태

HMG-API1.0.2의 `MapCheck.run`은 `m.points`에 있는 항목만 반복해 타입과 정확한 서명을 확인한다.
미매핑 지점은 그 검사 목록 밖에 있을 수 있어342/342 성공과 미매핑1개가 함께 표시될 수 있다.
`CarMap.load`는 probed/mapped 상태에서도 checkedHere 또는 testing_maps 조건으로 runnable을 계산한다.
checkedHere는 기존 검사 결과의 ok, 폴더와 파일 해시 목록이 현재 맵과 맞는지 확인한다.
화면의 `Map checked`와 맵 메타데이터의 `verified`는 별개다.

Car Report의 `map.hooks_on`은 보고서를 만들 때 계산한 맵 실행 조건이다.
현재 실행 중인 zygote/system_server에서 어떤 Hook이 이미 로드됐는지는 기존 로그로 확인해야 한다.
사용자가 전달한 화면에는 `hooks_on` 원시값과 현재 프로세스 로그가 포함되지 않아 미확인으로 둔다.

근거는 고정 버전의
[MapCheck](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/engine/MapCheck.java#L25-L92)와
[CarMap](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/engine/CarMap.java#L124-L148)이다.

## 세 후보의 역할

| Hook ID | 고정 버전의 서명 | 기능 | 수신 검증에서의 의미 |
|---|---|---|---|
| `framework.system_service.sml_rgb` | `setSMLRGB(byte[],byte[],byte[])` | RGB 설정 | 매핑 존재와 실제 입력 이벤트 수신을 따로 기록 |
| `framework.system_service.sml_configuration` | `setSMLConfiguration(int,int)` | 설정, 종류5는 밝기 | 밝기 설정 메서드의 존재를 확인 |
| `framework.system_service.sml_status` | `requestSMLStatus()` | 상태 요청 | 읽기 전용 수집에서는 호출 없이 기존 자료를 확인 |

후보 서명은 [framework catalogue](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/hookpoints/framework.json#L1549-L1586)에 있다.

현재 차량의 세 항목 포함 여부는 기존 Car Report의 `map.missing`, `map_car.points`,
`map_car.differs_from_map`과 현재 맵에서 복사한 framework.json을 대조해 판단한다.
`map_car.points`는 보고서 생성 중 탐색한 후보 목록이다. 선택된 맵의 항목 목록은 framework.json에서
확인한다. `differs_from_map`이 비어 있어도 선택된 맵에 빠진 후보는 그 비교에 포함되지 않을 수 있다.
전체 resolve 숫자만으로 개별 지점 포함 여부를 채우지 않는다.

## 실제 수신 경로의 의존성

기존 HMG-API의 순정 이벤트 경로는 다음과 같다.

```text
HmgApi.zygoteInit의 기본 Hook 조건
  -> Input.zygote의 loader/class_loader/intercept_key 조건
  -> Input.install
  -> LightRelay.start가 system_server에서 SML_STATUS_CHANGED 수신기 등록
  -> SML_STATUS byte[]
  -> 기존 vehicle 서비스의 CarCallbacks.lightStatus
```

세 SML 메서드가 모두 있어도 loader 또는 intercept_key 조건이 빠지면 이 LightRelay 경로가
시작되지 않을 수 있다. 반대로 RGB/밝기 설정 지점의 누락만으로 순정 방송 자체가 없다고
판정할 수는 없다. 자동 상태 요청과 순정 변경에 따른 passive 방송은 각 경로를 확인한다.
분석 도구는 기본 진입 조건·소유 클래스와 relay 조건을 별도 목록으로 검사한다.

근거는
[HmgApi](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/hook/HmgApi.java#L60-L84),
[Input](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/hook/Input.java#L94-L158),
[LightRelay](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/hook/LightRelay.java#L13-L46),
[CarCallbacks](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/vehicle/CarCallbacks.java#L209-L233)다.

기존 로그의 `light: passing the ambient light's status to the vehicle service`는 수신기 등록을 나타낸다.
`vehicle: ambient light status heard`는 과거 해당 프로세스가 첫 상태를 저장한 기록이다.
두 메시지의 시점·프로세스 수명과 RGB/밝기 변경값은 추가 대조가 필요하다.
Car Report의 errors는 주로 경고·오류를 추리므로 성공 정보 로그가 생략될 수 있다.
USB는 기존 `logs/hmgapi/log.log`와 `logs/hmgapi/vehicle.log`도 각각 확보한다.

## 미매핑 한 지점의 현재 판단

현재 차량의 미매핑 이름은 JSON 대기 상태다.
네메시스 작업 폴더에 보존된 이전 오프라인 K8 후보 맵은330개 항목이며
`themesettings.split_item.type` 하나가 missing으로 기록돼 있었다.
이전 후보와 현재342개 맵의 동일성은 확인하지 못했다.
현재 missing이 같은 항목이라면 확인한 SML 설정·relay 의존 목록에는 직접 포함되지 않는다.
현재 지점이 loader/intercept_key/기본 Hook 항목이면 relay 시작에 영향을 줄 수 있다.

`Report.notNoFiles`는 일부 absent-app 항목을 missing 요약에서 걸러낸다.
보고서의 missing 사전이 비어 있는 경우에도 전체 맵이 완전하다고 추정하지 않는다.
이 동작은 [Report](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/engine/Report.java#L176-L179)에 있다.

## 데이터와 향후 Lotus 연결 계약

기존 `SmlStatus`의 불변 raw 상태를 사용한다. 원시 채널은16비트 big-endian R/G/B,
밝기·dim·drive-mode는 각각 unsigned8비트다. byte[9] 이후 확장값은 보존한다.
K8에서 화면/실제 출력과 비교하기 전에는 테마값과 밝기 단위를 그대로 유지한다.

향후 관측 레코드는 아래 항목을 갖는다. 이는 설계 계약이며 실측 레코드는 아직 없다.

| 영역 | 필드 |
|---|---|
| 관측 출처 | source=sml_broadcast, session_alias, monotonic_ms, sender_validation=unknown |
| raw | redRaw, greenRaw, blueRaw, brightnessRaw, dimRaw, driveModeRaw, payloadLength |
| 정규화 | rgb8=null, brightness_percent=null, power=null, k8_calibration_id=null |
| Lotus 대상 | instruction_version=unknown, encryption=unknown, protocol_verified=false |
| 전송 준비 | transport_enabled=false |

K8 정규화가 실측으로 확인된 레코드만 이후 `AmbientState`에 넣는다.
기존 E1 후보 코덱과 실제 Lotus E1/E2·조건부 암호화 분기는 각각의 증거를 유지한다.
기존 소스 전용 `PassiveDiagnostics`와 이번 추가 fake 테스트는 raw6개 필드 보존을 검사한다.
receiver의250ms 로그 병합 간격은 관측 샘플링이며 동기화 지연의 실측값으로 사용하지 않는다.

## 별도 하드웨어에 대한 판단

소스에는 Gen5W 내부의 SML 관측 경로가 있다. 해당 K8의 수신·정규화, Gen5W BLE Central/GATT,
대상 컨트롤러와 Android Auto 병행이 확인되면 기존 장비를 이용한 직접 동기화 경로를 평가할 수 있다.
지금은 이 조건들의 실측 자료를 수집하는 단계이며 추가 하드웨어 필요 여부는 미확인이다.
