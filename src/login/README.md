# Android Login Helper

`src/login`은 한국투자증권 MTS의 공동인증서 로그인 영역만 담당하는 Android 접근성 helper 앱입니다. `src/pension` 같은 외부 자동화 코드는 이 앱을 ADB로 호출해 로그인 여부 확인과 공동인증서 로그인을 요청할 수 있어야 합니다.

## 담당 기능

- 설정 화면에서 공동인증서 비밀번호를 입력받아 Android Keystore 기반 저장소에 암호화 저장합니다.
- 로그인 여부 확인은 한국투자 앱 하단의 햄버거 `메뉴` 아이콘을 누른 뒤 메뉴 화면의 `로그인`/`로그아웃` 토글 표시로 판단합니다.
- 공동인증서 로그인은 저장된 공동인증서 비밀번호를 읽어 한국투자 공동인증서 로그인 화면과 TransKey 보안키패드 입력을 자동화합니다.

비밀번호는 ADB broadcast extra, profile JSON, logcat, 실행 결과 JSON에 넣지 않습니다.

## ADB Command Interface

`src/pension`에서 사용할 인터페이스는 ADB broadcast 기반으로 둡니다. 앱 UI 버튼을 직접 탭하는 방식은 실기 수동 검증용이고, 자동화 코드는 명시적인 command receiver를 호출하는 방식을 사용합니다.

권장 command:

- `CHECK_LOGIN_STATUS`: 현재 한국투자 앱 로그인 상태 확인
- `LOGIN`: 저장된 공동인증서 비밀번호로 공동인증서 로그인 수행

권장 호출 형태:

```bash
adb shell am broadcast \
  -n com.yquant.mtsa/.MtsaCommandReceiver \
  -a com.yquant.mtsa.COMMAND \
  --es command CHECK_LOGIN_STATUS \
  --es request_id pension-20260503-001
```

```bash
adb shell am broadcast \
  -n com.yquant.mtsa/.MtsaCommandReceiver \
  -a com.yquant.mtsa.COMMAND \
  --es command LOGIN \
  --es request_id pension-20260503-002
```

`request_id`는 호출자가 생성하는 추적용 문자열입니다. `src/pension`에서는 실행 run id나 timestamp 기반 값을 넣어 logcat 결과와 매칭합니다.

## Result Contract

command 결과는 logcat에 구조화된 JSON 한 줄로 남기는 방식을 사용합니다. `src/pension`은 broadcast 전후로 logcat을 정리한 뒤 `MtsaCommandResult` 태그를 읽고, 같은 `request_id`의 `finished=true` 결과를 기다립니다.

예상 포맷:

```text
MtsaCommandResult: {"request_id":"pension-20260503-001","command":"CHECK_LOGIN_STATUS","finished":true,"success":true,"state":"LOGGED_IN","message":"현재 한국투자 앱은 로그인된 상태입니다."}
```

필드:

- `request_id`: ADB 호출자가 전달한 값
- `command`: 실행한 command
- `finished`: 최종 결과 여부
- `success`: command 성공 여부
- `state`: `LOGGED_IN`, `LOGGED_OUT`, `UNKNOWN`, `ERROR` 중 하나
- `message`: 사람이 읽을 수 있는 상태 메시지

로그인 여부 확인에서 `success=false`, `state=LOGGED_OUT`은 자동화 실패가 아니라 정상적인 "로그인 안 됨" 결과입니다. 호출자는 이 경우 `LOGIN` command를 이어서 요청할 수 있습니다.

## Suggested Pension API

`src/pension` 쪽에서는 Android helper 세부 구현을 감추고 다음 정도의 얇은 API로 감싸는 것을 권장합니다.

```python
def check_login_status() -> LoginState:
    ...

def ensure_logged_in() -> bool:
    ...
```

`ensure_logged_in()` 권장 흐름:

1. `CHECK_LOGIN_STATUS` 호출
2. `LOGGED_IN`이면 바로 성공
3. `LOGGED_OUT`이면 `LOGIN` 호출
4. `LOGIN` 성공 후 다시 `CHECK_LOGIN_STATUS`로 재검증
5. `UNKNOWN` 또는 timeout이면 중단하고 run artifact에 실패 사유 저장

## Security Rules

- 공동인증서 비밀번호는 Android helper 앱 내부 Keystore 저장소에서만 읽습니다.
- ADB command에는 비밀번호, 계좌 비밀번호, 기기 serial, 전체 계좌번호를 싣지 않습니다.
- `LOGIN` command는 저장된 비밀번호가 없으면 실패해야 하며, 호출자에게 비밀번호 입력을 요구하는 상태 메시지만 반환합니다.
- logcat 결과에는 비밀번호나 보안키패드 입력값을 남기지 않습니다.

## Build And Install

루트 디렉터리에서 실행합니다.

```bash
make login-build
make login-install ADB=/opt/homebrew/bin/adb
```

가능하면 uninstall 대신 `adb install -r` 재설치를 사용합니다. 앱을 삭제하면 저장된 공동인증서 비밀번호도 함께 사라질 수 있습니다.

## Manual Verification Path

ADB command receiver 외에 UI 버튼 경로로도 수동 검증할 수 있습니다.

```bash
adb shell am start -n com.yquant.mtsa/.MainActivity
adb shell input tap 540 690   # 로그인 여부 확인 버튼, 1080x2340 테스트 기기 기준
adb shell input tap 540 1010  # 공동인증서 로그인 버튼, 1080x2340 테스트 기기 기준
```

화면 배치가 달라졌다면 먼저 `screenshots/`에 캡처를 저장해 버튼 위치를 확인합니다.
