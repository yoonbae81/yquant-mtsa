# AGENTS.md

이 문서는 이 저장소에서 작업하는 에이전트와 로컬 개발자를 위한 작업 노트입니다. 사용자용 개요와 실행 방법은 `README.md`를 우선 확인합니다.

## 로컬 도구 경로

- ADB/fastboot 로컬 경로: `/Users/y/Github/yquant-mtsa/tools/android-tools`
- ADB 실행 파일: `/Users/y/Github/yquant-mtsa/tools/android-tools/adb`
- Homebrew Java 홈 경로: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- Homebrew Java 실행 파일 경로: `/opt/homebrew/opt/openjdk@17/bin/java`

```bash
export PATH="/Users/y/Github/yquant-mtsa/tools/android-tools:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

`openjdk`(25)를 제거한 경우에도 `openjdk@17`이 설치되어 있으면 Gradle, apktool, jadx를 계속 사용할 수 있습니다.

## 분석 산출물

- 디컴파일 산출물 경로: `/Users/y/Github/yquant-mtsa/captures/hanatu-apk`

MTS 앱 디컴파일 결과나 화면 캡처 기반 분석이 필요하면 `captures/` 아래 산출물을 먼저 확인합니다.

## 스크린샷 파일

PNG 스크린샷 파일은 저장소 임의 위치에 만들지 말고 `screenshots/` 폴더에 저장합니다. 파일명은 `yyyymmdd-title.png` 형식을 사용하고, `title`은 내용을 알아볼 수 있는 짧은 영문 kebab-case로 작성합니다.

예: `screenshots/20260502-accessibility-settings.png`

## MTS 자동화 방법론

대상 기기의 Android 10 환경에서는 접근성 스크린샷 캡처를 사용할 수 없다는 전제로 작업합니다. 화면 상태 확인이 필요하면 ADB로 스크린샷을 캡처하고, 캡처 이미지를 OCR로 인식해 현재 화면과 문구를 파악합니다.

```bash
adb exec-out screencap -p > screenshots/yyyymmdd-title.png
```

기능 구현 시에는 OCR로 얻은 화면 정보와 `captures/` 아래의 디컴파일 산출물을 함께 분석합니다. 단순히 좌표 `x, y`를 찍어 클릭하는 방식은 화면 크기, 해상도, 앱 업데이트에 취약하므로 우선순위에서 낮게 둡니다. 가능하면 디컴파일 정보에서 화면 구조와 컨트롤 트리를 파악하고, 접근성 노드의 텍스트, 설명, 클래스명, 계층 관계를 기준으로 원하는 컨트롤을 찾아 클릭하는 방식으로 구현합니다.

좌표 기반 클릭은 컨트롤 트리 기반 접근이 불가능하거나 확인용 임시 실험이 필요한 경우에만 제한적으로 사용합니다.

## 한국투자 앱 동작 요건

테스트 중 한국투자 앱이 종료되었거나 테스트를 위해 직접 종료한 뒤 다시 실행하면, 한국투자 앱의 기본 동작에 따라 공동인증서 로그인을 다시 수행해야 합니다.

중요: `com.yquant.mtsa` 앱을 `adb uninstall` 하거나 기기에서 삭제하면, MTSA 앱 환경설정에 저장된 공동인증서 비밀번호/IRP 비밀번호/DC 비밀번호가 함께 삭제됩니다. 계좌 비밀번호/공동인증서 비밀번호 실기 검증 중에는 앱 삭제를 기본 선택지로 쓰지 말고, 우선 `adb install -r ...` 재설치, `am force-stop`, 접근성 서비스 재활성화, 앱 재실행으로 복구를 시도합니다. 정말 삭제가 필요할 때만, 저장된 비밀번호를 다시 입력해야 한다는 점을 전제로 진행합니다.

공동인증서 로그인 자동화 기능은 이미 구현되어 있으므로, 앱 재실행 후 자동화 흐름을 검증할 때는 로그인 단계가 먼저 필요한 상태인지 확인하고 해당 기능을 사용합니다. 이때 접근성 서비스에 `LOGIN` 브로드캐스트를 직접 보내는 우회 경로보다, MTSA 앱을 열고 첫 번째 `[공동인증서 로그인]` 버튼을 실제로 눌러 `MainActivity`의 버튼 핸들러 경로를 타는 방식을 우선합니다. 이 경로는 저장된 공동인증서 비밀번호 확인, 한국투자 앱 실행, 공동인증서 로그인 화면 탐색, 보안 키보드 열기, 비밀번호 입력, 로그인 버튼 클릭까지 포함합니다.

ADB로 실행할 때는 다음 순서로 진행합니다.

```bash
adb shell am start -n com.yquant.mtsa/.MainActivity
adb shell input tap 540 720
```

좌표는 현재 테스트 기기에서 `[공동인증서 로그인]` 버튼 중앙을 누르는 값입니다. 화면 배치가 달라졌다면 먼저 `screenshots/`에 MTSA 앱 화면을 캡처해 버튼 위치를 확인한 뒤 탭합니다. 로그인 성공 여부는 logcat의 `MtsaAccessibility` 태그에서 `공동인증서 로그인이 완료되었습니다.` 메시지로 확인할 수 있습니다.

## 한국투자 앱 디컴파일 매칭 노트

실제 한국투자 앱 산출물은 `captures/hanatu-apk/` 아래를 기준으로 확인합니다. `captures/current_apk_jadx/`는 이 저장소 앱을 디컴파일한 산출물일 수 있으므로, 한국투자 앱 구조를 볼 때 우선 사용하지 않습니다.

### 메인 액티비티와 딥링크

- 패키지명: `com.truefriend.neosmartarenewal`
- 메인 화면 액티비티: `com.truefriend.neosmartarenewal.ui.main.MTSMainActivity`
  - 근거: `captures/hanatu-apk/jadx/resources/AndroidManifest.xml`
  - `MTSMainActivity`는 `exported=true`, `launchMode=singleTask`입니다.
- 외부 딥링크 액티비티: `com.truefriend.neosmartarenewal.util.deeplink.AppDeepLinkActivity`
  - 근거: `captures/hanatu-apk/jadx/resources/AndroidManifest.xml`
  - `mtsrn`, `neosmartarenewal://content`, `eFriendNeoSmarta://content`, `neosmartaf`, `https://neosmtsrenewal.onelink.me`, `https://kis-pension.onelink.me` 등의 VIEW intent filter가 있습니다.
