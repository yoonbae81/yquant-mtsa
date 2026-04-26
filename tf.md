# NeoSmart (한국투자증권 MTS) - ADB 제어 및 API 분석 정보

## 기기 정보

| 항목 | 값 |
|---|---|
| 기기 ID | `824e1b4` |
| 모델 | Xiaomi (lavender) |
| 해상도 | 1080x2340 (440dpi) |
| 앱 패키지 | `com.truefriend.neosmartarenewal` |
| 메인 Activity | `.ui.main.MTSMainActivity` |
| 스플래시 Activity | `.ui.splash.SplashActivity` |
| 딥링크 Activity | `.util.deeplink.AppDeepLinkActivity` |
| 앱 버전 | v2.23.00 |
| 루팅 | Magisk (su 권한 확인됨) |

## 사전 준비

### USB 디버깅 (보안 설정) 활성화 필요

ADB로 화면 터치(`input tap`)를 하려면 기기에서 **설정 > 개발자 옵션 > USB 디버깅(보안 설정)** 을 켜야 함. 그렇지 않으면 `INJECT_EVENTS` 권한 에러 발생.

---

## 기본 ADB 명령어

### 앱 실행

```bash
# 일반 실행 (스플래시부터)
./adb shell am start -n com.truefriend.neosmartarenewal/.ui.splash.SplashActivity

# monkey로 실행 (런처 Activity 자동 탐색)
./adb shell monkey -p com.truefriend.neosmartarenewal -c android.intent.category.LAUNCHER 1
```

### 화면 캡처

```bash
./adb shell screencap -p /sdcard/screen.png && ./adb pull /sdcard/screen.png ./screen.png
```

### 화면 터치

```bash
./adb shell input tap <x> <y>
```

### 현재 Activity 확인

```bash
./adb shell dumpsys activity activities | grep -E "mResumedActivity|mFocusedActivity"
```

### 현재 화면 뷰 계층 구조

```bash
./adb shell dumpsys activity top -p com.truefriend.neosmartarenewal
```

### 앱 강제 종료

```bash
./adb shell am force-stop com.truefriend.neosmartarenewal
```

---

## 딥링크 (Deep Link)

### 라우팅 구조

앱은 단일 Activity(`MTSMainActivity`) 구조이며, `KeyOpenScreenNo`와 `KeyOpenScreenData` Intent Extra로 화면을 전환함.

### 화면 이동 명령어

```bash
# 실행 중인 앱에 딥링크 전송 (FLAG_ACTIVITY_CLEAR_TOP)
./adb shell "am start -n com.truefriend.neosmartarenewal/.ui.main.MTSMainActivity -a ActionDeepLink -f 0x04000000 --es KeyOpenScreenNo <화면번호> --es KeyOpenScreenData ''"
```

> **주의**: 앱이 완전히 종료된 상태에서는 로그인을 먼저 거쳐야 하므로, **앱이 실행 중인 상태**에서 사용해야 함.

### 등록된 Deep Link 스킴

| 스킴 | 용도 |
|---|---|
| `neosmartarenewal://content` | 앱 전용 딥링크 |
| `eFriendNeoSmarta://content` | eFriend 딥링크 |
| `mtsrn://` | MTS 리뉴얼 딥링크 |
| `twitter://neosmarta` | 트위터 연동 |
| `kakao26fc446e1f90f865beb265c88b38466c://` | 카카오 연동 |

---

## 화면 번호 (ScreenNo)

### 주요 화면

| 화면번호 | 화면 |
|---|---|
| `0001` | 홈 |
| `0100` | 시작 화면 |
| `0000` | 그룹 메뉴 |
| `6300` | 로그인 |

### 연금 (Pension)

| 화면번호 | 화면 |
|---|---|
| `7100` | 연금 (하위) |
| `7200` | 연금 (하위) |
| **`7201`** | **퇴직연금 ETF/리츠 주문** |
| `7203` | 연금 (하위) |

### 은행 이체 (Banking Transfer)

| 화면번호 | 화면 |
|---|---|
| `2701` | 은행 이체 |
| `2706` | 이체 약관 동의 (타행) |

### CFD (차이상품)

| 화면번호 | 화면 |
|---|---|
| `2803` | CFD 메인 |
| `2804` | CFD (하위) |
| `2807` | CFD 주문 |

### 선물옵션 (Future/Option)

| 화면번호 | 화면 |
|---|---|
| `1802` | 선물옵션 시세 |
| `1807` | 선물옵션 잔고 |

### 계좌 비밀번호 팝업

| 화면번호 | 화면 |
|---|---|
| `rn0000S09` | 계좌 비밀번호 팝업 |
| `rn0000S10` | 은행 계좌 비밀번호 팝업 |
| `rn0000S11` | 은행 계좌 비밀번호 풀팝업 |

---

## 테스트된 화면 이동

### 퇴직연금 ETF/리츠 주문 화면 (7201)

```bash
# 앱이 실행 중인 상태에서
./adb shell "am start -n com.truefriend.neosmartarenewal/.ui.main.MTSMainActivity -a ActionDeepLink -f 0x04000000 --es KeyOpenScreenNo 7201 --es KeyOpenScreenData ''"
```

결과: `KODEX 국고채10년액티브 (471230)` 종목 주문 화면이 열리며, 개인형IRP 계좌 선택됨.

---

## 팝업 닫기

### 화면 좌표 기반

"오늘 그만보기" 버튼 위치: 화면 좌하단, 대략 **x=110, y=1860** (1080x2340 해상도 기준).

```bash
./adb shell input tap 110 1860
```

### 팝업 시스템 구조

#### 팝업 관련 클래스

| 클래스 | 경로 | 역할 |
|---|---|---|
| `FormPopup` | `com/truefriend/corelib/view/FormPopup.java` | 메인 팝업 뷰 클래스. `dismiss()`, `hidePopup()`, `onBackPressed()`, `sendCloseMassage()` 메서드 제공 |
| `PopupInterface` | `com/truefriend/corelib/baseintrf/PopupInterface.java` | 팝업 인터페이스. `dismiss()` 및 `onBackPressed()` 정의 |
| `PopupState` | `com/truefriend/corelib/shared/data/PopupState.java` | 팝업 상태 열거형. `SHOW` / `HIDE` |

#### 이벤트 배너(프로모션 팝업) 구조

| 클래스 | 역할 |
|---|---|
| `HomeEventBanner` | 이벤트 배너 목록 컨테이너 |
| `HomeEventBannerItem` | 개별 배너 아이템 (id, type, imageUrl, title, message, **seenType**, actionType, actionUrl, actionData, buttonName) |

#### SeenType (팝업 숨김 정책)

배너의 `seenType` 필드로 "오늘 그만보기" 동작을 제어함.

| 값 | 의미 |
|---|---|
| `EMPTY` | 기본값 (항상 표시) |
| `NOTUSED` | 숨김 기능 미사용 |
| `NEVER` | 다시 보지 않기 |
| **`ONEDAY`** | **오늘 그만보기 (다음 날 다시 표시)** |
| `ONEWEEK` | 일주일간 보지 않기 |

#### 팝업 숨김 상태 저장

SharedPreferences 키:

```
event.notdisplay.<bannerId>    — 개별 배너 숨김 저장
event.notdisplay.cnt           — 숨김 배너 카운트
```

소스: `ConfigUtil.EVENT_NOTICE_NOTDISPLAY` 및 `ConfigUtil.EVENT_NOTICE_NOTDISPLAYCNT`

---

## 로그인 및 세션 키

### 이중 인증 시스템

앱은 **두 가지 인증 방식**을 병행 사용:
1. **레거시 세션 키** — 전통적인 한국투자 TR 트랜잭션용 (PacketManager)
2. **OAuth 토큰** — 최신 REST API용 (Retrofit)

### OAuth 토큰 (AuthGatewayServerTokenLoginResponse)

로그인 게이트웨이 서버에서 발급되는 토큰:

