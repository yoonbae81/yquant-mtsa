# 캘리브레이션 요구사항

## 목적

ADB 기반 MTS 자동화는 실행 중 반복적으로 OCR로 버튼을 찾지 않는다. 기기별 캘리브레이션 단계에서 누를 위치와 읽을 영역을 미리 확정하고, 실행 중에는 해당 profile을 사용해 빠르게 조작한 뒤 OCR/이미지 매칭으로 상태를 검증한다.

이 문서는 퇴직연금 ETF/리츠 주문 자동화를 위해 우선 캘리브레이션해야 할 화면, tap point, read region, anchor 문구를 정의한다.

근거:

- `old/captures/hanatu-apk/apktool/res/layout/fragment_request_tab.xml`
- `old/captures/hanatu-apk/apktool/res/layout/account_password_bottom_sheet.xml`
- `old/captures/hanatu-apk/apktool/res/layout/fragment_account_trans_keypad.xml`
- `old/captures/hanatu-apk/apktool/res/layout/holder_orderable_amount.xml`
- `old/captures/hanatu-apk/jadx/resources/assets/resource/addedfunction_n.dat`
- 실기 ADB screenshot/OCR 및 ADB tap 검증

## 전체 화면 목록

1. `home`
2. `order`
3. `account_password_popup`
4. `secure_number_keypad`
5. `account_select_sheet`
6. `order_search`
7. `balance`
8. `order_confirm`
9. `balance_holding_detail`
10. `common_recovery_overlay`

현재 공유 profile 기준의 핵심 화면은 `order`, `order_search`, `order_confirm`, `balance`, `account_password_popup`, `secure_number_keypad`, `common_recovery_overlay`다. 수량 입력은 별도 화면이 아니라 `order.quantity_input` tap point와 region으로 관리한다. 공동인증서 로그인 화면과 TransKey 입력은 `src/login` helper의 책임이며 pension profile에 저장하지 않는다.

## 공통 기준

각 화면은 다음 항목을 profile에 저장한다.

- `anchors`: 화면 판별에 사용할 OCR 문구. `required`, `optional`, `forbidden`, `min_score` 구조를 기본으로 한다.
- `tap_points`: 누를 위치
- `regions`: OCR 또는 이미지 매칭으로 읽을 영역
- `recovery`: 방해 팝업이 나타났을 때 닫는 위치와 anchor

좌표는 절대값 `x/y`와 비율값 `rx/ry`를 함께 저장한다. region은 `w/h`와 비율값 `rw/rh`도 함께 저장한다.

공유 profile은 `src/pension/profiles/<width>x<height>/` 아래 화면별 JSON으로 관리한다. `manifest.json`에는 schema version과 해상도 기준만 저장하고, 기기 serial, 비밀번호, 전체 계좌번호는 저장하지 않는다.

## 1. home

목적:

- MTS가 주문 화면이 아닌 홈/메뉴/로그인 상태로 돌아간 경우를 판별한다.
- 딥링크 진입 실패, 로그인 만료, 접근성 root의 하단 메뉴 오판 같은 상태를 구분한다.

Anchors:

- `MY`
- `국내`
- `해외`
- `상품/연금`
- `혜택`
- `로그인하기`
- `메뉴`

Tap points:

- `bottom_menu`: 하단 좌측 메뉴
- `login_button`: 로그인하기 버튼
- `close_quick_overlay`: 퀵뷰/지수 오버레이 닫기 후보

Read regions:

- `bottom_nav`: 하단 탭/퀵 메뉴 영역
- `home_login_area`: 로그인 유도 문구 영역
- `top_title_area`: 현재 MTS shell title 영역

비고:

- 이 화면은 정상 주문 루틴의 대상이 아니라 복구/재진입 판단용이다.

## 2. order

목적:

- 퇴직연금 ETF/리츠 주문 화면의 본문 상태를 판별한다.
- 계좌 비밀번호 버튼, 매수/매도 선택, 수량 입력, 주문 버튼을 제어한다.

디컴파일 근거:

- `addedfunction_n.dat`에 `7201`이 `퇴직연금 ETF/리츠 주문`으로 매핑된다.
- `fragment_request_tab.xml`에서 `ll_account` 안에 `btn_account`와 `btn_password`가 수평 배치된다.
- `btn_password`의 문자열은 `password_et = 비밀번호`다.

Anchors:

- `비밀번호`
- `매수`
- `매도`
- `정정`
- `취소`
- `체결`
- `잔고`
- `호가`
- `주문가능`
- `주문금액`

Tap points:

- `account_select`: 계좌 선택 버튼
- `account_password`: 계좌번호 우측 `비밀번호` 버튼
- `add_product`: 종목 추가/검색 버튼
- `market_price`: 시장가 선택 영역
- `buy`: 매수 탭
- `sell`: 매도 탭
- `modify_cancel`: 정정/취소 탭
- `filled`: 체결 탭
- `balance`: 잔고 탭
- `quantity_input`: 주문수량 입력칸
- `submit`: 하단 주문 버튼

