# MTSA Prototype

AccessibilityService를 사용하여 MTS 앱을 자동화하는 프로토타입 앱입니다.

## 🎯 프로토타입 목표

**기능**: 내 앱 버튼 클릭 → 5초 대기 → MTS 앱에서 7201 화면으로 전환

이것이 성공하면 AccessibilityService가 MTS 앱을 제어할 수 있다는 것이 증명됩니다.

## 📱 기능

1. **7201 화면으로 이동 버튼**: 클릭 후 5초 뒤에 MTS 앱이 7201 화면으로 전환됩니다
2. **접근성 서비스 상태 확인**: 앱 시작 시 접근성 서비스 활성화 여부를 표시합니다

## 🔧 사전 요구사항

- Android 기기 (API 26+)
- MTS 앱 (com.truefriend.neosmartarenewal)이 설치되어 있어야 함
- Android SDK (macOS 기본 경로: `/Users/y/Library/Android/sdk`)

## 📦 설치 방법

### 1. 프로젝트 빌드

```bash
cd /Users/y/Github/yquant-mtsa/android-prototype

# Debug APK 빌드
./gradlew assembleDebug
```

빌드된 APK는 다음 위치에 생성됩니다:
```
app/build/outputs/apk/debug/app-debug.apk
```

### 2. APK 설치

```bash
# ADB로 설치
/Users/y/Downloads/platform-tools/adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 접근성 서비스 활성화

1. **설정** → **접근성** → **접근성 서비스**로 이동
2. **MTSA Prototype** 찾기
3. 클릭하여 활성화
4. 권한 허용

## 🚀 사용 방법

1. **MTSA Prototype 앱 실행**
2. 접근성 서비스가 활성화되어 있는지 확인 (녹색 체크 표시)
3. **"7201 화면으로 이동"** 버튼 클릭
4. 5초 카운트다운 후 MTS 앱이 7201 화면으로 전환됩니다

## 🧪 테스트 방법

### 성공 조건

- [ ] 접근성 서비스 활성화 가능
- [ ] 버튼 클릭 후 5초 카운트다운 표시
- [ ] MTS 앱이 자동으로 7201 화면으로 전환
- [ ] 화면 전환 시 에러 없음

### 실패 시 확인 사항

1. **접근성 서비스 활성화 여부**
   ```bash
   # 설정 > 접근성 > 접근성 서비스에서 확인
   ```

2. **MTS 앱 설치 여부**
   ```bash
   /Users/y/Downloads/platform-tools/adb shell pm list packages | grep neosmartarenewal
   ```

3. **로그 확인**
   ```bash
   /Users/y/Downloads/platform-tools/adb logcat | grep MtsaAccessibility
   ```

## 📂 프로젝트 구조

```
android-prototype/
├── app/
│   ├── src/main/
│   │   ├── java/com/yquant/mtsa/
│   │   │   ├── MainActivity.kt                    # 메인 액티비티
│   │   │   └── NeoSmartAccessibilityService.kt    # 접근성 서비스
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml             # 메인 화면 레이아웃
│   │   │   ├── xml/
│   │   │   │   └── accessibility_service_config.xml  # 접근성 서비스 설정
│   │   │   └── values/
│   │   │       ├── strings.xml
│   │   │       ├── colors.xml
│   │   │       └── themes.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/
│   └── wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
└── local.properties
```

## 🔍 작동 원리

### 1. MainActivity (메인 액티비티)

- 버튼 클릭 시 5초 카운트다운 시작
- 카운트다운 완료 후 브로드캐스트 전송
- 접근성 서비스 활성화 상태 확인

### 2. NeoSmartAccessibilityService (접근성 서비스)

- 브로드캐스트 수신
- MTS 앱 실행 상태 확인
- 딥링크를 사용하여 7201 화면으로 이동

### 3. 딥링크 명령어

```kotlin
Intent().apply {
    action = Intent.ACTION_VIEW
    setClassName("com.truefriend.neosmartarenewal", "com.truefriend.neosmartarenewal.ui.main.MTSMainActivity")
    putExtra("action", "ActionDeepLink")
    putExtra("KeyOpenScreenNo", "7201")
    putExtra("KeyOpenScreenData", "")
}
```

## 🐛 디버깅

### 로그 보기

```bash
# 접근성 서비스 로그
adb logcat | grep MtsaAccessibility

# 전체 로그
adb logcat
```

### 문제 해결

#### 접근성 서비스가 활성화되지 않음

- 설정 > 접근성 > 접근성 서비스에서 MTSA Prototype 찾기
- 활성화 버튼 클릭
- 권한 허용

#### MTS 앱이 시작되지 않음

- MTS 앱이 설치되어 있는지 확인
```bash
adb shell pm list packages | grep neosmartarenewal
```

#### 7201 화면으로 전환되지 않음

- 로그cat에서 에러 메시지 확인
- MTS 앱이 로그인되어 있는지 확인
- 딥링크 명령어가 올바른지 확인

## 📝 다음 단계 (하이브리드 방식 Phase 2)

프로토타입이 성공하면 다음 기능들을 추가합니다:

1. **로그인 자동화**
   - 랜덤 키패드 OCR
   - 비밀번호 입력

2. **주문 기능**
   - 매수/매도 주문
   - 시장가 주문

3. **잔고 조회**
   - 계좌 잔고 확인
   - 보유 종목 목록

4. **REST API 서버**
   - 외부 제어용 HTTP 서버
   - yquant-htsa와 호환 API

## 📚 참고 자료

- [AccessibilityService 공식 문서](https://developer.android.com/guide/topics/ui/accessibility/service)
- [tf.md](../tf.md) - MTS 앱 기술 분석 문서

## 📄 라이선스

MIT License