- 딥링크 핸들러는 `ActionDeepLink`, `KeyOpenScreenNo` extra를 사용합니다.
  - 근거: `captures/hanatu-apk/jadx/sources/com/truefriend/neosmartarenewal/util/deeplink/AppDeepLinkHandler.java`
  - 현재 자동화의 `ActionDeepLink + KeyOpenScreenNo` 방식은 디컴파일 근거가 있는 경로입니다.

### 하단 퀵 메뉴/메뉴 버튼

한국투자 메인 화면의 하단 영역은 `activity_mts_main.xml`에 고정 구성으로 선언되어 있습니다.

- `activity_mts_main.xml`
  - `@id/quick_menu`가 `@layout/layout_quick_menu`를 include하며 `Bottom_toBottomOf=parent`로 붙습니다.
  - `@id/layoutQuickJisu`도 같은 하단 위치에 있으며, 지수 티커/퀵 지수 모드로 보입니다.
  - 근거: `captures/hanatu-apk/apktool/res/layout/activity_mts_main.xml`
- `layout_quick_menu.xml`
  - 전체 높이: `@dimen/quick_menu_layout_height`
  - 좌측 메뉴 컨테이너: `@id/llMenu`
  - 좌측 메뉴 폭: `@dimen/quick_menu_icon_type_menu_width_value`
  - 좌측 메뉴 아이콘: `@drawable/ico_tab_menu`
  - 좌측 메뉴 텍스트: `@string/txt_title_menu`
  - 우측 지수 버튼 영역: `@id/llMarketIndex`
  - 우측 지수 텍스트: `@string/quick_menu_market_index`
  - 근거: `captures/hanatu-apk/apktool/res/layout/layout_quick_menu.xml`
- 관련 치수
  - `quick_menu_layout_height = 54dp`
  - `quick_menu_icon_type_menu_width_value = 67dp`
  - 근거: `captures/hanatu-apk/apktool/res/values/dimens.xml`
- 관련 문자열
  - `txt_title_menu = 메뉴`
  - `txt_total_menu = 메뉴`
  - `quick_menu_market_index = 지수`
  - `ticker = 지수`
  - `quick_menu_setting_close = 닫기`
  - `txt_close = 닫기`
  - 근거: `captures/hanatu-apk/apktool/res/values/strings.xml`

