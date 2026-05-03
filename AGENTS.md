# AGENTS.md

이 문서는 이 저장소에서 작업하는 에이전트와 로컬 개발자를 위한 작업 노트입니다. 사용자용 개요와 실행 방법은 `README.md`를 우선 확인합니다.

## 프로젝트 기준

이 프로젝트는 한국투자증권 KIS Mobile Trading System(MTS) 자동화를 위한 하이브리드 구조입니다.

- `src/pension/`: Python ADB 컨트롤러. 전체 상태 머신, 앱 실행/딥링크, screenshot/crop/OCR, profile 기반 좌표 조작, 계좌 비밀번호 입력, 잔고조회, 주문 검증/실행을 담당합니다.
- `src/login/`: Android login helper. 공동인증서 로그인과 캡처 불가 TransKey 보안키패드 입력만 담당합니다.
- CLI 진입점은 `scripts/` 아래에 둡니다.
- Python 테스트는 `tests/` 아래에 둡니다.
- 과거 분석 스크립트, 디컴파일/캡처 자료, 이전 문서는 `old/` 아래 참고용으로 보관합니다.
- 접근성 트리와 gesture는 조악하므로 주문 본문 자동화의 핵심 경로로 확장하지 않습니다.

## 로컬 도구 경로

환경 확인:

```bash
make setup
make check-env
```

`make setup`은 `.venv`와 Python 의존성을 준비하고 ADB/Tesseract 실행 파일 존재를 확인합니다. `make check-env`는 ADB/Tesseract 버전과 연결 기기 목록을 확인합니다.

## 디렉터리 규칙

```text
src/pension/     # Python ADB 컨트롤러 코드
src/pension/profiles/ # 공유 가능한 해상도별 캘리브레이션 profile
src/login/       # Android login helper
tests/                  # Python 단위 테스트
scripts/                # 실행 진입점
docs/                   # 설계 문서
screenshots/            # 임시 화면 캡처, 언제든 삭제 가능
runs/                   # 실행 산출물, git 제외
old/                    # 기존 Android/접근성 구현과 참고 자료
```

캘리브레이션 profile은 git에 올릴 수 있는 공유 자료여야 합니다. 기기 serial이나 비밀번호 같은 개인/비밀 정보는 profile에 저장하지 않습니다.

권장 profile 구조:

```text
src/pension/profiles/
  1080x2340/
    manifest.json
    retirement-order.json
    account-password.json
    keypads.json
    recovery.json
```

## 비밀 정보

Python ADB 컨트롤러가 직접 쓰는 개인/비밀 정보는 `config.yaml`에 둡니다.
저장소에는 `config.example.yaml`만 올리고, 실제 `config.yaml`은 git에 올리지 않습니다.

예상 구조:

```yaml
accounts:
  DC:
    password: "..."
  IRP:
    password: "..."
```

주의:

- `config.yaml`의 비밀번호를 로그나 최종 답변에 노출하지 않습니다.
- 공동인증서 비밀번호는 ADB broadcast extra나 profile JSON에 넣지 않습니다.
- 공동인증서 비밀번호는 Android login helper 내부 Android Keystore 기반 암호화 저장소에 둡니다.
- profile JSON에는 공동인증서 비밀번호, 계좌 비밀번호, 기기 serial, 전체 계좌번호를 저장하지 않습니다.
- 계좌 검증이 필요하면 마스킹 값이나 일부 문자열만 별도 정책으로 다룹니다.

## 스크린샷 파일

임시 PNG 스크린샷은 `screenshots/` 폴더에 저장합니다.

파일명은 `yyyymmdd-title.png` 형식을 사용하고, `title`은 내용을 알아볼 수 있는 짧은 영문 kebab-case로 작성합니다.

예:

```text
screenshots/20260503-account-password.png
screenshots/20260503-retirement-order.png
```

`screenshots/`는 언제든 삭제 가능한 임시 폴더로 취급합니다.

## 분석 산출물

한국투자 앱 디컴파일 산출물과 과거 화면 캡처는 `old/captures/` 아래에 있습니다.

주요 경로:

```text
old/captures/hanatu-apk/
old/captures/hanatu-apk/apktool/
old/captures/hanatu-apk/jadx/
```

한국투자 앱 구조를 확인할 때는 `old/captures/hanatu-apk/`를 우선 사용합니다. `old/captures/current_apk_jadx/`는 이 저장소의 예전 앱을 디컴파일한 산출물일 수 있으므로 한국투자 앱 분석의 우선 자료로 쓰지 않습니다.

