# 진단 수신기 JVM 동작 검사

이 디렉터리의 Android/HMG 클래스는 **테스트 전용 fake**다. 실제 `PassiveDiagnostics.java`를 이들과 함께 JVM에서 실행하여 기본 OFF, 후크 입력 검증, 정상/비정상 Intent, 원시 테마 보존, 250ms 최신값 병합, 중복 로그 제거, 비활성화 시 수신·예약 로그 중단, unregister/작업 스레드 정리, 등록 실패의 예외 차단을 검사한다.

스케줄러는 가상 시간과 FIFO 큐를 사용한다. Android 실제 스레드 경쟁, Binder의 송신자·권한, system_server 콜백 전달, K8 후크·브로드캐스트, DEX 실행을 검증하지 않는다. fake의 서명이 맞는다는 주장도 하지 않는다. 실제 API19 jar와 고정 버전 HMG 전체 runtime 소스에 대한 별도 계약 컴파일은 `tools/check.py --diagnostic`가 수행한다. fake는 실제 진단 JAR/패키지에 포함하지 않는다.
