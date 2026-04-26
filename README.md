# yquant-mtsa

Android AccessibilityService를 사용하여 한국투자증권 MTS 앱을 자동화하는 프로젝트입니다.

## 📋 개요

내부 Android 앱을 통해 MTS 앱을 직접 제어하는 방식으로, 외부 웹서버 없이 안정적이고 빠른 자동화를 제공합니다.

### 기술 스택

- **Kotlin** - Android 개발 언어
- **AccessibilityService** - MTS 앱 제어 (화면 이동, UI 상호작용)
- **Deep Link** - MTS 앱 화면 전환 (KeyOpenScreenNo)
- **Broadcast Receiver** - 내부 컴포넌트 간 통신

### 현재 구현된 기능

- ✅ 접근성 서비스 기반 MTS 앱 제어
- ✅ 7201 화면으로 자동 이동
- ✅ 딥링크 기반 화면 전환
- ✅ 계좌 전환 기능
- ✅ 잔고 추출 및 파싱

---

## 🏗️ 프로젝트 구조

```
yquant-mtsa/
├── app/
│   ├── src/main/
│   │   ├── java/com/yquant/mtsa/
│   │   │   ├── MainActivity.kt                    # 메인 액티비티
│   │   │   ├── NeoSmartAccessibilityService.kt    # 접근성 서비스 (MTS 제어)
│   │   │   ├── ConfigManager.kt                   # 설정 관리
│   │   │   ├── ScreenConfig.kt                    # 화면 설정 데이터 클래스
│   │   │   └── ExtractionRule.kt                  # 데이터 추출 규칙
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml             # 메인 화면
│   │   │   ├── xml/
│   │   │   │   └── accessibility_service_config.xml  # 접근성 서비스 설정
│   │   │   ├── assets/
│   │   │   │   ├── account_switch_rules.json      # 계좌 전환 규칙
│   │   │   │   └── balance_extraction_rules.json  # 잔고 추출 규칙
│   │   │   └── values/                            # 리소스 값
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── scripts/                        # 개발 스크립트
├── gradle/
├── build.gradle.kts                # 프로젝트 빌드 설정
├── settings.gradle.kts             # 프로젝트 설정
├── gradlew                         # Gradle Wrapper (Unix)
├── tf.md                           # MTS 앱 기술 분석 문서
└── ANDROID_README.md               # 상세 사용 가이드
```

---

## 🚀 빠른 시작

### 1. 사전 요구사항

- Android 기기 (API 26+)
- Android SDK (macOS 기본 경로: `/Users/y/Library/Android/sdk`)
- MTS 앱 (com.truefriend.neosmartarenewal) 설치 필요
- ADB (Android Debug Bridge)

### 2. 프로젝트 빌드

```bash
# Debug APK 빌드
./gradlew assembleDebug
```

빌드된 APK: `app/build/outputs/apk/debug/app-debug.apk`

### 3. APK 설치

```bash
# ADB로 설치
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. 접근성 서비스 활성화

1. **설정** → **접근성** → **접근성 서비스**로 이동
2. **yquant-mtsa** 찾기
3. 클릭하여 활성화
4. 권한 허용

### 5. 앱 실행

1. **yquant-mtsa 앱 실행**
2. 접근성 서비스가 활성화되어 있는지 확인
3. 기능 버튼 클릭 (계좌 전환, 7201 이동 등)

---

## 📚 문서

- **[ANDROID_README.md](./ANDROID_README.md)** - 상세 사용 가이드
- **[QUICK_START.md](./QUICK_START.md)** - 빠른 시작 가이드
- **[HYBRID_STRATEGY.md](./HYBRID_STRATEGY.md)** - 하이브리드 전략 문서
- **[BALANCE_TEST_GUIDE.md](./BALANCE_TEST_GUIDE.md)** - 잔고 테스트 가이드
- **[tf.md](./tf.md)** - MTS 앱 기술 분석 문서

---

## 🎯 구현된 기능

### 1. 7201 화면 이동

내부 앱 버튼 클릭 → MTS 앱 7201 화면으로 자동 전환

```kotlin
// 딥링크 사용
Intent().apply {
    action = Intent.ACTION_VIEW
    setClassName("com.truefriend.neosmartarenewal", "com.truefriend.neosmartarenewal.ui.main.MTSMainActivity")
    putExtra("action", "ActionDeepLink")
    putExtra("KeyOpenScreenNo", "7201")
}
```

### 2. 계좌 전환

접근성 서비스를 통해 자동으로 계좌 전환

```kotlin
// 계좌 선택 드롭다운 클릭 후 하단 화살표로 계좌 이동
```

### 3. 잔고 추출

화면에서 잔고 정보를 자동으로 추출하여 파싱

```json
{
  "account": "000518893",
  "cash": "123,456,789",
  "total_evaluation": "987,654,321"
}
```

---

## 🔧 설정

### 계좌 전환 규칙 (`app/src/main/assets/account_switch_rules.json`)

```json
{
  "account_number": "000518893",
  "screen": "7201",
  "steps": [
    {
      "action": "click",
      "target": "account_dropdown"
    },
    {
      "action": "navigate",
      "direction": "down",
      "target": "account_item"
    }
  ]
}
```

### 잔고 추출 규칙 (`app/src/main/assets/balance_extraction_rules.json`)

```json
{
  "screen": "7201",
  "extraction_rules": [
    {
      "field": "cash",
      "selector": "cash_amount"
    },
    {
      "field": "total_evaluation",
      "selector": "total_amount"
    }
  ]
}
```

---

## 🐛 디버깅

### 로그 확인

```bash
# 접근성 서비스 로그
adb logcat | grep MtsaAccessibility

# 전체 로그
adb logcat
```

### 문제 해결

#### 접근성 서비스가 활성화되지 않음
- 설정 > 접근성 > 접근성 서비스에서 yquant-mtsa 찾기
- 활성화 버튼 클릭
- 권한 허용

#### MTS 앱이 시작되지 않음
```bash
adb shell pm list packages | grep neosmartarenewal
```

#### 화면 전환되지 않음
- 로그cat에서 에러 메시지 확인
- MTS 앱이 로그인되어 있는지 확인
- 딥링크 명령어가 올바른지 확인

---

## 📝 다음 단계

1. **로그인 자동화**
   - 랜덤 키패드 OCR
   - 비밀번호 입력

2. **주문 기능**
   - 매수/매도 주문
   - 시장가 주문

3. **REST API 서버** (옵션)
   - 외부 제어용 HTTP 서버
   - yquant-htsa와 호환 API

---

## 📚 참고 자료

- [AccessibilityService 공식 문서](https://developer.android.com/guide/topics/ui/accessibility/service)
- [tf.md](./tf.md) - MTS 앱 기술 분석 문서

---

## 📄 라이선스

MIT License