## 자동화 방법론

자동화의 주 제어 경로는 Python ADB 컨트롤러입니다.

- 화면 캡처: `adb exec-out screencap -p`
- 입력: `adb shell input tap/text/keyevent/swipe`
- 앱 실행/딥링크: `adb shell am start ...`
- 화면 인식: screenshot crop + OCR + 이미지 매칭
- 실행 판단: 상태 머신과 profile 기반 검증

접근성 노드나 `dispatchGesture()`는 주문 본문 자동화의 핵심 경로로 사용하지 않습니다. 한국투자 MTS의 퇴직연금 주문 본문은 접근성 트리와 접근성 gesture에서 신뢰할 수 없다는 실기 증거가 있습니다.

공동인증서/TransKey 보안키패드 입력은 일반 ADB text 입력이 인정되지 않고 ADB screenshot도 차단될 수 있으므로, `src/login/`의 Android login helper에 위임합니다. Python ADB 컨트롤러는 실행 전후 상태 기록과 검증을 담당하고, 키 입력만 helper에 위임합니다. 상세 기준은 `docs/secure-keyboard-strategy.md`를 확인합니다.

## Android login helper 접근성 앱

`src/login/`은 계속 유지하는 Android 접근성 helper 앱입니다. 이 앱은 주문 본문 자동화로 확장하지 않고, 캡처가 막히거나 일반 ADB 입력이 통하지 않는 로그인 영역만 담당합니다.

앱의 사용자 기능은 세 가지로 제한합니다.

- 설정 페이지: 공동인증서 비밀번호를 사용자에게 입력받아 Android Keystore 기반 저장소에 암호화 저장합니다. 공동인증서 비밀번호를 ADB broadcast extra, profile JSON, 로그, 최종 답변에 노출하지 않습니다.
- 메인 페이지의 `로그인 여부 확인` 버튼: 한국투자 앱을 열고 화면 하단의 햄버거 `메뉴` 아이콘을 누른 뒤, 메뉴 화면의 `로그인`/`로그아웃` 토글 표시로 현재 로그인 상태를 판단합니다.
- 메인 페이지의 `공동인증서 로그인` 버튼: 저장된 공동인증서 비밀번호를 읽고 한국투자 공동인증서 로그인 화면으로 이동한 뒤 TransKey 보안키패드 입력과 로그인 버튼 실행을 수행합니다. 이 기능은 이미 구현된 접근성 서비스 경로를 우선 사용합니다.

한국투자 앱이 종료되었거나 테스트를 위해 직접 종료한 뒤 다시 실행하면, 한국투자 앱 동작에 따라 공동인증서 로그인이 다시 필요할 수 있습니다.

중요: `com.yquant.mtsa` 앱을 `adb uninstall` 하거나 기기에서 삭제하면 Android Keystore와 앱 내부 저장소의 공동인증서 비밀번호도 함께 삭제될 수 있습니다. 실기 검증 중에는 앱 삭제를 기본 선택지로 쓰지 말고, 우선 `adb install -r ...`, `am force-stop`, 접근성 서비스 재활성화, 앱 재실행으로 복구합니다.

공동인증서 로그인 자동화 검증은 접근성 서비스에 `LOGIN` 브로드캐스트를 직접 보내는 우회 경로보다, MTSA 앱을 열고 `[공동인증서 로그인]` 버튼을 실제로 눌러 `MainActivity`의 버튼 핸들러 경로를 타는 방식을 우선합니다. 이 경로는 저장된 공동인증서 비밀번호 확인, 한국투자 앱 실행, 공동인증서 로그인 화면 탐색, 보안 키보드 열기, 비밀번호 입력, 로그인 버튼 클릭까지 포함합니다.

ADB로 수동 검증할 때는 다음 순서로 진행합니다.

```bash
adb shell am start -n com.yquant.mtsa/.MainActivity
adb shell input tap 540 720
```

좌표는 테스트 기기 화면 배치에 따라 달라질 수 있습니다. 화면 배치가 달라졌다면 먼저 `screenshots/`에 MTSA 앱 화면을 캡처해 버튼 위치를 확인한 뒤 탭합니다. 로그인 성공 여부는 logcat의 `MtsaAccessibility` 태그에서 `공동인증서 로그인이 완료되었습니다.` 메시지로 확인할 수 있습니다.