Read regions:

- `account_row`: 계좌번호와 `비밀번호` 버튼이 있는 행
- `symbol_header`: 종목명/종목코드/현재가 영역
- `order_book`: 호가/현재가 영역
- `price_input`: 주문단가 표시 영역
- `quantity_input`: 수량 입력값 표시 영역
- `available_amount`: 주문가능 금액 또는 주문가능 수량 카드
- `order_amount`: 주문금액/예상금액 영역
- `filled_results`: 체결결과 읽기 영역

필수 검증:

- `account_row` OCR에서 기대 계좌 일부 또는 계좌 유형 확인
- 전체 주문 화면 OCR에서 매수/매도 선택 상태 확인
- `quantity_input` OCR에서 입력 수량 확인
- 주문 직전 전체 화면 OCR에서 계좌, 종목, 방향, 수량, 시장가, 주문금액을 확인

## 3. account_password_popup

목적:

- 계좌 비밀번호 입력 bottom sheet가 열렸는지 검증한다.
- 저장 옵션과 입력 상태를 확인한다.

디컴파일 근거:

- `account_password_bottom_sheet.xml`
- `account_trans_keypad_bottom_sheet_title = 계좌 비밀번호 입력`
- `save_password = 비밀번호 저장하기`
- `saved_pwd_guide = 앱 사용 중 저장되어 편리하게 이용 가능해요.`

Anchors:

- `계좌 비밀번호 입력`
- `비밀번호 저장하기`
- `앱 사용 중 저장되어 편리하게 이용 가능해요`

Tap points:

- `save_password_checkbox`: 비밀번호 저장하기 체크
- `sheet_handle`: bottom sheet handle
- `outside_or_back`: 팝업 외부/뒤로가기 fallback

Read regions:

- `popup_title`: `계좌 비밀번호 입력` 제목 영역
- `popup_account_number`: 팝업 내 계좌번호 영역
- `password_dots`: 4자리 입력 dot 영역
- `save_password_row`: 비밀번호 저장하기 row
- `save_password_guide`: 저장 안내 문구 영역
- `keypad_area`: 보안 키패드 전체 영역

필수 검증:

- 팝업 제목 확인
- 입력 전 dot 0개 또는 미선택 상태 이미지 확인
- 입력 후 dot 4개 또는 완료 상태 확인

## 4. secure_number_keypad

목적:

- 계좌 비밀번호 또는 수량 입력용 키패드를 제어한다.
- 보안 키패드가 랜덤 배열이면 캘리브레이션 좌표만으로 숫자 입력하지 않고, OCR/이미지 매칭으로 키 위치를 매회 식별해야 한다.

디컴파일 근거:

- `account_password_bottom_sheet.xml`과 `fragment_account_trans_keypad.xml` 모두 `layout_custom_keyboard_sec`를 include한다.
- `transkey_*` 레이아웃이 존재한다.

Anchors:

- `취소`
- `확인`
- 키패드 숫자 영역은 이미지 매칭 대상

Tap points:

- `key_0` to `key_9`: 고정 배열로 검증된 경우에만 사용
- `delete`: 삭제
- `cancel`: 취소
- `confirm`: 확인

Read regions:

- `keypad_full`: 키패드 전체
- `keypad_top_row`
- `keypad_middle_row`
- `keypad_bottom_row`
- `keypad_confirm_area`
- `keypad_delete_area`

필수 검증:

- 키패드 배열이 고정인지 랜덤인지 먼저 판별한다.
- 랜덤이면 숫자별 key 위치를 run마다 OCR/이미지 매칭 결과로 갱신한다.
- 비밀번호 입력 완료 후 `password_dots` 또는 팝업 종료 여부를 확인한다.

## 5. account_select_sheet

목적:

- 주문 계좌가 기대 계좌가 아닐 때 계좌 선택을 수행한다.

Anchors:

- `계좌를 선택`
- `계좌선택`
- `개인형IRP`
- `퇴직연금`

Tap points:

- `target_account_row`: 대상 계좌 row
- `sheet_close`: 닫기
- `sheet_confirm`: 확인 버튼이 있는 경우

Read regions:

- `sheet_title`
- `account_list`
- `target_account_candidate`
- 선택 후 복귀 화면의 `order.account_row`

필수 검증:

- 선택 전 후보 계좌 OCR
- 선택 후 `order.account_row` OCR

## 6. order_search

목적:

- 주문 대상 ETF/리츠 종목을 검색/선택한다.

Anchors:

- `종목`
- `검색`
- `ETF`
- `리츠`

Tap points:

- `search_field`
- `first_result`

Read regions:

- `search_results`
- `first_result_name`
- `first_result_code`
- 선택 후 복귀 화면에서는 `order.symbol_header`로 확인

필수 검증:

- 선택 종목명 또는 종목코드가 기대값과 일치해야 한다.

## 7. order quantity input

목적:

- `order` 화면 안에서 주문 수량을 입력하고 입력값 반영 여부를 검증한다.

Anchors:

- `수량`
- `주`
- `주문수량`
- `보유수량`
- `주문가능`

Tap points:

- `order.quantity_input`

Read regions:

- `order.quantity_input`
- `order.available_amount`
- `quantity_error_area`

필수 검증:

- 입력 수량 OCR
- 보유수량 또는 주문가능수량과 비교
- 매도 시 입력 수량이 보유수량을 넘지 않는지 확인

## 8. order_confirm

목적:

- 실제 주문 전 최종 확인 팝업을 검증한다.

Anchors:

- `주문`
- `확인`
- `취소`
- `매수`
- `매도`
- `수량`
- `금액`
- `계좌`

Tap points:

- `submit`: 최종 주문 확인
- `cancel`: 취소

Read regions:

- `summary`

필수 검증:

- 계좌, 종목, 매수/매도, 수량, 가격/금액이 모두 기대값과 일치해야 한다.
- `real-run` 외 모드에서는 `submit`을 누르지 않는다.
- `confirm-run`은 팝업 검증 후 `cancel`을 누르고, `manual-submit`은 팝업을 열어둔다.

## 9. filled_results

목적:

- 주문 접수 결과를 확인하고 주문번호 또는 실패 사유를 저장한다.

Anchors:

- `주문`
- `접수`
- `완료`
- `거부`
- `실패`
- `확인`

Tap points:

- `order.filled`

Read regions:

- `order.filled_results`

필수 검증:

- 체결결과에서 계좌, 종목, 방향, 수량, 가격 유형을 확인한다.
- 실패 사유 또는 미체결 문구가 있으면 OCR을 run artifact에 저장한다.

## 10. common_recovery_overlay

목적:

- 주문 흐름과 무관한 홍보/공지/이벤트/세션 안내 팝업을 닫고 원래 step으로 복귀한다.

Anchors:

- `확인`
- `닫기`
- `오늘 하루 보지 않기`
- `다시 보지 않기`
- `로그인 연장`
- `자동 로그아웃`
- `이벤트`
- `공지`
- `안내`

Tap points:

- `confirm`
- `close`
- `today_skip`
- `never_show`
- `extend_session`
- `back`

Read regions:

- `popup_full`
- `popup_title`
- `popup_body`
- `popup_buttons`
- `top_right_close`
- `bottom_button_row`

복구 정책:

- 최대 복구 횟수는 화면별로 제한한다.
- Recovery Handler는 최종 주문 버튼을 누르지 않는다.
- 복구 후 원래 expected state를 다시 검증한다.

## 캘리브레이션 순서

1. `device-info`로 serial, width, height, density를 profile에 기록한다.
2. login helper로 로그인한 뒤 `order` 전체 스크린샷을 저장한다.
3. `order`의 tap point를 먼저 기록한다.
4. `order`의 read region을 기록한다.
5. 계좌 비밀번호 버튼을 ADB tap으로 눌러 `account_password_popup`을 연다.
6. `account_password_popup`의 read region과 키패드 영역을 기록한다.
7. 계좌 비밀번호 키패드가 랜덤 배열인지 반복 캡처로 확인한다.
8. 홍보/공지/세션 팝업 샘플이 나오면 `common_recovery_overlay` fixture로 저장한다.
9. 수량 입력이 필요한 시점에 `order.quantity_input`, `order.available_amount`, `order.order_amount` 영역을 추가한다.
10. `confirm-run` 또는 `manual-submit`에서 `order_confirm`까지 도달한 뒤 최종 확인 팝업 영역을 기록한다.

## 1차 Profile에 반드시 있어야 할 항목

Tap points:

- `order.account_password`
- `order.account_select`
- `order.add_product`
- `order.market_price`
- `order.buy`
- `order.sell`
- `order.quantity_input`
- `order.submit`
- `order_confirm.submit`
- `order_confirm.cancel`
- `common_recovery_overlay.confirm`
- `common_recovery_overlay.close`

Read regions:

- `order.account_row`
- `order.symbol_header`
- `order.price_input`
- `order.quantity_input`
- `order.available_amount`
- `order.order_amount`
- `order.filled_results`
- `order_confirm.summary`
- `balance.holdings_grid`
- `account_password_popup.popup_title`
- `account_password_popup.password_dots`
- `account_password_popup.keypad_area`
- `common_recovery_overlay.popup_full`
- `common_recovery_overlay.popup_buttons`

## 미해결 확인 사항

- 퇴직연금 주문 화면의 수량 입력 키패드가 Android 기본 입력인지, MTS custom keypad인지 확인 필요
- 계좌 비밀번호 보안 키패드의 숫자 배열이 고정인지 랜덤인지 반복 캡처 필요
- 주문 확인 팝업 문구와 버튼 위치는 `confirm-run` 또는 `manual-submit` 도달 후 캘리브레이션 필요
- 매수와 매도에서 보유수량/주문가능수량 표시 위치가 동일한지 확인 필요
- 홍보 팝업은 샘플이 충분히 쌓이기 전까지 recovery anchor를 보수적으로 운용