| 필드 | 설명 |
|---|---|
| `access_token` | API 요청용 액세스 토큰 |
| `refresh_token` | 만료 시 갱신용 리프레시 토큰 |
| `token_type` | 토큰 타입 (Bearer) |
| `expires_in` | 토큰 유효기간 (초) |
| `scope` | OAuth 권한 범위 |

### 세션 정보 (SessionInfo)

`com/truefriend/corelib/shared/data/SessionInfo.java` — 싱글톤, 메모리에만 보관

| 필드 | 설명 |
|---|---|
| `m_strSessionKey` | 레거시 TR 트랜잭션용 세션 키 |
| `m_strAutoKey` | 자동화 요청용 키 |
| `m_strCustomNo` | 고객번호 (SecManager로 암호화 저장) |
| `m_sScreenNo` | 세션 식별용 화면번호 |
| `m_sUniqueKey` | 고유 디바이스/사용자 키 |
| `m_strClientUniqID` | 클라이언트 고유 ID |
| `m_strConnectID` | 연결 ID |
| `m_isLoginUser` | 로그인 상태 (boolean) |
| `loginType` | 로그인 방식 (인증서, 비밀번호, 생체 등) |
| `m_strUserName` | 사용자 이름 |
| `m_strUserNumber` | 사용자 번호 |
| `m_strPassword` | 비밀번호 |
| `m_strEncPassword` | 암호화된 비밀번호 |
| `m_strOrderPwd` | 주문 비밀번호 |
| `m_strOrderPwdDigit` | 주문 비밀번호 자리수 |
| `m_strBranchNo` | 영업점 번호 |
| `m_strCustType` | 고객 유형 |
| `m_strJuminNo` | 주민등록번호 |
| `m_strServerName` | 서버 이름 |
| `m_strServerNo` | 서버 번호 |
| `m_strCertDN` | 인증서 DN |
| `m_strCertPassword` | 인증서 비밀번호 |
| `m_strCertEncPassword` | 암호화된 인증서 비밀번호 |
| `m_nLoginResult` | 로그인 결과 코드 |
| `m_nUserPermission` | 사용자 권한 레벨 |

### SSO 정보 (OpenSSOInfo)

| 필드 | 설명 |
|---|---|
| `m_sScreenNo` | 화면 번호 (SessionInfo.m_sScreenNo와 동일) |
| `m_sUniqueKey` | 고유 세션 키 |
| `m_sUserID` | 사용자 ID |
| `m_sCertDN` | 인증서 DN |
| `m_sCertPW` | 인증서 비밀번호 |
| `m_sOtherApp` | SSO 연동 앱 식별자 |

### 로그인 상태 플래그

| 플래그 | 설명 |
|---|---|
| `m_isLoginUser` | 메인 로그인 상태 |
| `m_isCertLogin` | 인증서 로그인 |
| `m_isCloudCertLogin` | 클라우드 인증서 로그인 |
| `m_isUserPwdEnc` | 비밀번호 암호화 여부 |
| `m_mobileOneLogin` | MobileOne 인증 |
| `m_bioCertLogin` | 생체인증 로그인 |
| `m_isCertSkipped` | 인증서 스킵 여부 |
| `m_isSemiCust` | 반개인 고객 여부 |
| `m_isSiseOnly` | 시세만 조회 가능 여부 |

### 서버 접속 정보

```
메인 서버 IP:  (서버에서 동적 할당)
LBS 서버:     210.183.254.161, 114.203.46.21, 210.124.177.81 (포트 2001)
Form 서버:    210.107.75.103 (포트 9010)
서버 포트:    9600
연결 타임아웃: 2000ms
```

---

## API 엔드포인트

### Base URL

| 환경 | URL |
|---|---|
| Production | `https://cls.kis.finance/logs` |
| Staging | `https://test-cls.kis.finance/logs` |
| Development | `https://dev-cls.kis.finance/logs` |

> 위 URL은 로깅/헬스체크용. 실제 거래 API는 별도의 Base URL 사용 (Retrofit + kotlinx.serialization)

### 인증 헤더

- REST API: `Authorization: Bearer <access_token>`
- 시세 조회: `MarketPriceAuth: Bearer` (커스텀 헤더)

---

### 홈/상품 API (HomeClientService)

| 메서드 | 엔드포인트 | 파라미터 | 응답 타입 | 설명 |
|---|---|---|---|---|
| GET | `home/preload` | `csno` | `HomeBaseResponse` | 홈 프리로드 |
| GET | `home/domestic` | `tks, rr, et, etc, ets, etf` | `HomeDomesticResponse` | 국내 홈 |
| GET | `home/overseas` | `tks, rr, rc, rl, et, etc, ets, etf, th, affiliation-type` | `HomeOverseasResponse` | 해외 홈 |
| GET | `home/products` | `hts-id` | `HomeProductPensionResponse` | 전체 상품 (연금 포함) |
| GET | `home/products/pension-products/{tabKey}/{filterKey}` | - | `IrpEtfReitsProductResponse` | **연금 ETF/리츠 상품** |
| GET | `home/products/pension-products/{tabKey}/{filterKey}` | - | `IrpFundProductResponse` | 연금 펀드 상품 |
| GET | `home/products/pension-products/{tabKey}/{filterKey}` | - | `IrpBondProductResponse` | 연금 채권 상품 |
| GET | `home/products/pension-products/{tabKey}/{filterKey}` | - | `IrpFixedInterestProductResponse` | 연금 확정이자 상품 |
| GET | `home/products/pension-products/{tabKey}/{filterKey}` | - | `IrpElbProductResponse` | 연금 ELB 상품 |
| GET | `home/products/pension-products/{tabKey}/{filterKey}` | - | `IndEtfProductResponse` | 개별 ETF 상품 |
| GET | `home/products/self-select-isa/{tabKey}` | `hts-id` | 다양 (ELS, 펀드, 채권, 주식ETF) | ISA 자유선택 상품 |
| GET | `home/products/fixed-rate-domestic-products/{tabKey}` | `filter, hts-id` | 다양 | 국내 확정수익 상품 |
| GET | `home/products/fixed-rate-overseas-products/{tabKey}` | `filter, hts-id` | 다양 | 해외 확정수익 상품 |
| GET | `home/products/high-yield-products/{tabKey}` | - | 다양 | 고수익 상품 |
| GET | `home/domestic/etf-ranking/{id}` | `chip, filter, sorter` | `HomeEtfResponse` | 국내 ETF 랭킹 |
| GET | `home/overseas/etf-ranking/{id}` | `chip, filter, sorter, accountAuth` | `HomeEtfResponse` | 해외 ETF 랭킹 |
| GET | `home/domestic/realtime-ranking/{id}` | - | `HomeRankResponse` | 국내 실시간 랭킹 |
| GET | `home/domestic/subscription/{id}` | - | `SubscriptionDetailResponse` | 청약 상세 |
| GET | `home/domestic/theme/{id}` | - | `HomeThemeResponse` | 국내 테마 |
| GET | `home/overseas/theme/{period}` | - | `HomeOverseasThemeResponse` | 해외 테마 |
| GET | `home/overseas/target-banner` | `hts-id` | `HomeTargetBannerResponse` | 해외 타겟 배너 |
| GET | `global/bottom-banner` | `csno, hts-id, is-skip, tr-call-date` | `HomeEventBannerResponse` | 이벤트 배너 |
| GET | `global/quick-menu/indices` | - | `HomeQuickIndexResponse` | 지수 빠른 메뉴 |
| POST | `global/ad-id` | Body: `HomeAdidRequest` | `HomeAdidResponse` | AD ID 등록 |

### 자산/거래 API (H2 패키지)