좌표 기반 조작은 무작정 하드코딩하지 않습니다. 기기 해상도별 profile에 tap point/read region으로 기록하고, 실행 중에는 해당 profile을 통해 사용합니다.

OCR은 매번 버튼 위치를 찾는 용도가 아니라 다음 목적에 우선 사용합니다.

- 현재 화면 검증
- 계좌/종목/매수매도/수량/금액 검증
- 팝업/오버레이 감지
- 입력 결과 확인
- 최종 주문 직전 안전 검증

## CLI 체계

`scripts/` 아래 실행 파일은 프로젝트 prefix 없이 역할 이름만 사용합니다.

```text
scripts/profile   # 캘리브레이션/profile 관리
scripts/run       # 운영 시나리오 실행
scripts/debug     # 저수준 ADB/OCR 디버그
```

현재 구현이 아직 `scripts/mtsa`에서 출발한 경우에도 새 구현은 위 체계로 이동합니다.
`scripts/mtsa`는 기존 명령 호환용 wrapper로만 유지합니다.

역할:

- `scripts/profile`: profile 생성, tap point 기록, read region 기록, region OCR 확인
- `scripts/run`: 로그인, 주문 화면 진입, dry-run/confirm-run/real-run 실행
- `scripts/debug`: devices, capture, OCR, 단일 tap, 단일 region 읽기

## 한국투자 앱 기본 정보

- 패키지명: `com.truefriend.neosmartarenewal`
- 메인 액티비티: `com.truefriend.neosmartarenewal.ui.main.MTSMainActivity`
- 딥링크 extra:
  - `ActionDeepLink`
  - `KeyOpenScreenNo`
  - `KeyOpenScreenData`

디컴파일 근거:

- `old/captures/hanatu-apk/jadx/resources/AndroidManifest.xml`
- `old/captures/hanatu-apk/jadx/sources/com/truefriend/neosmartarenewal/util/deeplink/AppDeepLinkHandler.java`

주요 화면 번호:

- 공동인증서 로그인: `6300`
- 퇴직연금 ETF/리츠 주문: `7201`

## 캘리브레이션 대상

우선 캘리브레이션해야 할 화면:

- `retirement_order`
- `account_password_popup`
- `secure_number_keypad`
- `account_select_sheet`
- `stock_search`
- `quantity_input`
- `order_confirm`
- `order_result`
- `common_recovery_overlay`

상세 요구사항은 `docs/calibration-requirements.md`를 확인합니다.
구현 순서와 안정화 기준은 `docs/implementation-roadmap.md`를 확인합니다.

## 실행 산출물

자동화 실행 결과는 `runs/` 아래에 저장합니다.

저장 대상:

- 실행 시각
- 실행 모드
- ADB 명령 로그
- before/after screenshot
- crop 이미지
- OCR JSON
- decision JSON
- recovery 실행 여부
- 실패 사유

`runs/`는 git에 올리지 않습니다. 실패 케이스를 테스트 자산으로 승격할 때만 별도 fixture로 정리합니다.

## 안전 규칙

실제 주문은 기본값이 아닙니다.

- 기본 실행 모드는 `inspect` 또는 `dry-run`이어야 합니다.
- `real-run`은 명시적 옵션과 최종 검증 통과가 모두 있을 때만 허용합니다.
- 주문 전에는 계좌, 종목, 매수/매도 방향, 수량, 금액을 OCR/이미지 검증으로 확인합니다.
- 알 수 없는 화면이나 주문 정보 불일치가 발생하면 중단합니다.
- 홍보/공지/세션 연장 등 주문 의미와 무관한 팝업만 Recovery Handler로 닫고 재검증합니다.

Recovery Handler는 최종 주문 버튼을 누르지 않습니다.

## 작업 완료 기준

Python ADB 컨트롤러 코드 변경 작업은 다음이 통과해야 완료로 봅니다.

```bash
make test
make compile
```

실행환경을 건드렸거나 ADB/OCR 관련 변경이 있으면 다음도 확인합니다.

```bash
make check-env
```

CLI 동작을 건드렸으면 다음도 확인합니다.

```bash
make cli-help
```

Android login helper를 수정한 경우에는 별도로 빌드와 기기 설치, 그리고 실제 기기 동작 확인까지 완료해야 합니다.

```bash
make login-build
make login-install ADB=/opt/homebrew/bin/adb
```
