# 보안 키보드 입력 전략

공동인증서 비밀번호와 계좌 비밀번호는 일반 `adb shell input text`로 입력하면 앱이 정상 보안키패드 입력으로 인정하지 않을 수 있다. 실제 테스트에서도 공동인증서 비밀번호 EditText에 텍스트는 전달됐지만, TransKey/보안키패드 입력으로 인정되지 않았다.

## 결론

Python ADB 컨트롤러는 앱 실행, 화면 진입, 상태 기록, OCR/replay, 주문 전후 검증을 담당한다. 공동인증서 TransKey 입력은 `src/login/`의 Android 접근성 helper가 담당한다.

## 이유

- `LoginMainActivity`에서는 ADB `screencap`이 0바이트를 반환했다.
- 같은 화면에서 `uiautomator dump`도 idle-state 오류로 실패했다.
- 보안키패드 입력은 앱 내부 TransKey가 인식해야 하므로 EditText 직접 입력으로는 충분하지 않다.
- 접근성 helper는 `AccessibilityNodeInfo`와 `dispatchGesture()`를 사용해 실제 키 노드 또는 키 좌표를 누른다.

## login helper의 핵심 흐름

기준 파일:

```text
src/login/app/src/main/java/com/yquant/mtsa/NeoSmartAccessibilityService.kt
```

공동인증서 로그인:

1. `openCertificateLoginScreen()`
2. `waitForCertLoaded()`
3. `openCertificatePasswordKeyboard()`
4. `enterCertificatePassword()`
5. `submitCertificateLogin()`

보안키패드 입력:

1. `findTransKeyInAnyWindow()`로 `fl_transkey`, `keypadContainer`, `transkey_navi_complete_button` 탐색
2. `findVirtualKeyboardKey()`로 접근성 노드의 문자 라벨 탐색
3. 필요한 경우 `readVisibleTextBoxes()`로 AccessibilityService screenshot + ML Kit OCR 사용
4. 노드 클릭 또는 `dispatchGesture()`로 실제 키 위치 입력
5. dot indicator 또는 입력 길이 증가로 각 키 입력 확인

## Python 쪽 브리지

Python CLI는 login helper의 동적 broadcast receiver에 명령을 보낸다.

```bash
./scripts/debug --adb /opt/homebrew/bin/adb --serial 824e1b4 login-status
./scripts/debug --adb /opt/homebrew/bin/adb --serial 824e1b4 login-command LOGIN
```

브로드캐스트 계약:

```text
action:  com.yquant.mtsa.ACTION_NAVIGATE
extra:   command=LOGIN
```

이 명령은 helper가 가진 전체 공동인증서 로그인 흐름을 실행한다. Python 컨트롤러는 실행 전후 current activity, 가능한 screenshot, run artifact를 기록하고, 로그인 이후 MTS shell 또는 목표 화면으로 돌아왔는지 검증한다.

## 비밀번호 저장 경계

공동인증서 비밀번호는 Python `config.yaml`에서 helper로 전달하지 않는다. ADB broadcast extra, shell command, logcat에 비밀번호가 남을 수 있기 때문이다.

저장 위치:

- 공동인증서 비밀번호: Android login helper 내부 Android Keystore 기반 암호화 저장소
- 계좌 비밀번호: Python 컨트롤러의 `config.yaml`
- profile JSON: 비밀번호, device serial, 전체 계좌번호 저장 금지

## 캡처 가능성

ADB screencap 기준으로는 로그인/보안키패드 화면이 캡처 불가로 관측됐다. AccessibilityService의 `takeScreenshot()`은 별도 API이지만, 기기/OS/보안 플래그에 따라 실패할 수 있다. 따라서 보안키패드 인식은 다음 순서로 설계한다.

1. 접근성 노드 라벨 기반 키 탐색
2. 접근성 screenshot OCR 기반 키 탐색
3. 키패드 구조 probe와 입력 길이 변화 기반 추론
4. 실패 시 중단

ADB screenshot OCR을 보안키패드 입력의 필수 경로로 두지 않는다.

## 계좌 비밀번호 키패드

계좌 비밀번호 입력 팝업의 숫자 키패드는 현재 기기에서 ADB screenshot 캡처가 가능했다. 구조는 `4x3` 숫자 격자이며, 숫자 10개와 빈칸 2개가 매번 랜덤한 위치에 배치된다. 따라서 profile에는 고정 숫자 좌표를 운영 좌표로 저장하지 않고, `secure_number_keypad.digit_grid` 영역을 캡처한 뒤 매번 OCR 결과를 12개 슬롯에 매핑한다.

확인 명령:

```bash
./scripts/profile --profile src/pension/profiles/1080x2340 map-keypad secure_number_keypad.digit_grid \
  --image screenshots/20260503-143012-account-password-current.png \
  --crop screenshots/20260503-143012-account-digit-grid.png \
  --json screenshots/20260503-143012-account-digit-grid.mapping.json
```

`map-keypad`는 전체 화면 OCR을 하지 않는다. ADB screenshot 직후 `digit_grid`만 crop하고, 키 좌표 산출에 필요한 TSV 단어 좌표만 읽는다. 결과는 실제 탭 가능한 전체 화면 좌표를 반환한다. 숫자 10개가 모두 인식되고 빈칸 2개가 감지된 경우에만 `complete: true`가 된다.