자동화 규칙:

- 메뉴 진입은 전역 텍스트 `"메뉴"` 검색보다 좌측 하단 퀵 메뉴 구조를 우선합니다.
- 접근성 노드가 잡히면 `@id/llMenu` 또는 하단 22% 이내, 화면 하단 78% 이후에 있는 `"메뉴"` 텍스트를 클릭합니다.
- 접근성 노드가 불안정하면 디컴파일된 구조를 근거로 전체 화면 기준 좌측 하단, 대략 `x = 화면폭 * 0.09`, `y = 화면높이 - 190px` 부근을 fallback으로 사용합니다. `llMenu` 폭이 `67dp`로 고정되어 있어, 이 좌표는 좌측 메뉴 컨테이너 내부를 겨냥하는 값입니다.
- 메뉴가 보이지 않으면 하단 퀵 메뉴가 `layoutQuickJisu`/지수 티커 상태로 전환되었거나 오버레이에 가려진 상황일 수 있습니다. 이때 우측 하단 닫기성 컨트롤을 먼저 닫고 메뉴 클릭을 재시도합니다.

### 퀵뷰/지수 티커 계열

- `activity_mts_main.xml`의 `@id/layoutQuickJisu`는 하단에 붙은 지수 티커 영역입니다.
  - 내부에 `@id/quickJisuMenu`, `@id/vpQuickJisu`, `@id/llOnMarketIndex`가 있습니다.
  - 메뉴 아이콘은 `quickJisuMenu` 안의 `@drawable/ico_tab_menu_bold`입니다.
- 별도 퀵뷰 액티비티도 존재합니다.
  - 레이아웃: `captures/hanatu-apk/apktool/res/layout/activity_quick_view.xml`
  - 닫기 버튼: `@id/iv_quick_close`
  - 주요 카드: `@id/cv_quick_total_asset`, `@id/cv_quick_stock_index`, `@id/cv_quick_favorites`
  - 관련 문자열: `quick_view_total_asset=총자산`, `quick_view_kospi=코스피`, `quick_view_kosdaq=코스닥`, `quick_view_nasdaq=나스닥`, `quick_view_favorites=관심종목`

자동화 규칙:

- 우측 하단에 `X`, `닫기`, `close` 성격의 노드가 있거나 `iv_quick_close` 류 닫기 버튼이 있으면 오버레이/퀵뷰로 보고 닫을 수 있습니다.
- 단, 일반 탐색 중 무조건 우측 하단 좌표를 누르지 않습니다. 메뉴 버튼이 보이지 않는 경우처럼 목적이 명확할 때만 닫기 fallback을 허용합니다.
- `총자산`, `지수`, `코스피`, `코스닥`, `나스닥` 등은 퀵뷰/티커 후보이므로 메뉴 진입 대상과 혼동하지 않습니다.

### 하단 탭/퀵 메뉴 탭

- 퀵 메뉴의 개별 탭 레이아웃은 `layout_quick_menu_tab.xml`입니다.
  - 탭 루트: `@id/layoutCustomView`
  - 아이콘: `@id/ivTab`
  - 텍스트: `@id/tvTab`
  - 탭 폭: `60dp`, 높이: `@dimen/quick_menu_layout_height`
  - 근거: `captures/hanatu-apk/apktool/res/layout/layout_quick_menu_tab.xml`

자동화 규칙:

- `"현재가"`, `"자산"`, `"퇴직주문"` 등 하단 탭은 사용자 설정/퀵 메뉴 편집에 따라 순서나 노출 여부가 바뀔 수 있습니다.
- 메뉴 버튼(`llMenu`)과 지수 버튼(`llMarketIndex`)은 레이아웃상 양 끝 고정 요소이지만, 중간 탭은 `TabLayout @id/tabContent`에 동적으로 구성됩니다.
- 따라서 중간 탭은 우선 텍스트/노드 기반으로 찾고, 고정 좌표는 기기별 확인이 끝난 fallback으로만 사용합니다.

## 작업 완료 기준

코드 변경 작업은 컴파일이 통과한 뒤 ADB로 실제 기기에 설치하고, 기기에서 올바르게 동작하는 것까지 확인해야 하나의 task가 끝난 것으로 봅니다.

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```
