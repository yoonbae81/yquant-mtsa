# ADB 기반 MTS 자동화 설계서

## 목적

이 문서는 한국투자 MTS 퇴직연금 주문 자동화를 하이브리드 구조로 구성하기 위한 설계 기준을 정리한다.

현재 실기 검증 결과, 퇴직연금 주문 화면은 Android 접근성 트리에 실제 주문 본문을 안정적으로 노출하지 않는다. 접근성 root에서는 하단 메뉴 또는 메인 홈성 텍스트가 반환되지만, ADB 스크린샷 OCR에서는 실제 주문 화면의 `비밀번호` 필드가 확인된다. 또한 동일 좌표에서 ADB `input tap`은 계좌 비밀번호 팝업을 열었지만, 접근성 `dispatchGesture()`는 동작하지 않았다.

따라서 장기적으로 주문 자동화의 핵심 경로는 Python ADB 컨트롤러를 하위 제어 계층으로 두고, 화면 인식과 주문 상태 판단을 그 위에 구성한다. 단, 공동인증서 TransKey 로그인은 ADB 캡처와 일반 text 입력이 모두 막히므로 Android login helper에 위임한다.

## 설계 원칙

1. ADB는 직접 흩뿌려 호출하지 않고 단일 제어 레이어로 캡슐화한다.
2. 실행 중 반복 조작은 사전 캘리브레이션된 좌표와 영역을 우선 사용한다.
3. OCR은 버튼 탐색보다 상태 검증과 예외 판단에 주로 사용한다.
4. 모든 실험과 실패는 재현 가능한 산출물로 저장한다.
5. 복구 가능한 방해 요소는 자동 복구하고, 주문 의미가 흔들리는 실패는 중단한다.
6. 실제 주문은 dry-run, 확인 실행, 실주문 모드로 명확히 분리한다.

## 계층 구조

```text
Order Scenario
  - 퇴직연금 매수/매도 흐름
  - 계좌 비밀번호 입력
  - 수량 입력
  - 최종 주문 확인

Screen State Machine
  - 현재 화면 판별
  - 허용 액션 제한
  - 다음 상태 검증
  - 복구 핸들러 호출

Secure Login Adapter
  - login helper readiness 확인
  - LOGIN command broadcast
  - 공동인증서 TransKey 입력 위임
  - 로그인 전후 화면 검증

Device Profile / Calibration
  - 기기별 해상도, density
  - tap point
  - read region
  - OCR anchor
  - crop 영역

Perception Layer
  - ADB screenshot
  - crop
  - OCR
  - 이미지 매칭
  - fixture replay

ADB Device Layer
  - adb executable 래핑
  - tap / text / keyevent / swipe
  - screencap
  - app start / force-stop
  - logcat
  - timeout / retry

Android Device
  - KIS MTS
  - login helper
```

## 서브시스템 경계

```text
src/pension/
  Python ADB controller
  - 전체 상태 머신
  - 앱 실행/딥링크
  - screenshot/crop/OCR
  - profile 기반 tap/swipe/text
  - 계좌 비밀번호 OCR 입력
  - 잔고조회와 주문 검증/실행
  - runs 산출물 저장

src/login/
  Android login helper
  - 공동인증서 로그인
  - 캡처 불가 TransKey 보안키패드 입력
  - Android Keystore 기반 인증서 비밀번호 저장
  - Python에서 보낸 command broadcast 처리
```

접근성 helper는 주문 본문 자동화를 소유하지 않는다. 접근성 트리와 gesture가 조악하다는 실기 결과 때문에, 주문 화면의 계좌/종목/수량/금액 검증과 최종 버튼 제어는 ADB screenshot/OCR/profile 기반 경로에 둔다.

## ADB Device Layer

ADB Device Layer는 기기 조작의 원시 기능만 책임진다. 이 레이어는 주문 의미를 알지 않는다.

주요 책임:

- 연결 기기 조회
- serial 선택
- 해상도와 density 조회
- 스크린샷 캡처
- tap, swipe, text, keyevent 실행
- 앱 실행과 종료
- logcat 수집
- 파일 push/pull
- timeout과 retry
- 표준화된 에러 반환

상위 계층은 다음처럼 의미 있는 이름으로 호출한다.