| 메서드 | 엔드포인트 | 설명 |
|---|---|---|
| GET | `mts-product/v1/rp/{tab}/{symbol}/status` | RP 상태 조회 |
| GET | `mts-product/v1/rp/products` | RP 상품 목록 |
| GET | `mts-product/v1/rp/automatic/guide/interest/{symbol}` | 자동적립 관심 종목 |
| GET | `mts-product/v1/rp/automatic/{symbol}/request-status` | 자동적립 신청 상태 |
| POST | `mts-product/v1/rp/automatic/product` | 자동적립 상품 신청 |
| POST | `mts-product/v1/rp/automatic/product/join` | 자동적립 가입 |
| GET | `mts-trading/v1/dividends/domestic` | 국내 배당금 |
| GET | `mts-trading/v1/dividends` | 배당금 목록 |
| GET | `mts-asset/v1/dividend/schedules` | 배당 일정 |
| GET | `mts-asset/v1/dividend/schedules/{dividendId}` | 배당 일정 상세 |
| POST | `mts-asset/v1/overseas-stock/tax-saving/intro` | 세금절약 소개 |
| POST | `mts-asset/v1/overseas-stock/tax-saving/loss-stocks` | 손실 종목 조회 |
| POST | `mts-asset/v1/overseas-stock/tax-saving/reservation-status` | 양도세 예약 상태 |
| POST | `mts-asset/v1/overseas-stock/tax-saving/sell-recommend` | 매도 추천 |
| POST | `mts-asset/v1/overseas-stock/tax-saving/reservation-order` | 양도세 예약 주문 |
| GET | `mts-asset/v1/overseas-stock/transfer-tax` | 양도세 이체 |
| GET | `mts-asset/v1/banking/institution/all` | 은행 기관 목록 |
| GET | `compute` | 자산 계산 |
| GET | `service-status` | 서비스 상태 |

### 검색 API

| 메서드 | 엔드포인트 | 설명 |
|---|---|---|
| GET | `search/integrated` | 통합 검색 |
| GET | `search/suggest` | 검색 자동완성 |

### 외부 연동 API

| 메서드 | 엔드포인트 | 설명 |
|---|---|---|
| GET | `other-orgs/joined-status` | 타기관 가입 상태 |
| POST | `other-orgs/joined-status` | 타기관 가입 상태 갱신 |
| GET | `asset/invest` | 투자 자산 |
| GET | `asset/bank/realtime` | 실시간 은행 자산 |
| POST | `users/signed-up` | 가입 사용자 |
| GET | `linkage/open-banking` | 오픈뱅킹 연동 |
| GET | `linkage/org/{code}/assets` | 기관별 자산 |
| POST | `linkage/private-auth/sign-request` | 개인인증 서명 요청 |
| POST | `linkage/private-auth/asset-details-v2` | 자산 상세 v2 |
| POST | `/v1/mip/biz/status` | MIP 비즈니스 상태 |
| POST | `/v1/mip/biz/open` | MIP 비즈니스 열기 |
| POST | `/v1/mip/biz/result` | MIP 비즈니스 결과 |
| POST | `api/v1/requests/koinse/user/push` | KOINSE 푸시 요청 |

---

## 연금 ETF/리츠 상품 데이터 구조

### IrpEtfReitsProduct

| 필드 | 타입 | 설명 |
|---|---|---|
| `type` | String | 상품 유형 |
| `symbol` | String | 종목 코드 (예: 471230) |
| `exchangeCodeForLanding` | String | 거래소 코드 |
| `nationForLanding` | String | 국가 코드 |
| `name` | String | 상품명 |
| `price` | String | 현재가 |
| `diff` | String | 전일 대비 |
| `rate` | String | 등락률 |
| `sign` | SignType | 부호 (UP/DOWN/LIMIT_UP 등) |
| `imageUrl` | String | 상품 이미지 URL |

---

## TR 트랜잭션 (레거시 주문 프로토콜)

### 개요

실제 주문 실행은 **TR(Transaction)** 코드 기반의 레거시 KIS 거래 프로토콜을 사용. `com/truefriend/neosmartarenewal/lib/network/tr/` 디렉토리에 517개의 TR 클래스가 존재.

### TR 명명 규칙

| 접두사 | 의미 |
|---|---|
| `TAC` | **주문/거래** 관련 (U=업데이트/주문, R=조회) |
| `TBN` | 뱅킹 |
| `TCI` | 계좌 정보 |
| `H0ST` | 실시간 시세 |
| `H0GS` | 계좌 조회 |
| `HMCM` | 신용/마진 |
| `HKST` | 주식 |
| `TRP` | 연금(Pension) |
| `TLN` | 대출 |
| `TPF` | 펀드 |
| `TOP` | 공통 (로그인, OTP 등) |
| `TIV` | 투자 |

### 주요 주문 TR (TAC 시리즈)

| TR 코드 | 타입 | 필드명 (어노테이션) | 설명 |
|---|---|---|---|
| TAC6621U | 주문 | - | 주식 매수 주문 |
| TAC6622U | 주문 | - | 주식 매도 주문 |
| TAC6623U | 주문 | - | 주문 정정 |
| TAC6624U | 주문 | - | 주문 취소 |
| TAC3206U | 주문 | - | ETF 매수 주문 |
| TAC3012U | OTP | `MBLE_OTP_ERR_CD, OTP_SQNO, OTP_CRTN_KEY, OTP_CRTN_ENPT_KEY, ISUC_DT, OTP_VNDR_KIND_CD, VNDR_OTP_NO, OTP_PIN_ERR_RCNT, OTP_PIN_MAX_ERR_PMSS_CNT, OTP_EXPR_PNOT_YN` | OTP 인증 |
| TAC3013U | 주문 | - | 주문 비밀번호 검증 |
| TAC6680U~TAC6686U | 주문 | - | 연금 계좌 주문 관련 |

### 연금 관련 TR (TRP 시리즈)

| TR 코드 | 설명 |
|---|---|
| TRP6120R ~ TRP6131R | 연금 상품 조회 |
| TRP5521U | 연금 주문 |
| TRP5526R, TRP5527R | 연금 잔고 조회 |
| TRP6515R, TRP6531R, TRP6532R | 연금 계좌 정보 |
| TRP6542R, TRP6548R, TRP6549R | 연금 한도 조회 |
| TRP6562R | 연금 상세 정보 |
| TRP1022R | 연금 상품 검색 |

### TR 공통 필드 (어노테이션 기반 직렬화)

TR 클래스들은 `@e9.c(name=..., outputID=...)` 어노테이션으로 필드명을 매핑. outputID 포맷:

```
예: TAC3012U → outputID = "TAC3012UO2" (OutBlock2)
예: TOP0005U → outputID = "OutBlock1" (ParsingConstants.OUTBLOCK1_ID)
```

### TOP0005U 공통 응답 필드 (은행 거래 공통)

| 필드명 | 설명 |
|---|---|
| `API_TRAN_ID` | 거래 ID |
| `API_TRAN_DTM` | 거래 일시 |
| `RSP_CODE` | 응답 코드 |
| `RSP_MESSAGE` | 응답 메시지 |
| `CATEGORY` | 카테고리 |
| `SUCCESS_CODE` | 성공 코드 |
| `RESULT_CODE` | 결과 코드 |
| `RESULT_MESSAGE` | 결과 메시지 |
| `BANK_TRAN_ID` | 은행 거래 ID |
| `BANK_TRAN_DATE` | 은행 거래 일자 |
| `BANK_CODE_TRAN` | 은행 코드 |
| `RESULT_RSP_CODE` | 결과 응답 코드 |
| `RESULT_RSP_MESSAGE` | 결과 응답 메시지 |

### 공통 데이터 (MTSCommonData)

모든 TR 클래스는 `MTSCommonData`를 상속하며, 다음 공통 필드를 포함:

```
screenNo    — 화면 번호
accountNo   — 계좌 번호  
orderQty    — 주문 수량
orderPrice  — 주문 가격
```

---

## API 요청 시 인증 흐름

### 레거시 TR 요청

```
1. SessionInfo.m_strSessionKey + m_strAutoKey → PacketManager에서 패킷 헤더에 포함
2. screenNo (예: 7201) → TR 헤더의 SCREEN_NO 필드
3. m_strCustomNo → 고객번호 필드
```