```python
device.capture("before_password_popup")
device.tap_point("retirement_order.account_password")
device.input_text("10")
device.press_back()
```

ADB Layer 내부에서만 실제 명령을 조립한다.

```bash
adb -s SERIAL exec-out screencap -p
adb -s SERIAL shell input tap 900 548
adb -s SERIAL shell input text 10
adb -s SERIAL shell input keyevent BACK
```

## 기기 프로파일과 캘리브레이션

기기별 좌표와 OCR 대상 영역은 코드가 아니라 profile 파일에 저장한다. 기기 변경 시에는 주문 로직을 수정하지 않고 캘리브레이션만 다시 수행한다.

공유 profile의 표준 위치는 `src/pension/profiles/<width>x<height>/`이다. profile은 git에 올릴 수 있어야 하므로 기기 serial, 비밀번호, 전체 계좌번호 같은 개인/비밀 정보는 저장하지 않는다.

```text
src/pension/profiles/
  1080x2340/
    manifest.json
    retirement-order.json
    account-password.json
    keypads.json
    recovery.json
```

캘리브레이션 대상은 두 종류다.

1. 누를 위치
2. 읽을 영역

`manifest.json`은 schema와 해상도 기준만 보관한다.

```json
{
  "device": {
    "width": 1080,
    "height": 2340,
    "density": 420
  },
  "schema_version": 1
}
```

화면별 JSON은 `screen`, `anchors`, `tap_points`, `regions`를 가진다. `anchors`는 단순 문자열 배열 대신 `required`, `optional`, `forbidden`, `min_score`를 사용해 OCR 누락과 잘못된 화면을 함께 다룬다.

```json
{
  "screen": "retirement_order",
  "anchors": {
    "required": ["퇴직연금"],
    "optional": ["비밀번호", "매수", "매도", "주문가능", "호가"],
    "forbidden": ["오류", "로그인"],
    "min_score": 0.7
  },
  "tap_points": {
    "account_password": { "x": 900, "y": 548, "rx": 0.833333, "ry": 0.234188 }
  },
  "regions": {
    "account_row": {
      "x": 40,
      "y": 490,
      "w": 1000,
      "h": 130,
      "rx": 0.037037,
      "ry": 0.209402,
      "rw": 0.925926,
      "rh": 0.055556
    }
  }
}
```

절대 좌표 `x/y`와 비율 좌표 `rx/ry`를 함께 저장한다. region은 `w/h`와 `rw/rh`도 함께 저장한다. 같은 기기에서는 절대 좌표를 우선 사용하고, 해상도 변경 또는 유사 기기 이식 시 비율 좌표로 초기값을 제안할 수 있다.

Python 컨트롤러가 직접 쓰는 개인 설정과 비밀 정보는 `config.yaml`에 둔다. 저장소에는 `config.example.yaml`만 올린다. 공동인증서 비밀번호는 ADB broadcast extra로 전달하지 않고 Android login helper 내부 Android Keystore 기반 암호화 저장소에 둔다.

## OCR과 이미지 인식 전략

전체 화면 OCR은 느리고 변동성이 크므로 기본 루틴에서는 최소화한다.

우선순위:

1. 캘리브레이션된 crop 영역 OCR
2. 좁은 영역 이미지 매칭
3. 필요한 경우 전체 화면 OCR
4. 그래도 불확실하면 중단 또는 수동 검토 요청

OCR의 기본 역할:

- 현재 화면이 예상 상태인지 확인
- 팝업이나 공지 오버레이 감지
- 수량 입력 결과 검증
- 보유수량, 주문가능수량 등 숫자 읽기
- 최종 주문 직전 종목, 계좌, 매수/매도, 수량 확인

OCR을 매번 버튼 위치 탐색에 사용하지 않는다. 버튼 위치는 캘리브레이션된 tap point를 사용하고, OCR은 그 결과가 맞는지 확인하는 용도로 둔다.

## 상태 머신

자동화 흐름은 명시적인 상태 머신으로 구성한다. 각 상태는 허용 가능한 액션과 기대하는 다음 상태를 가진다.

예시 상태:

- `MTS_HOME`
- `RETIREMENT_ORDER`
- `ACCOUNT_PASSWORD_POPUP`
- `ACCOUNT_PASSWORD_ENTERED`
- `ORDER_SIDE_SELECTED`
- `QUANTITY_FOCUSED`
- `QUANTITY_ENTERED`
- `ORDER_CONFIRM`
- `ORDER_READY`
- `RECOVERING`
- `UNKNOWN`

예시 흐름:

```text
RETIREMENT_ORDER
  -> tap account_password
  -> ACCOUNT_PASSWORD_POPUP
  -> input password
  -> ACCOUNT_PASSWORD_ENTERED
  -> select buy/sell
  -> ORDER_SIDE_SELECTED
  -> input quantity
  -> QUANTITY_ENTERED
  -> tap order
  -> ORDER_CONFIRM
  -> verify final summary
  -> ORDER_READY
```

상태 검증이 실패하면 바로 다음 좌표를 누르지 않는다. 먼저 Recovery Handler가 개입할 수 있는지 판단한다.

## Recovery Handler

실패를 모두 중단으로 처리하면 실제 운용성이 떨어진다. 홍보 팝업, 공지, 이벤트 안내, 세션 연장 안내처럼 주문 의미와 무관한 방해 요소는 복구 후 원래 단계로 돌아간다.

복구 흐름:

```text
Step 실행
  -> 기대 상태 검증 실패
  -> Recovery Handler 실행
  -> 복구 가능한 팝업/오버레이인지 판단
  -> 닫기/확인/뒤로가기 수행
  -> 원래 Step 재검증
  -> 성공하면 계속, 실패하면 중단
```

복구 가능한 예:

- 홍보 팝업
- 이벤트 팝업
- 공지 팝업
- 오늘 하루 보지 않기 팝업
- 세션 연장 안내
- 네트워크 재시도 안내
- 토스트 또는 스낵바로 인한 일시적 가림

중단해야 하는 예:

- 계좌 불일치
- 종목 불일치
- 매수/매도 방향 불일치
- 수량 불일치
- 주문가능수량 부족
- 최종 확인 팝업의 주문 정보 불일치
- 보안 입력 실패 반복
- 알 수 없는 화면에서 복구 실패

복구 정책도 설정 파일에 둔다.

```json
{
  "recoveries": {
    "common_popup": {
      "anchors": ["확인", "닫기", "오늘 하루 보지 않기"],
      "tap_points": {
        "confirm": { "x": 820, "y": 1680, "rx": 0.7593, "ry": 0.7 },
        "close": { "x": 985, "y": 420, "rx": 0.912, "ry": 0.175 }
      },
      "max_attempts": 2
    },
    "session_notice": {
      "anchors": ["자동 로그아웃", "로그인 연장"],
      "tap_points": {
        "extend": { "x": 810, "y": 1540, "rx": 0.75, "ry": 0.6417 }
      },
      "max_attempts": 1
    }
  }
}
```

Recovery Handler는 주문 버튼이나 최종 확인 버튼을 누르지 않는다. 복구 액션은 닫기, 확인, 뒤로가기, 로그인 연장처럼 방해 요소 제거에 한정한다.

## 실행 모드

실제 금융 주문을 다루므로 실행 모드는 명확히 분리한다.

- `inspect`: 현재 화면 캡처, OCR, 상태 판단만 수행
- `calibrate`: tap point와 read region 설정
- `dry-run`: 실제 주문 직전까지 진행하고 중단
- `confirm-run`: 최종 주문 직전 사용자 확인을 요구
- `real-run`: 명시적으로 허용된 경우에만 최종 주문까지 진행

기본값은 `inspect` 또는 `dry-run`이어야 한다. `real-run`은 설정 파일, CLI 옵션, 실행 전 확인 로그가 모두 충족될 때만 허용한다.

## 실험 산출물과 Replay

이 프로젝트는 실기 시행착오가 많기 때문에, 실패를 분석 자산으로 축적해야 한다.

각 실행은 고유 run 디렉터리를 만든다.

```text
runs/20260503-143012/
  run.json
  run.log
  001-before-retirement-order.png
  001-before-retirement-order.ocr.json
  001-decision.json
  002-after-password-tap.png
  002-after-password-tap.ocr.json
  002-decision.json
```

저장할 정보:

- 실행 시각
- 기기 serial, 해상도, density
- 실행 모드
- 현재 step 이름
- 실행한 ADB 명령
- before/after screenshot
- crop 이미지
- OCR 원문과 파싱 결과
- 판단한 state
- 사용한 tap point 또는 region 이름
- Recovery Handler 실행 여부
- 실패 이유

Replay 기능은 저장된 스크린샷과 OCR 결과를 입력으로 받아 화면 판단 로직만 다시 실행한다. 기기 없이도 인식 로직을 빠르게 개선할 수 있어야 한다.

```bash
mtsa replay runs/20260503-143012
```

## CLI 우선 개발

초기에는 GUI보다 CLI를 우선한다. CLI는 실험 속도가 빠르고 로그와 fixture 관리가 쉽다.

권장 명령:

```bash
make setup
mtsa device-info
mtsa capture current --name retirement-order
mtsa ocr latest
mtsa calibrate retirement-order
mtsa check retirement-order
mtsa tap retirement_order.account_password
mtsa recover
mtsa run retirement-order --dry-run
mtsa replay runs/20260503-143012
```

CLI가 안정화되면 Android 앱 또는 데스크톱 GUI는 이 CLI/엔진 위에 얹는다.

## 권장 디렉터리 구조

```text
src/pension/
  adb_device.py
  device_profile.py
  screen_capture.py
  ocr.py
  image_matcher.py
  state_machine.py
  recovery.py
  run_artifacts.py
  scenarios/
    retirement_order.py
  profiles/
    1080x2340/
      manifest.json
      retirement-order.json
      account-password.json
      keypads.json
      recovery.json
  fixtures/
    retirement_order/

runs/
  20260503-143012/
```

`runs/`는 실행 산출물이므로 기본적으로 git 추적 대상에서 제외한다. `fixtures/`는 실패 케이스를 테스트 자산으로 승격한 경우에만 git에 포함한다.

## 접근성 앱의 위치

접근성 앱은 필수 제어 경로로 보지 않는다. 한국투자 MTS의 퇴직연금 주문 본문은 접근성 노드와 `dispatchGesture()` 양쪽에서 신뢰할 수 없다는 실기 증거가 있다.

가능한 역할:

- 기존 설정 UI
- 저장된 비밀번호 관리
- 접근성 노드가 안정적인 일부 로그인/팝업 화면 보조
- 상태 표시용 companion app

핵심 주문 제어, 화면 캡처, 좌표 입력, 상태 검증은 ADB 기반 컨트롤러가 담당한다.

## 개발 반복 절차

1. 실기 화면을 ADB로 캡처한다.
2. 필요한 tap point와 read region을 캘리브레이션한다.
3. 단일 step 명령으로 조작을 검증한다.
4. before/after screenshot과 OCR 결과를 저장한다.
5. 실패 케이스를 분석해 recovery, anchor, region을 보강한다.
6. 반복 실패 또는 중요한 화면은 fixture로 승격한다.
7. dry-run으로 전체 주문 플로우를 검증한다.
8. 최종 주문 단계는 별도 확인 모드에서만 검증한다.

## 안전 장치

최종 주문 전에는 다음 항목을 반드시 검증한다.

- 계좌 종류와 계좌번호
- 종목명 또는 종목코드
- 매수/매도 방향
- 주문 수량
- 주문 단가 또는 시장가 여부
- 주문가능수량 또는 주문가능금액
- 최종 확인 팝업의 핵심 문구

실주문 모드는 다음 조건을 요구한다.

- dry-run 통과
- 동일 기기 프로파일 사용
- 최근 캘리브레이션 유효
- 최종 확인 OCR 통과
- 주문 상한 수량/금액 설정
- 사용자 또는 외부 호출자의 명시적 승인

## 1차 구현 범위

1차 구현은 실주문이 아니라 검증 가능한 자동화 기반을 만드는 데 집중한다.

- ADB Device Layer
- 기기 정보 조회
- 스크린샷 저장
- OCR 래퍼
- 프로파일 파일 로딩
- tap point 실행
- read region crop
- run artifact 저장
- 퇴직연금 주문 화면 판별
- 계좌 비밀번호 버튼 tap 검증
- 계좌 비밀번호 팝업 검증
- common popup recovery 골격
- replay 골격

이 범위가 안정화된 뒤 수량 입력, 매수/매도 선택, 주문 확인 단계로 확장한다.