### REST API 요청

```
1. Authorization: Bearer <access_token>
2. 시세 조회 시: MarketPriceAuth: Bearer (별도 헤더)
3. Query 파라미터: hts-id, csno 등
```

---

## APK 디컴파일

```bash
# APK 경로 확인
./adb shell pm path com.truefriend.neosmartarenewal

# APK 가져오기
./adb pull /data/app/com.truefriend.neosmartarenewal-GJaSGCapgVy5L9ZOkZU2Cw==/base.apk ./neosmart_base.apk

# jadx로 디컴파일
jadx -d ./jadx_output --no-res ./neosmart_base.apk
```

### 주요 소스 파일

| 파일 | 내용 |
|---|---|
| `com/truefriend/neosmartarenewal/util/deeplink/AppDeepLinkHandler.java` | 딥링크 라우팅 로직 (KeyOpenScreenNo, KeyOpenScreenData) |
| `com/truefriend/neosmartarenewal/util/deeplink/AppDeepLinkActivity.java` | 딥링크 수신 Activity |
| `com/truefriend/corelib/shared/data/MainMenuItem.java` | 메뉴/화면 번호 상수 |
| `com/truefriend/corelib/shared/data/OpenScreenInfo.java` | 화면 전환 정보 구조체 |
| `com/truefriend/corelib/shared/data/SessionInfo.java` | 세션/인증 정보 (모든 로그인 키) |
| `com/truefriend/corelib/shared/data/OpenSSOInfo.java` | SSO 세션 정보 |
| `com/truefriend/corelib/util/FormScreenUtil.java` | 화면 번호 유틸 (Pension, CFD, Banking 등) |
| `com/truefriend/corelib/analytics/AnalyticsOpenScreenManager.java` | 화면 전환 분석 로깅 |
| `com/truefriend/corelib/view/FormPopup.java` | 팝업 뷰 관리 |
| `com/truefriend/neosmartarenewal/ui/home/data/SeenType.java` | 배너 숨김 정책 열거형 |
| `com/truefriend/neosmartarenewal/ui/home/data/HomeEventBannerItem.java` | 이벤트 배너 아이템 |
| `com/truefriend/neosmartarenewal/lib/network/HomeClientService.java` | 홈/상품 Retrofit API 인터페이스 |
| `com/truefriend/neosmartarenewal/lib/network/tr/` | TR 트랜잭션 클래스 디렉토리 (517개) |
| `com/truefriend/neosmartarenewal/ui/mydata/data/response/AuthGatewayServerTokenLoginResponse.java` | OAuth 토큰 응답 |
| `com/truefriend/kisenterpriselog/data/remote/HttpClient.java` | KIS 엔터프라이즈 로그 API |
| `com/truefriend/neosmartarenewal/lib/repository/init/ServerHealthManagerInitializer.java` | Retrofit 초기화 설정 |
| `H2/` (최상위 패키지) | REST API 서비스 인터페이스 모음 |

---

## 루팅 기기에서 실제 데이터 읽기 검증 결과

### ✅ 전체 확인 결과: 모든 데이터 읽기 가능 (Magisk 루팅)

```bash
# 접근 명령어
./adb shell "su -c 'cat /data/data/com.truefriend.neosmartarenewal/shared_prefs/<파일명>'"
```

---

### SharedPreferences — main_config.xml (로그인/세션)

| 키 | 값 (실제) | 설명 |
|---|---|---|
| `login.session.userid` | `yxcvkr` | 로그인 사용자 ID |
| `login.session.userpw` | `VgRfUaEXFs7y8AnX2oWQ3fBA9WqO7ox1Vu5xIyswMJY=` | 암호화된 비밀번호 (AES) |
| `login.auto.userid` | `yxcvkr` | 자동로그인 ID |
| `login.auto.userpw` | `VgRfUaEXFs7y8AnX2oWQ3fBA9WqO7ox1Vu5xIyswMJY=` | 암호화된 자동로그인 비밀번호 |
| `login.cert.encpassword` | `bdba6786d0122a39...` (256+ hex) | 인증서 암호화 비밀번호 |
| `common.Login.LastType` | `CERTIFICATION` | 마지막 로그인 방식 (인증서) |
| `common.keep_cert_login` | `true` | 인증서 로그인 유지 |
| `common.keep_pwd` | `true` | 비밀번호 저장 |
| `autologin.autologin` | `1` | 자동로그인 활성화 |
| `autologin.certlogout` | `1` | 인증서 자동로그아웃 사용 |
| `autologin.certlogouttime` | `360` | 자동로그아웃 시간 (분) = 6시간 |
| `login.input.saveid` | `true` | ID 저장 |

### SharedPreferences — main_config.xml (사용자 정보)

| 키 | 값 (실제) | 설명 |
|---|---|---|
| `etc.cert.cert.dn` | `cn=조윤배,ou=HTS,ou=신한투자증권,ou=증권,o=SignKorea,c=KR` | 인증서 DN |
| `&USER_CERT_DN` | `cn=조윤배,ou=HTS,ou=신한투자증권,ou=증권,o=SignKorea,c=KR` | 사용자 인증서 DN |
| `&PERSON_CORP` | `01` | 개인/법인 구분 (01=개인) |
| `&CDDEDD_NO_MORE_POPUP_TODAY_CSNO` | `000518893` | **고객번호** |
| `&SAVED_ADID` | `fec5e731-ff1e-41eb-b76f-ab74b3a1ebab` | 광고 ID |
| `&PENSION_AGREEMENT_CHK` | `1` | 연금 약관 동의 |

### SharedPreferences — main_config.xml (팝업 제어)

| 키 | 값 (실제) | 설명 |
|---|---|---|
| `homePopup:673` | `20260426` | 홈 팝업 #673 오늘 그만보기 날짜 |
| `&CDDEDD_NO_MORE_POPUP_TODAY` | `20260426` | CDDEDD 팝업 오늘 그만보기 날짜 |
| `&VIEW_TOTAL_EVENT_POPUP` | `N` | 전체 이벤트 팝업 표시 여부 |

> **팝업 닫기 원리**: `homePopup:<id>` 키에 날짜 문자열(예: `20260426`)을 저장. 앱이 실행 시 오늘 날짜와 비교하여 같으면 팝업 표시 안 함. `adb shell "su -c 'am broadcast -a android.intent.action.DATE_CHANGED'"`로 날짜 변경 시 팝업 재등장.

### SharedPreferences — main_config.xml (화면/설정)

| 키 | 값 | 설명 |
|---|---|---|
| `common.last.screen` | `7201` | 마지막 화면 = 퇴직연금 ETF/리츠 주문 |
| `common.last_screen` | `0001` | 마지막 메인 화면 = 홈 |
| `system.version` | `002.023` | 앱 내부 버전 |
| `&CHECK_CALLGATE` | `Y` | 콜게이트 체크 완료 |

### SharedPreferences — common.xml (OAuth 토큰)

| 키 | 값 | 설명 |
|---|---|---|
| `authGatewayServerAccessToken` | `MqjEVDAb7kfJzr0i...` (암호화된 Base64) | **OAuth 액세스 토큰** (AES 암호화) |
| `authGatewayServerRefreshToken` | `Ck+maKkXNov7EWNJ...` (암호화된 Base64) | **OAuth 리프레시 토큰** (AES 암호화) |
| `homeInterestId` | `dakmqaaHYXTxJBu1KxZy4A==` | 관심 종목 ID (암호화) |
| `SplashScreenData` | `{"backgroundColor":"#235BED",...}` | 스플래시 화면 설정 JSON |

> **중요**: OAuth 토큰은 AES로 암호화되어 SharedPreferences에 저장. 복호화하려면 앱 내부의 `&SECTION_SECURE_PUBLIC_KEY`와 짝이 되는 Private Key가 필요하거나, Frida로 메모리에서 평문 토큰을 직접 덤프해야 함.

### SharedPreferences — main_config.xml (보안 키)

| 키 | 값 |
|---|---|
| `&SECTION_SECURE_PUBLIC_KEY` | `30820122300d06092a864886f70d01010105...` (RSA 공개키, HEX 인코딩) |
| `common.push.id` | `bkgD8TFolkm+BrCq00bbTV6xAxHSCERU356qCmkhc6I=` |
| `common.gcm.push.id` | `dF6tC89cS-G0hrrt0Fc9LZ:APA91bGhfSfqWYrhFqulppQtbZIwykB4GImniJjLaCy1ZNpOS2hYFCljxRnMeUOjArKE1V3hiDpdeLx4CpSvVUz7ZmW6TLpKXbhXZmoqTi9GB7HATaBr76c` (FCM 토큰) |

### DataStore (Protobuf) — 암호화된 데이터

DataStore 파일은 `files/datastore/` 디렉토리에 protobuf 형식으로 저장, **AES-CBC 암호화** 적용.

| 파일 | 포맷 | 내용 |
|---|---|---|
| `authorization_token.preferences_pb` | `{"data":"<base64>","iv":[...]}` | 인증 토큰 (AES 암호화) |
| `account_info.preferences_pb` | `{"data":"<base64>","iv":[...]}` | 계좌 정보 (AES 암호화) |
| `my_info_settings.preferences_pb` | `{"data":"<base64>","iv":[...]}` | 내 정보 설정 (AES 암호화) |
| `authorization_preferences.preferences_pb` | protobuf | `login_type: CertificateLoggedIn` |
| `menu_list.preferences_pb` | protobuf (236KB) | 메뉴 목록 |

### NTIUSP 에이전트 — 푸시/디바이스 정보

| 키 | 값 |
|---|---|
| `PUSH_DEVICE_SERIAL` | `S17771799519471` |
| `PUSH_DEVICE_ID` | `ffffffff-bf4f-8efc-ffff-ffffdfa7c77e` |
| `PUSHCODE_01` | `134CA1E037CDE041DF77D21270B8C48E564C3058EF2F305B171A78578107544C` |
| `fet_4000` | `DE804710B5EE27E9B0F4AF873BA514843FC989C32D17340EB898360AB08CE497` |

### 기타 파일

| 파일 | 내용 |
|---|---|
| `files/I3G_AUTH_31` | Widevine 인증 데이터 (Base64 암호화) |
| `files/I3G_AUTH_CODE_31` | Widevine 인증 코드 |
| `files/I3G_WIDEVINE_ID` | Widevine 디바이스 ID |

---

### 데이터 읽기 스크립트 (루팅 기기)

```bash
#!/bin/bash
# NeoSmart 데이터 추출 스크립트
APP=com.truefriend.neosmartarenewal
SU="su -c"

echo "=== SharedPreferences ==="
for f in $(adb shell "$SU ls /data/data/$APP/shared_prefs/"); do
  echo "--- $f ---"
  adb shell "$SU cat /data/data/$APP/shared_prefs/$f"
  echo ""
done

echo "=== DataStore ==="
for f in $(adb shell "$SU ls /data/data/$APP/files/datastore/"); do
  echo "--- $f ---"
  adb shell "$SU cat /data/data/$APP/files/datastore/$f" | xxd | head -20
  echo ""
done

echo "=== Auth Files ==="
adb shell "$SU cat /data/data/$APP/files/I3G_AUTH_31"
echo ""
adb shell "$SU cat /data/data/$APP/files/I3G_AUTH_CODE_31"
```

---

### SSL Pinning 테스트 결과

mitmproxy로 실제 테스트 완료. **SSL Pinning 존재 확인**.

```
Client TLS handshake failed. The client does not trust the proxy's certificate 
for mts.kis.finance (OpenSSL Error: 'ssl/tls alert certificate unknown')
```

| 항목 | 결과 |
|---|---|
| 트래픽 프록시 유입 | ✅ 확인 |
| `mts.kis.finance` (18.64.8.101) 연결 | ✅ 확인 |
| SSL Pinning | ❌ **차단** — 인증서 신뢰 불가 |
| 감지된 도메인 | `mts.kis.finance`, `acgnnu-launches.appsflyersdk.com`, `crashlyticsreports-pa.googleapis.com`, `app-measurement.com` |

**다음 단계**: Frida로 SSL Pinning 우회 후 실제 API 페이로드 캡처 필요.

---

### 암호화 구조 요약

```
SharedPreferences
├── main_config.xml
│   ├── login.session.userpw ← AES 암호화 (Base64)
│   ├── login.cert.encpassword ← AES 암호화 (Hex)
│   ├── &SECTION_SECURE_PUBLIC_KEY ← RSA 공개키 (Hex)
│   └── 기타 설정값 (평문)
├── common.xml
│   ├── authGatewayServerAccessToken ← AES 암호화 (Base64)
│   └── authGatewayServerRefreshToken ← AES 암호화 (Base64)
└── DataStore (files/datastore/)
    ├── authorization_token.preferences_pb ← AES-CBC (JSON {data, iv})
    ├── account_info.preferences_pb ← AES-CBC (JSON {data, iv})
    └── my_info_settings.preferences_pb ← AES-CBC (JSON {data, iv})
```

> **평문으로 복호화하려면**: Frida로 앱 메모리에서 `SessionInfo` 싱글톤 객체의 필드를 직접 읽거나, AES 키를 KeyStore에서 추출해야 함.

---

## Frida + SSL Pinning 우회 시도 기록

### 환경 설정

| 항목 | 값 |
|---|---|
| Frida Client | v17.9.1 (`/Users/y/Library/Python/3.9/bin/frida`) |
| Frida Server | `frida-server-17.9.1-android-arm64` (`/data/local/tmp/frida-server`) |
| mitmproxy | v12.2.2 (`/opt/homebrew/bin/mitmdump`) |
| mitmproxy CA 인증서 | `~/.mitmproxy/mitmproxy-ca-cert.cer` (기기에 설치 완료) |
| SSL Bypass 스크립트 v1 | `/Users/y/Downloads/platform-tools/ssl_bypass.js` |
| SSL Bypass 스크립트 v2 | `/Users/y/Downloads/platform-tools/ssl_bypass_v2.js` (native hook 포함) |

### Frida 실행 명령어

```bash
# Frida Server 시작
./adb shell "su -c '/data/local/tmp/frida-server -D &'"

# 실행 중인 앱 목록 확인
/Users/y/Library/Python/3.9/bin/frida-ps -Ua

# 앱에 attach (PID 필요)
/Users/y/Library/Python/3.9/bin/frida -U -p <PID> -l ssl_bypass.js

# spawn 모드 (동작하지 않음 — "unable to pick a payload base")
/Users/y/Library/Python/3.9/bin/frida -U -f com.truefriend.neosmartarenewal -l ssl_bypass.js
```

### mitmproxy + Frida 동시 실행 절차

```bash
# 1. 프록시 설정
./adb shell "settings put global http_proxy 192.168.1.216:8080"

# 2. mitmproxy 백그라운드 실행
mitmdump -p 8080 --set block_global=false -w capture.flow &

# 3. 앱 실행
./adb shell am start -n com.truefriend.neosmartarenewal/.ui.splash.SplashActivity

# 4. Frida attach
PID=$(/Users/y/Library/Python/3.9/bin/frida-ps -Ua | grep truefriend | awk '{print $1}')
/Users/y/Library/Python/3.9/bin/frida -U -p $PID -l ssl_bypass_v2.js

# 5. 프록시 복원
./adb shell "settings put global http_proxy :0"
```

### SSL Pinning 분석 결과

#### 시도 1: OkHttp 레벨 후킹 (ssl_bypass.js)

| 후킹 대상 | 성공 여부 | 결과 |
|---|---|---|
| `okhttp3.CertificatePinner.check()` | ✅ Hook 성공 | TLS 차단됨 |
| `javax.net.ssl.SSLContext.init()` | ✅ Hook 성공 | TLS 차단됨 |
| `okhttp3.OkHttpClient$Builder.certificatePinner()` | ✅ Hook 성공 | TLS 차단됨 |
| `android.webkit.WebViewClient.onReceivedSslError()` | ✅ Hook 성공 | TLS 차단됨 |

Frida 로그에서 모든 hook 적용 확인:
```
[+] OkHttp CertificatePinner hooked
[+] SSLContext.init hooked
[+] HostnameVerifier bypass ready
[+] OkHttpClient.Builder.certificatePinner hooked
[+] WebViewClient SSL error hooked
[*] SSL Pinning Bypass script loaded successfully
```

그러나 여전히:
```
Client TLS handshake failed. The client does not trust the proxy's certificate
for mts.kis.finance (ssl/tls alert certificate unknown)
```

#### 시도 2: Native SSL + 시스템 TrustManager 후킹 (ssl_bypass_v2.js)

| 후킹 대상 | 성공 여부 | 결과 |
|---|---|---|
| `com.android.org.conscrypt.TrustManagerImpl.verifyChain()` | 시도 | 스크립트 로드 실패 |
| `android.security.net.config.RootTrustManager.checkServerTrusted()` | 시도 | 스크립트 로드 실패 |
| `android.net.http.X509TrustManagerExtensions.checkServerTrusted()` | 시도 | 스크립트 로드 실패 |
| `libssl.so` → `SSL_CTX_set_verify()` | 시도 | 스크립트 로드 실패 |
| `libssl.so` → `SSL_get_verify_result()` | 시도 | 스크립트 로드 실패 |

**스크립트 로드 실패 원인**: "Failed to load script: the connection is closed"
→ 앱이 Frida attach를 감지하고 프로세스를 종료하거나, 프록시 설정으로 인해 Frida 통신이 차단됨.

#### Frida Spawn 모드

```
Failed to spawn: unable to pick a payload base
```

→ Magisk 환경 + Android 10에서 Frida spawn이 동작하지 않음.

### 결론: SSL Pinning 다중 레이어 구조

이 앱은 **3중 SSL Pinning**을 적용하고 있음:

```
Layer 1: OkHttp CertificatePinner (Java)       → Frida로 Hook 성공, 그러나 불충분
Layer 2: Android System TrustManager (Java)     → Hook 시도했으나 스크립트 로드 실패
Layer 3: Native BoringSSL / libssl.so (C/C++)   → Hook 시도했으나 스크립트 로드 실패
```

추가로 **xshield** 보안 모듈이 난독화된 native 라이브러리에서 인증서 검증을 수행할 가능성 높음.

### 감지된 전체 도메인 목록

| 도메인 | IP | 용도 |
|---|---|---|
| `mts.kis.finance` | 18.64.8.28, 18.64.8.101, 18.64.8.117 | **메인 API 서버** |
| `kbox.kis-static.finance` | 18.64.8.86 | 정적 리소스 (이미지, 배너) |
| `search.kis.finance` | 13.225.117.111 | 검색 API |
| `cls.kis.finance` | 13.225.134.112 | 로깅/분석 |
| `cdn.nshc.net` | 23.76.153.8 | 가짜앱 탐지 업데이트 |
| `new.real.download.dws.co.kr` | 140.150.20.24 | 시세 데이터 다운로드 (HTTP 평문!) |
| `acgnnu-launches.appsflyersdk.com` | — | AppsFlyer 분석 (DNS 해석 불가) |
| `acgnnu-cdn-settings.appsflyersdk.com` | — | AppsFlyer 설정 |
| `acgnnu-conversions.appsflyersdk.com` | — | AppsFlyer 전환 추적 |
| `acgnnu-inapps.appsflyersdk.com` | — | AppsFlyer 인앱 이벤트 |
| `firebase-settings.crashlytics.com` | — | Crashlytics 설정 |
| `crashlyticsreports-pa.googleapis.com` | — | Crashlytics 리포트 |
| `firebaseremoteconfigrealtime.googleapis.com` | 142.250.21.95 | Firebase Remote Config |
| `app-measurement.com` | — | Google Analytics |
| `connectivitycheck.gstatic.com` | 142.251.23.94 | 연결 확인 |
| `play.googleapis.com` | 216.239.38.223 | Play Services |
| `youtubei.googleapis.com` | 216.239.38.223 | YouTube API |
| `i.ytimg.com` | 172.217.209.119 | YouTube 썸네일 |
| `graph.facebook.com` | 157.240.215.16 | Facebook SDK |
| `api.account.xiaomi.com` | 8.219.11.240 | Xiaomi 계정 |
| `data.mistat.intl.xiaomi.com` | — | Xiaomi 분석 |

> **중요 발견**: `new.real.download.dws.co.kr`은 **HTTP 평문**으로 통신. 이 경로로는 SSL 없이 트래픽 캡처 가능.

---

## 다음에 이어서 할 작업 (시작점)

### 옵션 A: LSPosed + TrustMeAlready (추천)

Magisk에 LSPosed 프레임워크를 설치하고, TrustMeAlready 또는 JustTrustMe 모듈로 시스템 전체 SSL Pinning을 우회.

```
1. Magisk Manager → 모듈 → LSPosed 설치
2. LSPosed 활성화 후 리부팅
3. TrustMeAlready 모듈 설치 (또는 JustTrustMe)
4. LSPosed에서 NeoSmart 앱에 대해 모듈 활성화
5. mitmproxy 실행 + 프록시 설정
6. 앱 실행 → TLS 핸드셰이크 성공 확인
7. 실제 API 페이로드 캡처
```

### 옵션 B: Frida Gadget (APK 재패키징)

APK에 frida-gadget.so를 주입하여 앱 초기화 시점부터 hook 적용.

```
1. apktool로 APK 디컴파일
2. lib/arm64-v8a/에 frida-gadget.so 복사
3. smali 코드에서 System.loadLibrary("frida-gadget") 호출 추가
4. 재빌드 + apksigner로 서명
5. 설치 후 Frida로 연결하여 SSL bypass 스크립트 로드
```

> **단점**: 서명이 달라져서 기존 계정/인증서 데이터가 초기화될 수 있음.

### 옵션 C: Frida로 SessionInfo 메모리 덤프 (우회 대안)

SSL Pinning을 우회하는 대신, Frida로 실행 중인 앱의 메모리에서 평문 세션 키를 직접 추출.

```javascript
// SessionInfo 필드 덤프 스크립트
Java.perform(function() {
    var SessionInfo = Java.use('com.truefriend.corelib.shared.data.SessionInfo');
    var instance = SessionInfo.getInstance();
    console.log('SessionKey: ' + instance.m_strSessionKey.value);
    console.log('AutoKey: ' + instance.m_strAutoKey.value);
    console.log('CustomNo: ' + instance.m_strCustomNo.value);
    console.log('ScreenNo: ' + instance.m_sScreenNo.value);
    console.log('UniqueKey: ' + instance.m_sUniqueKey.value);
    console.log('ConnectID: ' + instance.m_strConnectID.value);
    console.log('LoginUser: ' + instance.m_isLoginUser.value);
});
```

### 작업 재개 체크리스트

- [ ] LSPosed 설치 (Magisk 모듈)
- [ ] TrustMeAlready 또는 JustTrustMe 모듈 설치
- [ ] mitmproxy로 API 트래픽 캡처 성공
- [ ] `mts.kis.finance` 엔드포인트 실제 request/response 기록
- [ ] 퇴직연금 ETF/리츠 주문(7201) 화면에서 실제 주문 API payload 캡처
- [ ] OAuth access_token 복호화 또는 메모리 덤프
- [x] Frida로 SessionInfo 메모리 덤프
- [x] 캡처한 데이터로 tf.md 최종 업데이트

---

## 2026-04-26 추가 진행 로그

### 1) 현재 환경 재확인

```bash
./adb devices -l
/Users/y/Library/Python/3.9/bin/frida-ps -Ua
mitmdump --version
./adb shell "su -c 'ls /data/adb/modules'"
```

확인 결과:

- ADB 연결 정상: `824e1b4` (Redmi Note 7)
- Frida attach 가능한 앱 PID 확인: `com.truefriend.neosmartarenewal` = `18303`
- mitmproxy 설치 정상: `12.2.2`
- Magisk 모듈: `playintegrityfix`, `zygisk_shamiko`만 존재 (LSPosed 미설치)

### 2) Frida로 SessionInfo 런타임 덤프 (완료)

스크립트: `/Users/y/Downloads/platform-tools/frida_runtime_dump.js`

실행:

```bash
/Users/y/Library/Python/3.9/bin/frida -U -p 18303 -l /Users/y/Downloads/platform-tools/frida_runtime_dump.js
```

로그: `/Users/y/Downloads/platform-tools/frida_runtime_dump.log`

주요 덤프 결과:

| 필드 | 값 |
|---|---|
| `m_strCustomNo` | `MDAwNTE4ODkz` (Base64 디코드: `000518893`) |
| `m_strClientUniqID` | `2026042614483601956851953362yxcvkr` |
| `m_strConnectID` | `5336` |
| `m_isLoginUser` | `false` |
| `m_strUserName` | `조윤배` |

### 3) 추가 관찰 사항

- Frida attach 직후 프로세스 종료 재현됨 (`Process terminated`) — anti-Frida 또는 런타임 무결성 체크 가능성 높음.
- attach 유지 시간이 짧아 OkHttp 헤더/URL 후킹 로그(`Authorization`, `MarketPriceAuth`)는 확보 실패.
- 따라서 체크리스트의 `OAuth access_token 메모리 덤프`, `mts.kis.finance request/response`, `7201 주문 payload`는 여전히 미완료.

### 4) 다음 실행 우선순위 (실행용)

1. LSPosed 설치 후 재부팅
2. TrustMeAlready(또는 JustTrustMe) 활성화
3. mitmproxy 재시험 → `mts.kis.finance` 핸드셰이크 성공 확인
4. 7201 화면 주문 시퀀스 재현 후 request/response 및 payload 캡처
5. 필요 시 Frida는 attach 대신 Gadget 방식으로 전환

---

## 2026-04-26 추가 진행 로그 (2차)

### 1) LSPosed/TrustMeAlready 실제 적용 상태

실행한 주요 명령:

```bash
# LSPosed(Vector) 설치
adb shell "su -c 'magisk --install-module /data/local/tmp/LSPosed-zygisk-release.zip'"

# TrustMeAlready 설치 (adb install 실패 우회)
adb shell "su -c 'pm install -r /data/local/tmp/TrustMeAlready-v1.0.apk'"

# 공식 LSPosed v1.9.2로 교체
adb shell "su -c 'magisk --install-module /data/local/tmp/LSPosed-v1.9.2-zygisk-release.zip'"

# 충돌 모듈 제거
adb shell "su -c 'rm -rf /data/adb/modules/zygisk_vector'"

# denylist 해제
adb shell "su -c 'magisk --denylist rm com.truefriend.neosmartarenewal'"
```

확인 결과:

- `/data/adb/modules`에 `zygisk_lsposed` 활성 상태 확인
- `mfsx.xposed.trustmealready` APK 설치 완료
- LSPosed 로그에서 대상 앱 로딩 및 후킹 확인

`neosmart_launch.log` 기준 핵심 로그:

- `Loading Vector/Xposed for com.truefriend.neosmartarenewal`
- `TrustMeAlready loading: com.truefriend.neosmartarenewal`
- `TrustMeAlready loaded! Hooked 29 methods`

### 2) MITM 재검증 결과 (핸드셰이크는 여전히 실패)

캡처 파일:

- `/Users/y/Downloads/platform-tools/mitm_after_lsposed192.log`
- `/Users/y/Downloads/platform-tools/mitm_after_lsposed192.flow` (159KB)

확인된 점:

- `mts.kis.finance`, `search.kis.finance`, `cls.kis.finance`, `apis.kis.finance`로의 CONNECT 시도는 대량 확인
- 하지만 TLS는 여전히 `ssl/tls alert certificate unknown`로 차단
- 즉, **도메인 레벨 트래픽 유입은 성공했으나 request/response payload 복호화는 실패**

예시 로그:

```text
Client TLS handshake failed. The client does not trust the proxy's certificate for mts.kis.finance
Client TLS handshake failed. The client does not trust the proxy's certificate for search.kis.finance
Client TLS handshake failed. The client does not trust the proxy's certificate for cls.kis.finance
```

### 3) 현재 상태 요약

- LSPosed/TrustMeAlready 설치 및 앱 스코프 적용: 완료
- 앱 프로세스 기동 안정화(denylist 정책 재적용 포함): 완료
- KIS 계열 도메인 접속 시도 캡처: 완료
- TLS 복호화 성공(실제 req/res, 주문 payload): 미완료

### 3-1) 운영 제약 (중요)

실사용 확인 결과, **DenyList를 비활성화하면 NeoSmart 앱 실행이 불안정/불가**.

따라서 현재 운영 상태는 아래로 고정:

- `magisk --denylist enable` (enforced)
- `com.truefriend.neosmartarenewal` 및 `com.truefriend.neosmartarenewal:agent` DenyList 등록

이 제약 때문에 해당 앱 프로세스에는 Zygisk 기반 LSPosed 모듈 적용이 차단되어,
TrustMeAlready만으로는 `mts.kis.finance` MITM 복호화가 성립하지 않음.

### 4) 다음 우선 실행

1. TrustMeAlready 외에 native 계층 우회 모듈/방법 추가 (예: Frida Gadget + native SSL hook)
2. `mts.kis.finance` 대상 우회 성공 후 7201 화면에서 실제 주문 직전 요청 재캡처
3. OAuth access_token 평문은 메모리 후킹 경로로 우선 확보

---

## 2026-04-26 추가 진행 로그 (3차, 사용자 요청 정리)

요청: "LSPosed/TrustMeAlready 삭제"

실행:

```bash
adb shell "su -c 'pm uninstall mfsx.xposed.trustmealready; pm uninstall org.lsposed.manager'"
adb shell "su -c 'rm -rf /data/adb/modules/zygisk_lsposed'"
adb reboot
```

검증:

- `/data/adb/modules` = `playintegrityfix`, `zygisk_shamiko` (LSPosed 제거 확인)
- 패키지 목록에서 `org.lsposed.manager`, `mfsx.xposed.trustmealready` 미검출
- `DenyList is enforced` 유지 + NeoSmart 2개 프로세스 등록 유지
- NeoSmart 앱 실행 확인 (`pidof com.truefriend.neosmartarenewal` 정상)

---

## 2026-04-26 추가 진행 로그 (4차, 정적 분석 완료 — TRP5521U Payload 스펙 도출)

### TRP5521U 주문 Payload 전체 스펙

TR 코드 `TRP5521U` (퇴직연금 ETF/리츠 주문) 의 전체 request payload 필드 구조를 정적 분석으로 완전 파악.

#### 주문 전송 호출 체인

```
[7201 화면 주문 버튼]
  → C3185v.W() (ViewModel)
    → J.invokeSuspend() (coroutine lambda)
      → Trp5521uInputData 필드값 읽기
      → DataManager.setDataValue() × 19회 (각 필드를 TRP5521U 입력으로 설정)
      → DataManager.RequestTranData("TRP5521U")
      → B.g() / B.j() (응답 콜백 → TRP5521U 객체 리플렉션 생성)
      → A.invoke() (최종 결과 처리 → 성공/실패 메시지)
```

#### Trp5521uInputData 필드 → 서버 전송 필드 매핑

J.java `invokeSuspend()`에서 각 필드는 `setDataValue(block=0, tranID="TRP5521U", outputID, fieldName, recordSeq=0, value)` 로 전송됨.
`outputID`와 `fieldName`은 xshield 암호화 문자열로, `g5.AbstractC3795a.a()` 또는 `DexterBuilderExtentionKt.D()`로 복호화됨.

| # | Java 필드 | 타입 | 의미 | 값 출처 | 비고 |
|---|-----------|------|------|---------|------|
| 1 | `f26603a` (a) | String | 주문구분코드 | `s0.f26559a` (수수료 체크 결과) → "01"(신규) 또는 "02"(재신청) | G.java에서 설정, Z()에서 업데이트 |
| 2 | `f26604b` (b) | String | 세율 (TAX_RATE) | `r9.getTrfx()` | TRP5526R 응답에서 취득 |
| 3 | `f26605c` (c) | String | 계좌번호 (ACCOUNT_NO) | `kotlin.text.StringsKt.T(r13, ...)` 포맷팅 | 마스킹 포맷 적용 |
| 4 | `d` | String | 고객주민번호 (CUST_RNCNO) | `r15.getCustRncno()` | TBMM214R 응답 |
| 5 | `e` | String | 처리순번 (PRCS_SEQ) | `r15.getPrcsSeq()` | TBMM214R 응답 |
| 6 | `f` | String | 종목코드 (PDNO) | `r8.element` (UI 입력) | K0.getCode() 또는 사용자 선택 |
| 7 | `g` | String | 위탁수수료부과코드 (OTCO_FEE_LEVY_CD / OTCO_PRCS_DVSN_CODE) | `r15.getOtcoFeeLevyCd()` 또는 `I0.getOtcoPrcsDvsnCode()` | "00"(당사), "01"(전사), "02"(전사무료) |
| 8 | `h` | String | 계좌상품코드 (ACNT_PRDT_CD) | `r1.a0` (ViewModel 필드) | C3185v에서 설정 |
| 9 | `i` | String | 기본값 | `r1.f26737k0` (= "0") | 초기값 "0" 고정 |
| 10 | `f26606j` (j) | String | 납입금액 (DOTN_AMT) | `r15.getDotnAmt()` 또는 `r9.getDotnAmt()` | TBMM214R / TRP5526R 응답 |
| 11 | `f26607k` (k) | String | 납입수령방법코드 (PAYB_RCVE_MTHD_CD) | `r15.getPaybRcveMthdCode()` → "01"(이메일) 또는 "02"(우편) | `.a(String)` setter로 설정 |
| 12 | `f26608l` (l) | String | 과세기준금액 (TXBS_AMT) | `r9.getTxbsAmt()` | TRP5526R 응답 |
| 13 | `f26609m` (m) | String | 계좌번호 원본 (RAW_ACNT_NO) | `r1.f26729Y` (ViewModel) | 포맷팅 전 원본 |
| 14 | `f26610n` (n) | String | 의뢰일자 (RQST_DT) | `r15.getRqstDt()` | TBMM214R 응답 |
| 15 | `f26611o` (o) | String | 개인연금전입잔액 (OPNT_CVYC_RSDX) | `r9.getOpntCvycRsdx()` | TRP5526R 응답 |
| 16 | `f26612p` (p) | String | 연금저축구분 (PENSION_SAVINGS_TYPE) | `r14` (이전 context에서 계산) | |
| 17 | `f26613q` (q) | String | 수령자주소/이메일 (RCVP_ADDR) | `r15.getRcvpAddr()` / `r15.getCustAddr()` / `r15.getCustEmalAddr()` | `.e(String)` setter, 이메일/우편 토글 |
| 18 | `f26614r` (r) | String | 계좌비밀번호 (ACNO_PSWD) | `r18.element` (UI 입력) | 사용자 입력 |
| 19 | `f26615s` (s) | String | 소득공제금액 (PFLS_AMT) | `r9.getPflsAmt()` | TRP5526R 응답 |

#### I0.otcoPrcsDvsnCode 값

| enum | code | 의미 |
|------|------|------|
| OUR_COMPANY | "00" | 당사 |
| OUR_COMPANY_FREE | "00" | 당사 무료 |
| ALL_COMPANY | "01" | 전사 |
| ALL_COMPANY_FREE | "02" | 전사 무료 |

#### TAC668xU TR 클래스 요약 (생체인증 관련, 주문 직접 payload 아님)

| TR 코드 | 필드명 | 의미 |
|---------|--------|------|
| TAC6680U | BIO_CTFC_TLG_CHAS_NO, BIO_CTFC_FIDO_CHAS_NO | 생체인증 챌린지 번호 ( Telegraph/FIDO ) |
| TAC6681U | BIO_CTFC_ERLM_RSLT_CD, BIO_CTFC_CTFE_LGTH, BIO_CTFC_CTFE_INFO | 생체인증 등록결과코드, 인증정보 길이/내용 |
| TAC6682U | BIO_CTFC_TLG_CHAS_NO, BIO_CTFC_FIDO_CHAS_NO, TRSF_TR_UNIQ_NO | 생체인증 챌린지 + 거래고유번호 |
| TAC6683U | BIO_CTFC_CTFC_RSLT_CD, FIDO_USGE_RESP_MSG_LGTH, FIDO_USGE_RESP_MSG, TRSF_TR_UNIQ_NO | 생체인증 결과코드 + FIDO 응답메시지 |
| TAC6684U | BIO_CTFC_TLG_CHAS_NO, BIO_CTFC_FIDO_CHAS_NO | 생체인증 챌린지 (인증 완료용) |
| TAC6685U | BIO_CTFC_CCLC_PRCS_RSLT_CD | 생체인증 취소처리결과코드 |
| TAC6686U | List\<TAC6686UList\>, BIO_CTFC_CTFC_RSLT_CD, FIDO_USGE_RESP_MSG_LGTH, FIDO_USGE_RESP_MSG, NCFR_RE_ERLM_OBJT_YN | 생체인증 결과 (리스트 포함) + 비대면 재등록 대상 여부 |

TAC668xU는 주문 payload가 아닌 **생체인증(FIDO/BIO) 전용 TR**로, 주문 전 인증 단계에서 사용됨.
TRP5521U 주문 요청 시 생체인증이 필요한 경우 TAC668xU → TRP5521U 순서로 호출됨.

#### 응답 처리 (TRP5521U)

- B.java: `TRP5521U.class` 리플렉션으로 응답 파싱
  - `@e9.c(name="...", outputID="...")` annotation에서 필드명 추출
  - `@e9.a(tranID="...")` annotation에서 TR ID 추출
- A.java: 응답 처리
  - `isSuccess()` → 성공 시 빈 문자열, 실패 시 `getMessage()` 표시
  - MTSCommonData 기반 클래스: `messageCode`, `message`, `messageDisplay`, `errorType`, `hasData`, `isSuccess`

#### 서버 전송 필드명 복원 상태

서버로 전송되는 실제 필드명은 `g5.AbstractC3795a.a()` / `DexterBuilderExtentionKt.D()` 로 암호화되어 있어,
J.java의 `setDataValue()` 호출에서 3번째/4번째 인자로 전달됨:

```java
// 예: r11 = r1.f26609m (계좌번호 원본)
r5.setDataValue(0, "TRP5521U", outputID_decrypted, fieldName_decrypted, 0, r11)
```

필드명 복호화를 위해서는 런타임에 `g5.AbstractC3795a.a()` / `DexterBuilderExtentionKt.D()` 함수를
Frida로 hook하여 암호화된 문자열을 평문으로 변환하는 과정이 필요.
현재 Frida background hook이 활성 상태이며, 7201 화면에서 주문 실행 시 constructor param 값과 함께 캡처 가능.

#### Frida Background 상태

- Frida server: `/data/local/tmp/frida-server` 실행 중
- `frida_trp5521_dump.js`: Trp5521uInputData 생성자 hook + OkHttp Request.Builder.build hook 활성
- 백그라운드 프로세스 PID 87273으로 실행 중
- 로그: `/Users/y/Downloads/platform-tools/frida_trp5521_dump.log`
- 7201 화면 진입 후 주문 버튼 탭 시 `=== Trp5521uInputData CONSTRUCTOR called ===` 로그 출력 예상
