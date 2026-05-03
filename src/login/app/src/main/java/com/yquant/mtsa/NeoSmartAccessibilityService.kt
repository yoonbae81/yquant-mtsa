package com.yquant.mtsa

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class NeoSmartAccessibilityService : AccessibilityService() {

    private var commandReceiver: BroadcastReceiver? = null
    private lateinit var configManager: ConfigManager
    private var extractionRules: List<ExtractionRule> = emptyList()
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val loginInProgress = AtomicBoolean(false)
    private val lastPasswordEventTime = AtomicLong(0L)
    private val lastPasswordAddedCount = AtomicInteger(0)
    private val lastPasswordRemovedCount = AtomicInteger(0)
    private val currentCommand = AtomicReference<String?>(null)
    private val currentRequestId = AtomicReference<String?>(null)

    private data class OcrTextBox(
        val text: String,
        val bounds: Rect,
    )

    private data class VisibleTextBox(
        val text: String,
        val bounds: Rect,
    )

    private data class ScreenSnapshot(
        val label: String,
        val packageName: String,
        val visibleTexts: List<String>,
        val clickableTexts: List<String>,
    )

    private data class GridRowSnapshot(
        val itemName: String?,
        val sellableQuantity: String?,
        val averagePurchasePrice: String?,
    )

    private data class RetirementAccountTarget(
        val type: String,
        val accountPrefix: String,
        val accountName: String,
    )

    private data class RetirementHoldingSnapshot(
        val accountType: String,
        val accountLabel: String,
        val itemName: String?,
        val ticker: String?,
        val sellableQuantity: String?,
        val averagePurchasePrice: String?,
    )

    private data class VirtualKeypadProbeLayout(
        val digitSlots: List<PointF>,
        val probeSlots: List<PointF>,
        val backspacePoint: PointF,
        val completePoint: PointF,
    )

    private data class AccountPopupDigitNode(
        val digit: Char,
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val parentBounds: Rect?,
    )

    private data class NumericKeypadProbeResult(
        val slotMap: Map<Char, PointF>,
        val slotDigits: List<Char?> = emptyList(),
    )

    private data class FlexibleActionCandidate(
        val label: String,
        val node: AccessibilityNodeInfo?,
        val bounds: Rect?,
        val score: Int,
    )

    private enum class LoginState {
        LOGGED_IN,
        LOGGED_OUT,
        UNKNOWN,
    }

    companion object {
        private const val TAG = "MtsaAccessibility"
        private const val MTS_PACKAGE = "com.truefriend.neosmartarenewal"
        private const val ACTION_NAVIGATE = "com.yquant.mtsa.ACTION_NAVIGATE"
        private const val ACTION_BALANCE_DATA = "com.yquant.mtsa.ACTION_BALANCE_DATA"
        private const val ACTION_LOGIN_STATUS = "com.yquant.mtsa.ACTION_LOGIN_STATUS"
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val TAG_COMMAND_RESULT = "MtsaCommandResult"
        private const val SERVICE_PREFS_NAME = "mtsa_service_state"
        private const val PENDING_COMMAND_KEY = "pending_command"
        private const val PENDING_COMMAND_TIME_KEY = "pending_command_time"
        private const val PENDING_COMMAND_MAX_AGE_MS = 15_000L
        private const val PROBE_STRATEGY_INDEX_KEY = "probe_strategy_index"

        private const val CMD_LOGIN = "LOGIN"
        private const val CMD_CHECK_LOGIN_STATUS = "CHECK_LOGIN_STATUS"

        private const val LOGIN_SCREEN_NO = "6300"
        private const val RETIREMENT_BALANCE_SCREEN_NO = "7202"
        private const val RETIREMENT_ORDER_SCREEN_NO = "7201"

        private val RETIREMENT_ACCOUNT_TARGETS = listOf(
            RetirementAccountTarget(
                type = "IRP",
                accountPrefix = "64923286",
                accountName = "개인형IRP",
            ),
            RetirementAccountTarget(
                type = "DC",
                accountPrefix = "62669151",
                accountName = "DC",
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AccessibilityService created")

        configManager = ConfigManager(this)
        Log.d(TAG, "Login helper initialized")

        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val command = intent?.getStringExtra(EXTRA_COMMAND)
                val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
                Log.d(TAG, "Received command: $command requestId=$requestId")
                handleCommand(command, requestId)
            }
        }

        val filter = IntentFilter(ACTION_NAVIGATE)
        registerReceiver(commandReceiver, filter)

        resumePendingCommandIfNeeded()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            when (it.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    Log.d(TAG, "Window changed: ${it.packageName} / ${it.className}")
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // 상태 전이 확인용으로만 사용하고, 실제 처리는 rootInActiveWindow 재조회 기준으로 진행합니다.
                }

                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    recordPasswordTextChangedEvent(it)
                }

                else -> Unit
            }
        }
    }

    private fun recordPasswordTextChangedEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != MTS_PACKAGE) {
            return
        }

        if (!event.isPassword && event.className?.toString()?.contains("EditText") != true) {
            return
        }

        lastPasswordEventTime.set(event.eventTime)
        lastPasswordAddedCount.set(event.addedCount)
        lastPasswordRemovedCount.set(event.removedCount)
        Log.d(
            TAG,
            "Password text changed: added=${event.addedCount}, removed=${event.removedCount}, text=${event.text}",
        )
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        commandReceiver?.let { unregisterReceiver(it) }
        Log.d(TAG, "AccessibilityService destroyed")
    }

    private fun handleCommand(command: String?, requestId: String?) {
        currentCommand.set(command)
        currentRequestId.set(requestId)
        when (command) {
            CMD_LOGIN -> {
                clearPendingCommand()
                performLogin()
            }
            CMD_CHECK_LOGIN_STATUS -> {
                clearPendingCommand()
                checkLoginStatus()
            }
            else -> Log.w(TAG, "Unknown command: $command")
        }
    }

    private fun servicePrefs() = getSharedPreferences(SERVICE_PREFS_NAME, MODE_PRIVATE)

    private fun nextProbeStrategyIndex(): Int {
        val prefs = servicePrefs()
        val current = prefs.getInt(PROBE_STRATEGY_INDEX_KEY, 0)
        prefs.edit().putInt(PROBE_STRATEGY_INDEX_KEY, (current + 1) % 3).apply()
        return current
    }

    private fun persistPendingCommand(command: String) {
        servicePrefs().edit()
            .putString(PENDING_COMMAND_KEY, command)
            .putLong(PENDING_COMMAND_TIME_KEY, System.currentTimeMillis())
            .apply()
    }

    private fun clearPendingCommand() {
        servicePrefs().edit()
            .remove(PENDING_COMMAND_KEY)
            .remove(PENDING_COMMAND_TIME_KEY)
            .apply()
    }

    private fun resumePendingCommandIfNeeded() {
        val prefs = servicePrefs()
        val pendingCommand = prefs.getString(PENDING_COMMAND_KEY, null) ?: return
        val savedAt = prefs.getLong(PENDING_COMMAND_TIME_KEY, 0L)
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > PENDING_COMMAND_MAX_AGE_MS) {
            Log.d(TAG, "Dropping stale pending command: $pendingCommand")
            clearPendingCommand()
            return
        }
        Log.d(TAG, "Resuming pending command: $pendingCommand")
        Thread {
            sleep(1500)
            handleCommand(pendingCommand, requestId = null)
        }.start()
    }

    private fun performLogin() {
        if (!loginInProgress.compareAndSet(false, true)) {
            sendLoginStatus("이미 로그인 자동화가 진행 중입니다.", false, finished = true)
            return
        }

        Thread {
            try {
                sendLoginStatus("저장된 공동인증서 비밀번호를 확인하는 중입니다...")
                val certPassword = configManager.loadCredentialSettings().certPassword
                if (certPassword.isBlank()) {
                    sendLoginStatus("저장된 공동인증서 비밀번호를 찾지 못했습니다. 환경설정에서 비밀번호를 입력하세요.", false, finished = true)
                    return@Thread
                }

                sendLoginStatus("공동인증서 로그인 화면으로 이동하는 중입니다...")
                if (!openCertificateLoginScreen()) {
                    sendLoginStatus("공동인증서 로그인 화면을 찾지 못했습니다.", false, finished = true)
                    return@Thread
                }

                sendLoginStatus("공동인증서 정보를 확인하는 중입니다...")
                if (!waitForCertLoaded()) {
                    dumpCertLoginState()
                    sendLoginStatus("공동인증서를 찾을 수 없습니다. 기기에 인증서가 설치되어 있는지 확인하세요.", false, finished = true)
                    return@Thread
                }

                sendLoginStatus("보안 키보드를 여는 중입니다...")
                sleep(1000)
                if (!openCertificatePasswordKeyboard()) {
                    dumpAllWindowsInfo()
                    sendLoginStatus("보안 키보드를 열지 못했습니다.", false, finished = true)
                    return@Thread
                }

                sendLoginStatus("보안 키보드로 비밀번호를 입력하는 중입니다...")
                if (!enterCertificatePassword(certPassword)) {
                    sendLoginStatus("보안 키보드로 비밀번호 입력에 실패했습니다.", false, finished = true)
                    return@Thread
                }

                sendLoginStatus("로그인 버튼 활성화를 기다리는 중입니다...")
                if (!submitCertificateLogin()) {
                    dumpCertLoginState()
                    sendLoginStatus("로그인 버튼을 실행하지 못했습니다. 비밀번호가 올바른지 확인하세요.", false, finished = true)
                    return@Thread
                }

                sendLoginStatus("로그인 결과를 확인하는 중입니다...")
                if (!waitForCertificateLoginResult()) {
                    sendLoginStatus("로그인 성공을 확인하지 못했습니다.", false, finished = true)
                    return@Thread
                }

                sendLoginStatus("공동인증서 로그인이 완료되었습니다.", true, finished = true)
            } catch (e: Exception) {
                Log.e(TAG, "로그인 자동화 중 오류 발생", e)
                sendLoginStatus("로그인 자동화 중 오류가 발생했습니다: ${e.message}", false, finished = true)
            } finally {
                loginInProgress.set(false)
            }
        }.start()
    }

    private fun checkLoginStatus() {
        Thread {
            try {
                sendLoginStatus("한국투자 앱 화면을 확인하는 중입니다...")

                var root = waitForMtsWindow(timeoutMs = 3000)
                if (root == null) {
                    if (!launchMtsApp()) {
                        sendLoginStatus("한국투자 앱을 실행하지 못했습니다.", false, finished = true)
                        return@Thread
                    }
                    root = waitForMtsWindow(timeoutMs = 12000)
                }

                if (root == null) {
                    sendLoginStatus("한국투자 앱 화면 정보를 가져오지 못했습니다.", false, finished = true)
                    return@Thread
                }

                sendLoginStatus("한국투자 메뉴에서 로그인 상태를 읽는 중입니다...")
                var menuRoot = root
                var state = inferLoginStateFromMenu(menuRoot)
                if (state == LoginState.UNKNOWN) {
                    if (!openBottomMenu(menuRoot)) {
                        sendLoginStatus("한국투자 앱 메뉴를 열지 못해 로그인 여부를 판단하지 못했습니다.", false, finished = true)
                        return@Thread
                    }
                    val result = waitForLoginStateFromMenu(timeoutMs = 8000)
                    menuRoot = result.first ?: menuRoot
                    state = result.second
                }

                when (state) {
                    LoginState.LOGGED_IN -> sendLoginStatus("현재 한국투자 앱은 로그인된 상태입니다.", true, finished = true)
                    LoginState.LOGGED_OUT -> sendLoginStatus("현재 한국투자 앱은 로그인되지 않은 상태입니다.", false, finished = true)
                    LoginState.UNKNOWN -> {
                        dumpViewIds(menuRoot, "login-status-unknown")
                        sendLoginStatus("한국투자 앱 메뉴를 확인했지만 로그인 여부를 판단하지 못했습니다.", false, finished = true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "로그인 여부 확인 중 오류 발생", e)
                sendLoginStatus("로그인 여부 확인 중 오류가 발생했습니다: ${e.message}", false, finished = true)
            }
        }.start()
    }

    private fun waitForLoginStateFromMenu(timeoutMs: Long): Pair<AccessibilityNodeInfo?, LoginState> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastRoot: AccessibilityNodeInfo? = null
        var lastState = LoginState.UNKNOWN
        while (System.currentTimeMillis() < deadline) {
            val root = waitForMtsWindow(timeoutMs = 800) ?: lastRoot
            if (root != null) {
                lastRoot = root
                lastState = inferLoginStateFromMenu(root)
                if (lastState != LoginState.UNKNOWN) {
                    return root to lastState
                }
            }
            sleep(300)
        }
        return lastRoot to lastState
    }

    private fun inferLoginStateFromMenu(root: AccessibilityNodeInfo): LoginState {
        val requestLoginNode = firstVisibleNodeByViewId(root, "txt_menu_request_login")
        val requestLoginText = requestLoginNode?.let { nodeText(it) }?.trim().orEmpty()
        if (requestLoginText.contains("로그아웃")) {
            Log.d(TAG, "로그인 상태 판단: txt_menu_request_login=$requestLoginText")
            return LoginState.LOGGED_IN
        }
        if (requestLoginText.contains("로그인")) {
            Log.d(TAG, "로그아웃 상태 판단: txt_menu_request_login=$requestLoginText")
            return LoginState.LOGGED_OUT
        }

        val nonLoginNode = firstVisibleNodeByViewId(root, "txt_menu_logininfo_nonlogin")
        val nonLoginText = nonLoginNode?.let { nodeText(it) }?.trim().orEmpty()
        if (nonLoginText.contains("로그인")) {
            Log.d(TAG, "로그아웃 상태 판단: txt_menu_logininfo_nonlogin=$nonLoginText")
            return LoginState.LOGGED_OUT
        }

        val loginInfoNode = firstVisibleNodeByViewId(root, "txt_menu_logininfo_login")
        val loginInfoText = loginInfoNode?.let { nodeText(it) }?.trim().orEmpty()
        if (loginInfoText.isNotBlank() && requestLoginNode != null) {
            Log.d(TAG, "로그인 상태 후보 판단: txt_menu_logininfo_login=$loginInfoText")
            return LoginState.LOGGED_IN
        }

        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val visibleTexts = nodes.map { nodeText(it).trim() }.filter { it.isNotBlank() }
        return when {
            visibleTexts.any { it == "로그아웃" } -> LoginState.LOGGED_IN
            visibleTexts.any { it == "로그인" } -> LoginState.LOGGED_OUT
            visibleTexts.any { it == "로그인해 주세요" || it == "로그인을 해 주세요" } -> LoginState.LOGGED_OUT
            else -> LoginState.UNKNOWN
        }
    }

    private fun openCertificateLoginScreen(): Boolean {
        val currentRoot = findCertLoginRootInAnyWindow()
        if (currentRoot != null) {
            return true
        }

        var mtsRoot = findMtsRootInAnyWindow()
        if (mtsRoot == null) {
            sendLoginStatus("한국투자 앱을 실행하는 중입니다...")
            if (!launchMtsApp()) {
                return false
            }

            mtsRoot = waitForMtsWindow(timeoutMs = 12000)
            if (mtsRoot == null) {
                return false
            }

            sendLoginStatus("한국투자 앱 마스터 데이터를 받는 중입니다. 10초 후 계속합니다...")
            sleep(10_000)
        }

        return waitForCertificateLoginScreen(timeoutMs = 20000)
    }

    private fun navigateToRetirementOrderScreenAsync() {
        Thread {
            val success = navigateToRetirementOrderScreen()
            Log.d(TAG, "퇴직연금 주문 화면 비동기 진입 결과=$success")
        }.start()
    }

    private fun navigateToRetirementOrderScreen(): Boolean {
        Log.d(TAG, "퇴직연금 주문 화면 진입 시작")
        val currentRoot = waitForMtsWindow(timeoutMs = 3000)
        if (currentRoot != null && isRetirementOrderScreen(currentRoot)) {
            Log.d(TAG, "이미 퇴직연금 주문 화면에 있음")
            return true
        }

        repeat(2) { attempt ->
            try {
                openDeepLinkScreen(RETIREMENT_ORDER_SCREEN_NO)
                Log.d(TAG, "${RETIREMENT_ORDER_SCREEN_NO} 화면으로 이동 명령 전송 완료: attempt=${attempt + 1}")
            } catch (e: Exception) {
                Log.e(TAG, "${RETIREMENT_ORDER_SCREEN_NO} 화면 이동 실패: attempt=${attempt + 1}", e)
            }

            val directRoot = waitForRetirementOrderScreen(timeoutMs = 12000)
            if (directRoot != null) {
                Log.d(TAG, "퇴직연금 주문 화면 도달")
                return true
            }

            val fallbackRoot = waitForMtsWindow(timeoutMs = 3000)
            if (fallbackRoot != null) {
                var latestRoot: AccessibilityNodeInfo? = fallbackRoot
                val clickedRetirementIcon = clickFlexible(fallbackRoot, listOf("퇴직연금"))
                Log.d(TAG, "퇴직연금 아이콘 fallback clicked=$clickedRetirementIcon attempt=${attempt + 1}")
                if (clickedRetirementIcon) {
                    sleep(1800)
                    waitForMtsWindow(timeoutMs = 3000)?.let { afterIconRoot ->
                        latestRoot = afterIconRoot
                        if (clickFlexible(afterIconRoot, listOf("확인", "닫기"))) {
                            Log.d(TAG, "퇴직연금 추천/안내 바텀시트 닫기 성공")
                            sleep(1500)
                            latestRoot = waitForMtsWindow(timeoutMs = 2500) ?: latestRoot
                        }
                    }
                    val iconRoot = waitForRetirementOrderScreen(timeoutMs = 8000)
                    if (iconRoot != null) {
                        Log.d(TAG, "퇴직연금 아이콘으로 화면 도달")
                        return true
                    }
                    latestRoot = waitForMtsWindow(timeoutMs = 2500) ?: latestRoot
                }

                val rootForBottomTab = latestRoot ?: fallbackRoot
                val clicked = clickFlexible(rootForBottomTab, listOf("퇴직주문"), preferBottom = true)
                Log.d(TAG, "퇴직주문 하단 탭 fallback clicked=$clicked attempt=${attempt + 1}")
                if (clicked) {
                    val tabRoot = waitForRetirementOrderScreen(timeoutMs = 8000)
                    if (tabRoot != null) {
                        Log.d(TAG, "퇴직주문 하단 탭으로 화면 도달")
                        return true
                    }
                }
            }

            sleep(1000)
        }

        if (launchMtsApp()) {
            waitForMtsWindow(timeoutMs = 8000)
            try {
                openDeepLinkScreen(RETIREMENT_ORDER_SCREEN_NO)
                Log.d(TAG, "${RETIREMENT_ORDER_SCREEN_NO} 화면으로 이동 명령 재전송 완료")
            } catch (e: Exception) {
                Log.e(TAG, "${RETIREMENT_ORDER_SCREEN_NO} 화면 재이동 실패", e)
            }
            if (waitForRetirementOrderScreen(timeoutMs = 12000) != null) {
                Log.d(TAG, "앱 실행 후 퇴직연금 주문 화면 도달")
                return true
            }
        }

        Log.w(TAG, "퇴직연금 주문 화면 진입 실패")
        return false
    }

    private fun selectRetirementOrderAccount(accountType: String?) {
        Thread {
            val normalizedAccountType = accountType?.trim()?.uppercase().orEmpty()
            val target = RETIREMENT_ACCOUNT_TARGETS.firstOrNull { it.type == normalizedAccountType }
            if (target == null) {
                Log.w(TAG, "Unknown pension account type: $accountType")
                sendBalanceData("오류: 알 수 없는 계좌 유형입니다: ${accountType.orEmpty()}")
                return@Thread
            }

            try {
                Log.d(TAG, "${target.type} 계좌 선택 시작")
                if (!navigateToRetirementOrderScreen()) {
                    sendBalanceData("오류: 퇴직연금 주문 화면으로 이동하지 못했습니다")
                    return@Thread
                }

                val root = waitForMtsWindow(timeoutMs = 8000)
                if (root == null) {
                    sendBalanceData("오류: 퇴직연금 주문 화면을 찾을 수 없습니다")
                    return@Thread
                }

                if (selectRetirementAccount(target)) {
                    sendBalanceData("${target.type} 계좌 선택 완료")
                    Log.d(TAG, "${target.type} 계좌 선택 완료")
                } else {
                    sendBalanceData("오류: ${target.type} 계좌를 선택하지 못했습니다")
                    Log.w(TAG, "${target.type} 계좌 선택 실패")
                }
            } catch (e: Exception) {
                Log.e(TAG, "${target.type} 계좌 선택 중 오류 발생", e)
                sendBalanceData("오류: ${e.message}")
            }
        }.start()
    }

    private fun openAccountPasswordPopupForCurrentOrder() {
        Thread {
            try {
                val popup = openAccountPasswordPopupBlocking(requirePopup = true)
                sendBalanceData("OPEN_ACCOUNT_PASSWORD_POPUP: success=${popup != null}")
            } catch (e: Exception) {
                Log.e(TAG, "계좌 비밀번호 팝업 열기 실패", e)
                sendBalanceData("오류: ${e.message}")
            }
        }.start()
    }

    private fun enterAccountPasswordForCurrentOrder() {
        Thread {
            try {
                val currentRoot = waitForMtsWindow(timeoutMs = 2000)
                if (currentRoot != null && isRetirementOrderFormUnlocked(currentRoot)) {
                    val target = resolveRetirementAccountTargetFromRoot(currentRoot)
                    sendBalanceData("ENTER_ACCOUNT_PASSWORD: success=true account=${target?.type ?: "unknown"} already_unlocked=true")
                    return@Thread
                }

                val popup = openAccountPasswordPopupBlocking(requirePopup = true)
                if (popup == null) {
                    sendBalanceData("오류: 비밀번호 팝업을 열지 못했습니다")
                    return@Thread
                }

                val target = resolveRetirementAccountTargetFromRoot(popup)
                    ?: waitForMtsWindow(timeoutMs = 2000)?.let { resolveRetirementAccountTargetFromRoot(it) }
                if (target == null) {
                    sendBalanceData("오류: 현재 계좌 유형을 판별하지 못했습니다")
                    return@Thread
                }

                val success = enterAccountPassword(target)
                sendBalanceData("ENTER_ACCOUNT_PASSWORD: success=$success account=${target.type}")
            } catch (e: Exception) {
                Log.e(TAG, "계좌 비밀번호 입력 실패", e)
                sendBalanceData("오류: ${e.message}")
            }
        }.start()
    }

    private fun probeAccountPasswordInputForCurrentOrder() {
        Thread {
            try {
                val popup = openAccountPasswordPopupBlocking(requirePopup = true)
                if (popup == null) {
                    sendBalanceData("오류: 비밀번호 팝업을 열지 못했습니다")
                    return@Thread
                }

                if (!openAccountPasswordKeyboard()) {
                    sendBalanceData("오류: 계좌 비밀번호 키패드를 열지 못했습니다")
                    return@Thread
                }

                val keypadRoot = waitForRoot(timeoutMs = 3000) { isVirtualKeyboardAvailableRoot(it) }
                if (keypadRoot == null) {
                    sendBalanceData("오류: 계좌 비밀번호 키패드를 찾지 못했습니다")
                    return@Thread
                }

                val ocrBoxes = readVisibleTextBoxes().orEmpty()
                val digitNodes = collectAccountPopupDigitNodes(keypadRoot)
                val probeResult = mapNumericKeypadSlotsByProbe()
                val slotDigits = probeResult?.slotDigits?.takeIf { it.size == 12 }
                    ?: mapAccountPopupDigitsByVisibleNodes(
                        waitForRoot(timeoutMs = 3000) { isVirtualKeyboardAvailableRoot(it) } ?: keypadRoot,
                    )
                if (slotDigits == null || slotDigits.size != 12) {
                    sendBalanceData("오류: 계좌 비밀번호 12칸 프로브에 실패했습니다")
                    return@Thread
                }

                val matrixRows = formatAccountPopupProbeMatrix(slotDigits)
                val layout = resolveVirtualKeypadProbeLayout(
                    waitForRoot(timeoutMs = 3000) { isVirtualKeyboardAvailableRoot(it) } ?: keypadRoot,
                )
                val adbHint = "adb exec-out screencap -p > screenshots/20260502-account-password-popup.png"

                sendBalanceData(
                    buildString {
                        append("ACCOUNT_PASSWORD_INPUT_PROBE\n")
                        append("popup=true\n")
                        append("keyboard=true\n")
                        append("adb_capture_hint=$adbHint\n")
                        append("ocr_digit_boxes=")
                        append(formatOcrDigitBoxes(ocrBoxes))
                        append("\naccessibility_digit_nodes=")
                        append(formatAccountPopupDigitNodes(digitNodes))
                        append("\nprobe_matrix\n")
                        append(matrixRows.joinToString("\n"))
                        if (probeResult != null) {
                            append("\nprobe_points=")
                            append(formatProbePoints(probeResult.slotMap))
                        }
                        if (layout != null) {
                            append("\nbackspace=")
                            append(formatPoint(layout.backspacePoint))
                            append("\ncomplete=")
                            append(formatPoint(layout.completePoint))
                        }
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "계좌 비밀번호 입력 분석 실패", e)
                sendBalanceData("오류: ${e.message}")
            }
        }.start()
    }

    private fun openAccountPasswordPopupBlocking(requirePopup: Boolean = false): AccessibilityNodeInfo? {
        if (!navigateToRetirementOrderScreen()) {
            return null
        }

        var root = waitForMtsWindow(timeoutMs = 8000) ?: return null
        if (isAccountPasswordPopupRoot(root)) {
            return root
        }
        if (!requirePopup && isRetirementOrderFormUnlocked(root)) {
            return root
        }

        val userTab = findFirstActionableNodeByTextOrDescription(root, "사용자")
            ?: findNodeByText(root, "사용자")
        if (userTab != null) {
            if (!clickNode(userTab)) {
                val bounds = Rect()
                userTab.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    tapCenter(bounds)
                }
            }
            sleep(1200)
            root = waitForMtsWindow(timeoutMs = 5000) ?: root
        } else {
            tapOrderScreenRelative(root, 0.42f, 0.305f)
            sleep(1200)
            root = waitForMtsWindow(timeoutMs = 5000) ?: root
        }

        if (isAccountPasswordPopupRoot(root)) {
            return root
        }
        if (!requirePopup && isRetirementOrderFormUnlocked(root)) {
            return root
        }

        val passwordButton = findFirstActionableNodeByTextOrDescription(root, "비밀번호")
            ?: findNodeByText(root, "비밀번호")
            ?: return null

        val opened = if (clickNode(passwordButton)) {
            true
        } else {
            val bounds = Rect()
            passwordButton.getBoundsInScreen(bounds)
            bounds.width() > 0 && bounds.height() > 0 && tapCenter(bounds)
        }

        val openedOrFallback = if (opened) {
            true
        } else {
            tapOrderScreenRelative(root, 0.75f, 0.205f)
        }

        if (!openedOrFallback) {
            return null
        }

        return waitForRoot(timeoutMs = 5000) {
            isAccountPasswordPopupRoot(it) || (!requirePopup && isRetirementOrderFormUnlocked(it))
        }
    }

    private fun isRetirementOrderFormUnlocked(root: AccessibilityNodeInfo): Boolean {
        val texts = ArrayList<String>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        nodes.forEach { node ->
            val text = nodeText(node).trim()
            if (text.isNotBlank()) {
                texts.add(text)
            }
        }
        val markers = listOf("KRX", "지정가", "시장가", "주문금액")
        val matched = markers.count { marker -> texts.any { it.contains(marker) } }
        return matched >= 3
    }

    private fun testAccountPasswordProbe() {
        Thread {
            try {
                val popupRoot = waitForRoot(timeoutMs = 3000) { isAccountPasswordPopupRoot(it) }
                if (popupRoot == null) {
                    sendBalanceData("오류: 계좌 비밀번호 팝업이 열려 있지 않습니다")
                    return@Thread
                }

                val probeResult = mapNumericKeypadSlotsByProbe()
                val slotDigits = probeResult?.slotDigits?.takeIf { it.size == 12 }
                    ?: mapAccountPopupDigitsByVisibleNodes(
                        waitForRoot(timeoutMs = 3000) { isVirtualKeyboardAvailableRoot(it) } ?: popupRoot,
                    )
                if (slotDigits == null || slotDigits.size != 12) {
                    sendBalanceData("오류: 계좌 비밀번호 12칸 프로브에 실패했습니다")
                    return@Thread
                }

                val matrixRows = formatAccountPopupProbeMatrix(slotDigits)
                Log.d(TAG, "계좌 비밀번호 12칸 프로브 결과\n${matrixRows.joinToString("\n")}")
                sendBalanceData(
                    buildString {
                        append("TEST_ACCOUNT_PASSWORD_PROBE_MATRIX\n")
                        append(matrixRows.joinToString("\n"))
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "계좌 비밀번호 프로브 테스트 실패", e)
                sendBalanceData("오류: ${e.message}")
            }
        }.start()
    }

    private fun testAccountPasswordMatrix() {
        Thread {
            try {
                val popupRoot = waitForRoot(timeoutMs = 3000) { isAccountPasswordPopupRoot(it) }
                if (popupRoot == null) {
                    sendBalanceData("오류: 계좌 비밀번호 팝업이 열려 있지 않습니다")
                    return@Thread
                }

                val gridSlots = resolveAccountPopupGridSlots(popupRoot)
                val layout = resolveVirtualKeypadProbeLayout(popupRoot)
                if (gridSlots.size != 12 || layout == null) {
                    sendBalanceData("오류: 계좌 키패드 좌표를 계산하지 못했습니다")
                    return@Thread
                }

                val digitNodes = collectAccountPopupDigitNodes(popupRoot)
                val expected = MutableList(12) { "-" }
                digitNodes.forEach { (digit, digitNode) ->
                    val index = nearestGridSlotIndex(digitNode.bounds.centerX().toFloat(), digitNode.bounds.centerY().toFloat(), gridSlots)
                    if (index >= 0) {
                        expected[index] = digit.toString()
                    }
                }

                val actual = MutableList(12) { "-" }
                for ((index, slot) in gridSlots.withIndex()) {
                    val beforeLength = virtualKeyboardPasswordLength() ?: 0
                    resetPasswordInputTracking()
                    if (!tapPoint(slot.x, slot.y)) {
                        continue
                    }

                    val accepted = waitForVirtualKeyboardLengthIncrease(beforeLength, timeoutMs = 900)
                    if (accepted) {
                        actual[index] = expected[index]
                        val afterLength = virtualKeyboardPasswordLength() ?: (beforeLength + 1)
                        if (!pressVirtualKeyboardBackspace(layout, afterLength)) {
                            sendBalanceData("오류: 키패드 초기화에 실패했습니다")
                            return@Thread
                        }
                    }

                    sleep(180)
                }

                val summary = buildString {
                    append("EXPECTED\n")
                    append(formatKeypadMatrix(expected))
                    append("\nACTUAL\n")
                    append(formatKeypadMatrix(actual))
                    append("\nMATCH=")
                    append(expected == actual)
                }
                sendBalanceData(summary)
            } catch (e: Exception) {
                Log.e(TAG, "계좌 비밀번호 키패드 매트릭스 테스트 실패", e)
                sendBalanceData("오류: ${e.message}")
            }
        }.start()
    }

    private fun resolveAccountPopupGridSlots(root: AccessibilityNodeInfo): List<PointF> {
        val keyboardBounds = virtualKeyboardBounds(root)
        if (keyboardBounds.width() <= 0 || keyboardBounds.height() <= 0) {
            return emptyList()
        }

        val keypadTop = keyboardBounds.top + keyboardBounds.height() * 0.42f
        val keypadBottom = keyboardBounds.bottom.toFloat()
        val keypadHeight = keypadBottom - keypadTop
        if (keypadHeight <= 0f) {
            return emptyList()
        }

        val slotWidth = keyboardBounds.width() / 3f
        val slotHeight = keypadHeight / 4f
        val slots = mutableListOf<PointF>()
        for (row in 0 until 4) {
            for (column in 0 until 3) {
                slots.add(
                    PointF(
                        keyboardBounds.left + slotWidth * column + slotWidth / 2f,
                        keypadTop + slotHeight * row + slotHeight / 2f,
                    ),
                )
            }
        }
        return slots
    }

    private fun nearestGridSlotIndex(x: Float, y: Float, slots: List<PointF>): Int {
        return slots.indices.minByOrNull { index ->
            val slot = slots[index]
            val dx = slot.x - x
            val dy = slot.y - y
            dx * dx + dy * dy
        } ?: -1
    }

    private fun formatKeypadMatrix(values: List<String>): String {
        return values.chunked(3)
            .joinToString("\n") { row -> row.joinToString(" ") }
    }

    private fun formatOcrDigitBoxes(boxes: List<OcrTextBox>): String {
        val digitBoxes = boxes
            .mapNotNull { box ->
                val digit = normalizeOcrLabel(box.text).singleOrNull()?.takeIf { it.isDigit() }
                digit?.let { it to box.bounds }
            }
            .sortedWith(compareBy({ it.second.top }, { it.second.left }))

        return digitBoxes.joinToString(", ") { (digit, bounds) ->
            "$digit@${formatRect(bounds)}"
        }.ifBlank { "none" }
    }

    private fun formatAccountPopupDigitNodes(nodes: Map<Char, AccountPopupDigitNode>): String {
        return nodes.entries
            .sortedBy { it.key }
            .joinToString(", ") { (digit, node) ->
                "$digit@${formatRect(node.bounds)}"
            }.ifBlank { "none" }
    }

    private fun formatProbePoints(slotMap: Map<Char, PointF>): String {
        return slotMap.entries
            .sortedBy { it.key }
            .joinToString(", ") { (digit, point) ->
                "$digit@${formatPoint(point)}"
            }.ifBlank { "none" }
    }

    private fun formatPoint(point: PointF): String {
        return "(${point.x.toInt()},${point.y.toInt()})"
    }

    private fun formatRect(rect: Rect): String {
        return "[${rect.left},${rect.top},${rect.right},${rect.bottom}]"
    }

    private fun tapOrderScreenRelative(root: AccessibilityNodeInfo, xRatio: Float, yRatio: Float): Boolean {
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false
        }
        val x = bounds.left + bounds.width() * xRatio
        val y = bounds.top + bounds.height() * yRatio
        return tapPoint(x, y)
    }

    private fun navigateToRetirementBalanceDirectScreen() {
        try {
            openDeepLinkScreen(RETIREMENT_BALANCE_SCREEN_NO)
            Log.d(TAG, "${RETIREMENT_BALANCE_SCREEN_NO} 화면으로 이동 명령 전송 완료")
        } catch (e: Exception) {
            Log.e(TAG, "${RETIREMENT_BALANCE_SCREEN_NO} 화면 이동 실패", e)
        }
    }

    private fun openDeepLinkScreen(screenNo: String) {
        val intent = Intent().apply {
            component = ComponentName(MTS_PACKAGE, "$MTS_PACKAGE.ui.main.MTSMainActivity")
            action = "ActionDeepLink"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("KeyOpenScreenNo", screenNo)
            putExtra("KeyOpenScreenData", "")
        }

        startActivity(intent)
    }

    private fun launchMtsApp(): Boolean {
        try {
            val intent = packageManager.getLaunchIntentForPackage(MTS_PACKAGE) ?: run {
                Log.e(TAG, "MTS 앱 실행 Intent를 찾지 못했습니다")
                return false
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
            Log.d(TAG, "MTS 앱 시작 완료")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "MTS 앱 시작 실패", e)
            return false
        }
    }

    private fun dismissTransientOverlays(root: AccessibilityNodeInfo): Boolean {
        val dismissQueries = listOf("닫기", "확인", "오늘 하루 보지 않기", "그만보기", "취소")
        if (clickTextNode(root, dismissQueries)) {
            sleep(700)
            return true
        }

        if (closeBottomRightTickerOverlay(root, allowCoordinateFallback = false)) {
            sleep(700)
            return true
        }

        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val closeNode = nodes.firstOrNull { node ->
            val text = nodeText(node)
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.width() > 0 &&
                bounds.height() > 0 &&
                bounds.top < 260 &&
                (text == "X" || text.equals("close", ignoreCase = true)) &&
                isActionableNode(node)
        }
        return closeNode != null && clickNode(closeNode)
    }

    private fun captureScreenSnapshot(label: String, root: AccessibilityNodeInfo): ScreenSnapshot {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val visibleTexts = nodes.map { nodeText(it) }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(18)
        val clickableTexts = nodes.filter { isActionableNode(it) }
            .map { nodeText(it).replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)

        return ScreenSnapshot(
            label = label,
            packageName = root.packageName?.toString().orEmpty(),
            visibleTexts = visibleTexts,
            clickableTexts = clickableTexts,
        )
    }

    private fun formatScreenSnapshot(snapshot: ScreenSnapshot): String {
        val texts = snapshot.visibleTexts.take(8).joinToString(" | ").ifBlank { "텍스트 없음" }
        val actions = snapshot.clickableTexts.take(5).joinToString(" | ").ifBlank { "클릭 후보 없음" }
        return "[${snapshot.label}] ${snapshot.packageName}\ntexts: $texts\nactions: $actions"
    }

    private fun clickFlexible(
        root: AccessibilityNodeInfo,
        queries: List<String>,
        preferBottom: Boolean = false,
    ): Boolean {
        for (query in queries) {
            val candidate = findFlexibleActionCandidate(root, query, preferBottom)
            if (candidate != null) {
                val clicked = candidate.node?.let { clickNode(it) } == true ||
                    candidate.bounds?.let { tapCenter(it) } == true
                if (clicked) {
                    Log.d(TAG, "Flexible click '$query' via '${candidate.label}' score=${candidate.score}")
                    return true
                }
            }
        }

        if (preferBottom) {
            for (query in queries) {
                if (tapBottomNavigationByLabel(root, query)) {
                    Log.d(TAG, "Flexible click '$query' via bottom-navigation fallback")
                    return true
                }
            }
        }

        return clickTextOrOcr(root, queries)
    }

    private fun tapBottomNavigationByLabel(root: AccessibilityNodeInfo, label: String): Boolean {
        val normalized = normalizeOcrLabel(label)
        val tabNode = findBottomTabNode(root, label)
        if (tabNode != null) {
            if (clickNode(tabNode)) {
                Log.d(TAG, "Bottom navigation label=$label clicked via node")
                return true
            }
            val bounds = Rect()
            tabNode.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0 && tapCenter(bounds)) {
                Log.d(TAG, "Bottom navigation label=$label tapped via node bounds")
                return true
            }
        }

        if (normalized == normalizeOcrLabel("메뉴") && !isBottomMenuVisible(root)) {
            closeBottomRightTickerOverlay(root, allowCoordinateFallback = true)
            sleep(500)
        }

        val xRatio = when {
            normalized == normalizeOcrLabel("메뉴") -> 0.09f
            normalized == normalizeOcrLabel("홈") -> 0.25f
            normalized == normalizeOcrLabel("현재가") -> 0.40f
            normalized == normalizeOcrLabel("자산") -> 0.55f
            normalized == normalizeOcrLabel("퇴직주문") -> 0.71f
            normalized == normalizeOcrLabel("지수") -> 0.90f
            else -> return false
        }

        val realDisplaySize = getRealDisplaySize()
        val width = realDisplaySize?.x?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.takeIf { it > 0 } ?: run {
            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            bounds.width()
        }
        val height = realDisplaySize?.y?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.takeIf { it > 0 } ?: run {
            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            bounds.height()
        }
        if (width <= 0 || height <= 0) {
            return false
        }

        val x = width * xRatio
        val y = if (height < 2200) {
            height + 120f
        } else {
            height - 190f
        }
        Log.d(TAG, "Bottom navigation fallback label=$label x=$x y=$y size=${width}x$height")
        tapPoint(x, y)
        return true
    }

    private fun findBottomTabNode(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val normalized = normalizeOcrLabel(label)
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val size = getRealDisplaySize()
        val screenHeight = size?.y ?: resources.displayMetrics.heightPixels

        val labelNode = nodes.firstOrNull { node ->
            val text = normalizeOcrLabel(nodeText(node))
            if (!text.contains(normalized)) {
                return@firstOrNull false
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.width() > 0 &&
                bounds.height() > 0 &&
                bounds.centerY() > screenHeight * 0.82f
        } ?: return null

        return generateSequence(labelNode.parent) { it.parent }
            .firstOrNull { parent ->
                val bounds = Rect()
                parent.getBoundsInScreen(bounds)
                parent.isClickable &&
                    bounds.width() > 0 &&
                    bounds.height() > 0 &&
                    bounds.centerY() > screenHeight * 0.80f
            } ?: labelNode
    }

    private fun isBottomMenuVisible(root: AccessibilityNodeInfo): Boolean {
        return findBottomMenuNode(root) != null
    }

    private fun findBottomMenuNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val size = getRealDisplaySize()
        val screenHeight = size?.y ?: resources.displayMetrics.heightPixels
        val screenWidth = size?.x ?: resources.displayMetrics.widthPixels

        return nodes.firstOrNull { node ->
            val text = nodeText(node)
            if (!text.contains("메뉴")) {
                return@firstOrNull false
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.width() > 0 &&
                bounds.height() > 0 &&
                bounds.centerY() > screenHeight * 0.78f &&
                bounds.centerX() < screenWidth * 0.22f
        }
    }

    private fun clickBottomMenuButton(root: AccessibilityNodeInfo): Boolean {
        val bottomMenuNode = findBottomMenuNode(root)
        if (bottomMenuNode != null) {
            if (clickNode(bottomMenuNode)) {
                return true
            }
            val bounds = Rect()
            bottomMenuNode.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0 && tapCenter(bounds)) {
                return true
            }
        }

        return tapBottomNavigationByLabel(root, "메뉴")
    }

    private fun closeBottomRightTickerOverlay(
        root: AccessibilityNodeInfo,
        allowCoordinateFallback: Boolean = false,
    ): Boolean {
        val size = getRealDisplaySize()
        val screenWidth = size?.x ?: resources.displayMetrics.widthPixels
        val screenHeight = size?.y ?: resources.displayMetrics.heightPixels
        if (screenWidth <= 0 || screenHeight <= 0) {
            return false
        }

        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val closeNode = nodes.firstOrNull { node ->
            val text = normalizeOcrLabel(nodeText(node))
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.width() > 0 &&
                bounds.height() > 0 &&
                bounds.centerX() > screenWidth * 0.78f &&
                bounds.centerY() > screenHeight * 0.72f &&
                (text == "x" || text.contains("닫기") || text.contains("close"))
        }

        if (closeNode != null) {
            if (clickNode(closeNode)) {
                Log.d(TAG, "Closed bottom-right ticker overlay by node")
                return true
            }
            val bounds = Rect()
            closeNode.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0 && tapCenter(bounds)) {
                Log.d(TAG, "Closed bottom-right ticker overlay by node bounds")
                return true
            }
        }

        if (!allowCoordinateFallback) {
            return false
        }

        val x = screenWidth * 0.91f
        val y = screenHeight - 250f
        Log.d(TAG, "Trying bottom-right ticker close fallback at ($x, $y)")
        return tapPoint(x, y)
    }

    private fun getRealDisplaySize(): Point? {
        return try {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            Point().also { windowManager.defaultDisplay.getRealSize(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read real display size: ${e.message}")
            null
        }
    }

    private fun findFlexibleActionCandidate(
        root: AccessibilityNodeInfo,
        query: String,
        preferBottom: Boolean,
    ): FlexibleActionCandidate? {
        val normalizedQuery = normalizeOcrLabel(query)
        val nodeCandidates = collectSafeActionCandidates(root).mapNotNull { candidate ->
            if (!normalizeOcrLabel(candidate.label).contains(normalizedQuery)) {
                return@mapNotNull null
            }
            val bounds = candidate.bounds ?: Rect()
            val bottomBonus = if (preferBottom && bounds.centerY() > 1600) 200 else 0
            candidate.copy(score = candidate.score + bottomBonus)
        }

        val ocrCandidates = readVisibleTextBoxes().orEmpty().mapNotNull { box ->
            val normalized = normalizeOcrLabel(box.text)
            if (!normalized.contains(normalizedQuery) || isRiskyActionLabel(box.text)) {
                return@mapNotNull null
            }
            val bottomBonus = if (preferBottom && box.bounds.centerY() > 1600) 200 else 0
            FlexibleActionCandidate(
                label = box.text,
                node = null,
                bounds = box.bounds,
                score = 100 + bottomBonus - box.bounds.top / 20,
            )
        }

        return (nodeCandidates + ocrCandidates).maxByOrNull { it.score }
    }

    private fun collectSafeActionCandidates(root: AccessibilityNodeInfo): List<FlexibleActionCandidate> {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        return nodes.mapNotNull { node ->
            val label = nodeText(node).replace(Regex("\\s+"), " ").trim()
            if (label.isBlank() || !isActionableNode(node) || isRiskyActionLabel(label)) {
                return@mapNotNull null
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0 || !node.isVisibleToUser) {
                return@mapNotNull null
            }
            val bottomBonus = if (bounds.centerY() > 1600) 80 else 0
            FlexibleActionCandidate(
                label = label,
                node = node,
                bounds = bounds,
                score = 100 + bottomBonus - label.length,
            )
        }
            .distinctBy { normalizeOcrLabel(it.label) }
            .sortedByDescending { it.score }
    }

    private fun isRiskyActionLabel(label: String): Boolean {
        val normalized = normalizeOcrLabel(label)
        val riskyTerms = listOf(
            "매수",
            "매도",
            "정정",
            "취소주문",
            "주문전송",
            "주문하기",
            "입금",
            "출금",
            "이체",
            "동의",
            "신청",
        )
        return riskyTerms.any { normalized.contains(normalizeOcrLabel(it)) }
    }

    private fun openCertificatePasswordKeyboard(): Boolean {
        val loginRoot = waitForRoot(timeoutMs = 8000) { isCertLoginRoot(it) }
        if (loginRoot == null) {
            Log.w(TAG, "openCertPwdKbd: cert login root not found")
            return false
        }

        val passwordField = firstVisibleNodeByViewId(loginRoot, "tf_cert_login_password")
            ?: firstVisibleNodeByViewId(loginRoot, "et_password")

        if (passwordField == null) {
            Log.w(TAG, "openCertPwdKbd: password field NOT found. Dumping view IDs:")
            dumpViewIds(loginRoot, "certLogin")
            return false
        }

        Log.d(TAG, "openCertPwdKbd: password field found, clickable=${passwordField.isClickable}, visible=${passwordField.isVisibleToUser}, bounds=${boundsStr(passwordField)}")

        repeat(3) { attempt ->
            Log.d(TAG, "openCertPwdKbd: click attempt ${attempt + 1}/3")
            val clicked = focusAndTapPasswordField(passwordField)
            Log.d(TAG, "openCertPwdKbd: interaction result=$clicked")

            if (clicked) {
                sleep(800)
                val transKeyRoot = findTransKeyInAnyWindow()
                if (transKeyRoot != null) {
                    Log.d(TAG, "openCertPwdKbd: TransKey found!")
                    return true
                }
                Log.d(TAG, "openCertPwdKbd: TransKey not found, checking all windows after click")
                for ((idx, window) in windows.withIndex()) {
                    val wr = window.root
                    Log.d(TAG, "  window[$idx]: pkg=${wr?.packageName}, class=${wr?.className}")
                }
            }
            sleep(400)
        }

        Log.w(TAG, "openCertPwdKbd: all attempts failed")
        dumpAllWindowsInfo()
        return false
    }

    private fun focusAndTapPasswordField(passwordField: AccessibilityNodeInfo): Boolean {
        passwordField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        passwordField.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(passwordField, candidates)

        val attemptedBounds = linkedSetOf<String>()
        var interacted = false

        for (candidate in candidates) {
            candidate.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            candidate.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                interacted = true
            }

            val bounds = Rect()
            candidate.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                continue
            }

            val key = bounds.flattenToString()
            if (!attemptedBounds.add(key)) {
                continue
            }

            if (tapCenter(bounds)) {
                interacted = true
            }

            val rightX = (bounds.right - 24).coerceAtLeast(bounds.left + 1).toFloat()
            if (tapPoint(rightX, bounds.centerY().toFloat())) {
                interacted = true
            }
        }

        return interacted
    }

    private fun findTransKeyInAnyWindow(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        if (root != null && isTransKeyRoot(root)) return root
        if (root != null && isTransKeyRootGeneric(root)) return root

        for (window in windows) {
            val windowRoot = window.root ?: continue
            if (isTransKeyRoot(windowRoot)) return windowRoot
            if (isTransKeyRootGeneric(windowRoot)) return windowRoot
        }

        return null
    }

    private fun isTransKeyRootGeneric(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val transKeyIds = listOf("fl_transkey", "keypadContainer", "transkey_navi_complete_button", "transkey_cursur_input")
        return nodes.any { node ->
            val id = node.viewIdResourceName ?: return@any false
            transKeyIds.any { suffix -> id.endsWith("/$suffix") || id.endsWith(":$suffix") }
        }
    }

    private fun dumpViewIds(root: AccessibilityNodeInfo, prefix: String) {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val ids = nodes.mapNotNull { it.viewIdResourceName }.distinct().sorted()
        Log.d(TAG, "[$prefix] Total nodes: ${nodes.size}, unique view IDs: ${ids.size}")
        ids.forEach { Log.d(TAG, "[$prefix] $it") }
    }

    private fun dumpAllWindowsInfo() {
        val allWindows = windows
        Log.d(TAG, "Total windows: ${allWindows.size}")
        for ((index, window) in allWindows.withIndex()) {
            val root = window.root
            Log.d(TAG, "window[$index]: type=${window.type}, pkg=${root?.packageName}, class=${root?.className}")
            if (root != null) {
                dumpViewIds(root, "window[$index]")
            }
        }
    }

    private fun boundsStr(node: AccessibilityNodeInfo): String {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.toShortString()
    }

    private fun waitForCertificateLoginScreen(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val certRoot = findCertLoginRootInAnyWindow()
            if (certRoot != null) {
                return true
            }

            val mtsRoot = findMtsRootInAnyWindow()
            if (mtsRoot != null && tapLoginEntryPoint(mtsRoot)) {
                val loginRoot = waitForRoot(timeoutMs = 5000) { isCertLoginRoot(it) }
                if (loginRoot != null) {
                    return true
                }
            }

            sleep(500)
        }

        return false
    }

    private fun tapLoginEntryPoint(root: AccessibilityNodeInfo): Boolean {
        val candidates = listOf(
            "공동인증서 로그인",
            "공동인증서",
            "인증서 로그인",
            "인증서",
            "로그인",
        )

        candidates.forEach { query ->
            val node = findFirstActionableNodeByTextOrDescription(root, query)
            if (node != null && clickNode(node)) {
                sleep(1000)
                return true
            }
        }

        return false
    }

    private fun enterCertificatePassword(password: String): Boolean {
        return enterSecurePassword(password) { root -> isCertLoginRoot(root) }
    }

    private fun enterSecurePassword(
        password: String,
        exitPredicate: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        clearVirtualKeyboardInput()

        var expectedLength = virtualKeyboardPasswordLength() ?: 0
        for (char in password) {
            if (!pressVirtualKeyboardKey(char, expectedLength)) {
                Log.w(TAG, "Failed to enter virtual key: $char")
                return false
            }
            expectedLength += 1
        }

        return completeVirtualKeyboardEntry(exitPredicate)
    }

    private fun clearVirtualKeyboardInput() {
        val root = waitForRoot(timeoutMs = 3000) { isTransKeyRoot(it) } ?: return
        firstVisibleNodeByViewId(root, "ib_clear")?.let { clearButton ->
            clickNode(clearButton)
            sleep(300)
        }
    }

    private fun pressVirtualKeyboardKey(char: Char, previousLength: Int): Boolean {
        repeat(12) {
            val root = waitForRoot(timeoutMs = 4000) { isTransKeyRoot(it) } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pressVirtualKeyboardKeyByOcr(root, char, previousLength)) {
                return true
            }

            val keyNode = findVirtualKeyboardKey(root, char)
            if (keyNode != null && clickNode(keyNode)) {
                sleep(350)
                return true
            }

            if (switchVirtualKeyboardForChar(root, char)) {
                sleep(500)
                val switchedRoot = waitForRoot(timeoutMs = 2000) { isTransKeyRoot(it) } ?: return false
                val switchedKeyNode = findVirtualKeyboardKey(switchedRoot, char)
                if (switchedKeyNode != null && clickNode(switchedKeyNode)) {
                    sleep(350)
                    return true
                }
            }

            if (!moveToNextVirtualKeyboardPage(root)) {
                return false
            }
        }

        return false
    }

    private fun pressVirtualKeyboardKeyByOcr(
        root: AccessibilityNodeInfo,
        char: Char,
        previousLength: Int,
    ): Boolean {
        val keyboardBounds = virtualKeyboardBounds(root)
        val boxes = readVisibleTextBoxes() ?: return false
        val keyBox = findOcrKeyBox(boxes, char, keyboardBounds)
        if (keyBox != null && tapCenter(keyBox.bounds)) {
            sleep(350)
            val currentLength = virtualKeyboardPasswordLength()
            if (currentLength == null || currentLength > previousLength) {
                return true
            }
        }

        if (switchVirtualKeyboardByOcr(root, boxes, keyboardBounds)) {
            sleep(500)
            val symbolBoxes = readVisibleTextBoxes() ?: return false
            val symbolKey = findOcrKeyBox(symbolBoxes, char, virtualKeyboardBounds(rootInActiveWindow ?: root))
            if (symbolKey != null && tapCenter(symbolKey.bounds)) {
                sleep(350)
                val currentLength = virtualKeyboardPasswordLength()
                return currentLength == null || currentLength > previousLength
            }

            switchVirtualKeyboardByOcr(rootInActiveWindow ?: root, symbolBoxes, virtualKeyboardBounds(rootInActiveWindow ?: root))
        }

        return false
    }

    private fun findOcrKeyBox(
        boxes: List<OcrTextBox>,
        char: Char,
        keyboardBounds: Rect,
    ): OcrTextBox? {
        val labels = expectedVirtualKeyLabels(char).map { normalizeOcrLabel(it) }.toSet()
        return boxes
            .filter { keyboardBounds.intersectedWith(it.bounds) }
            .filter { box ->
                val normalized = normalizeOcrLabel(box.text)
                normalized in labels || labels.any { normalized.contains(it) }
            }
            .minByOrNull { box ->
                val centerY = box.bounds.centerY()
                val centerX = box.bounds.centerX()
                centerY * 10_000 + centerX
            }
    }

    private fun switchVirtualKeyboardByOcr(
        root: AccessibilityNodeInfo,
        boxes: List<OcrTextBox>,
        keyboardBounds: Rect,
    ): Boolean {
        val switchBox = boxes
            .filter { keyboardBounds.intersectedWith(it.bounds) }
            .firstOrNull { box ->
                val label = normalizeOcrLabel(box.text)
                label == "a/@" || label == "a@" || label.contains("/@")
            }

        if (switchBox != null && tapCenter(switchBox.bounds)) {
            return true
        }

        val switchNode = findFirstActionableNodeByTextOrDescription(root, "a/@")
            ?: findFirstActionableNodeByTextOrDescription(root, "@")
            ?: findFirstActionableNodeByTextOrDescription(root, "특수문자변경")
        return switchNode != null && clickNode(switchNode)
    }

    private fun findVirtualKeyboardKey(root: AccessibilityNodeInfo, char: Char): AccessibilityNodeInfo? {
        val transKeyRoot = firstVisibleNodeByViewId(root, "fl_transkey") ?: root
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(transKeyRoot, candidates)

        val expectedLabels = expectedVirtualKeyLabels(char)
        return candidates.firstOrNull { node ->
            val label = nodeText(node)
            label.isNotBlank() && expectedLabels.any { expected ->
                label.equals(expected, ignoreCase = false) ||
                    label.contains(expected, ignoreCase = false)
            } && isActionableNode(node)
        }
    }

    private fun expectedVirtualKeyLabels(char: Char): List<String> {
        return when (char) {
            '@' -> listOf("@", "골뱅이")
            '.' -> listOf(".")
            '-' -> listOf("-")
            '_' -> listOf("_")
            else -> listOf(char.toString())
        }
    }

    private fun switchVirtualKeyboardForChar(root: AccessibilityNodeInfo, char: Char): Boolean {
        val queries = when (char) {
            '@', '.', '-', '_' -> listOf("특수문자변경", "문자변경", "키보드 변경")
            else -> listOf("영문", "소문자", "키보드 변경")
        }

        for (query in queries) {
            val node = findFirstActionableNodeByTextOrDescription(root, query)
            if (node != null && clickNode(node)) {
                return true
            }
        }

        return false
    }

    private fun moveToNextVirtualKeyboardPage(root: AccessibilityNodeInfo): Boolean {
        val nextButton = firstVisibleNodeByViewId(root, "transkey_navi_next_button")
            ?: return false

        val beforeSignature = keypadSignature(root)
        if (!clickNode(nextButton)) {
            return false
        }

        val moved = waitForRoot(timeoutMs = 2000) { updated ->
            isTransKeyRoot(updated) && keypadSignature(updated) != beforeSignature
        }

        return moved != null
    }

    private fun keypadSignature(root: AccessibilityNodeInfo): String {
        val transKeyRoot = firstVisibleNodeByViewId(root, "fl_transkey") ?: root
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(transKeyRoot, nodes)
        return nodes.map { nodeText(it) }
            .filter { it.isNotBlank() }
            .joinToString("|")
    }

    private fun virtualKeyboardPasswordLength(): Int? {
        val root = waitForRoot(timeoutMs = 2000) { isVirtualKeyboardAvailableRoot(it) } ?: return null
        val node = firstVisibleNodeByViewId(root, "et_password")
            ?: firstVisibleNodeByViewId(root, "etText")
        val textLength = node?.text?.length
        if (textLength != null && textLength > 0) {
            return textLength
        }

        return virtualKeyboardIndicatorCount(root)
    }

    private fun logVirtualKeyboardIndicatorState(root: AccessibilityNodeInfo, label: String) {
        val dotViewIds = listOf(
            "img_account_trans_key_dot1",
            "img_account_trans_key_dot2",
            "img_account_trans_key_dot3",
            "img_account_trans_key_dot4",
            "img_password_trans_key_dot1",
            "img_password_trans_key_dot2",
            "img_password_trans_key_dot3",
            "img_password_trans_key_dot4",
            "img_password_trans_key_dot5",
            "img_password_trans_key_dot6",
        )
        val states = dotViewIds.mapNotNull { viewId ->
            val node = firstVisibleNodeByViewId(root, viewId) ?: return@mapNotNull null
            node.refresh()
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            "${viewId.substringAfterLast('/')}[sel=${node.isSelected},chk=${node.isChecked},enabled=${node.isEnabled},visible=${node.isVisibleToUser},bounds=${bounds.toShortString()}]"
        }
        if (states.isNotEmpty()) {
            Log.d(TAG, "Indicator state [$label] ${states.joinToString(" ")}")
        }
    }

    private fun virtualKeyboardIndicatorCount(root: AccessibilityNodeInfo): Int? {
        val dotViewIds = listOf(
            "img_account_trans_key_dot1",
            "img_account_trans_key_dot2",
            "img_account_trans_key_dot3",
            "img_account_trans_key_dot4",
            "img_password_trans_key_dot1",
            "img_password_trans_key_dot2",
            "img_password_trans_key_dot3",
            "img_password_trans_key_dot4",
            "img_password_trans_key_dot5",
            "img_password_trans_key_dot6",
        )
        val dotNodes = dotViewIds.mapNotNull { firstVisibleNodeByViewId(root, it) }
        if (dotNodes.isNotEmpty()) {
            dotNodes.forEach { it.refresh() }
            return dotNodes.count { it.isSelected || it.isChecked }
        }

        val indicatorNode = firstVisibleNodeByViewId(root, "ll_password")
            ?: firstVisibleNodeByViewId(root, "keylayout")
            ?: return null

        if (indicatorNode.childCount > 0) {
            return indicatorNode.childCount
        }

        for (index in 0 until indicatorNode.childCount) {
            val child = indicatorNode.getChild(index) ?: continue
            if (child.childCount > 0) {
                return child.childCount
            }
        }

        return 0
    }

    private fun resetPasswordInputTracking() {
        lastPasswordEventTime.set(0L)
        lastPasswordAddedCount.set(0)
        lastPasswordRemovedCount.set(0)
    }

    private fun waitForVirtualKeyboardLengthIncrease(previousLength: Int, timeoutMs: Long = 1200): Boolean {
        val baselineEventTime = lastPasswordEventTime.get()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val currentLength = waitForRoot(timeoutMs = 250) { isVirtualKeyboardAvailableRoot(it) }
                ?.let { virtualKeyboardIndicatorCount(it) }
                ?: virtualKeyboardPasswordLength()
            if (currentLength != null && currentLength > previousLength) {
                return true
            }

            if (lastPasswordEventTime.get() > baselineEventTime && lastPasswordAddedCount.get() > 0) {
                return true
            }

            sleep(80)
        }
        return false
    }

    private fun waitForVirtualKeyboardLengthDecrease(previousLength: Int, timeoutMs: Long = 1200): Boolean {
        val baselineEventTime = lastPasswordEventTime.get()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val currentLength = virtualKeyboardPasswordLength()
            if (currentLength != null && currentLength < previousLength) {
                return true
            }

            if (lastPasswordEventTime.get() > baselineEventTime && lastPasswordRemovedCount.get() > 0) {
                return true
            }

            sleep(80)
        }
        return false
    }

    private fun resolveVirtualKeypadProbeLayout(root: AccessibilityNodeInfo): VirtualKeypadProbeLayout? {
        val keyboardBounds = virtualKeyboardBounds(root)
        if (keyboardBounds.width() <= 0 || keyboardBounds.height() <= 0) {
            return null
        }

        val completeBounds = Rect()
        val completeNode = firstVisibleNodeByViewId(root, "transkey_navi_complete_button")
            ?: firstVisibleNodeByViewId(root, "done")
            ?: findFirstActionableNodeByTextOrDescription(root, "입력완료")
        completeNode?.getBoundsInScreen(completeBounds)

        val backspaceBounds = Rect()
        val backspaceNode = firstVisibleNodeByViewId(root, "ib_clear")
            ?: findFirstActionableNodeByTextOrDescription(root, "삭제")
            ?: findFirstActionableNodeByTextOrDescription(root, "backspace")
        backspaceNode?.getBoundsInScreen(backspaceBounds)

        val isAccountPopup = isAccountPasswordPopupRoot(root)
        val keypadTop = if (isAccountPopup) {
            keyboardBounds.top.toFloat()
        } else {
            keyboardBounds.top.toFloat()
        }
        val keypadBottom = keyboardBounds.bottom.toFloat()
        val keypadHeight = keypadBottom - keypadTop
        if (keypadHeight <= 0f) {
            return null
        }

        val digitSlots = mutableListOf<PointF>()
        val probeSlots = mutableListOf<PointF>()
        val actionCenterY: Float

        if (isAccountPopup) {
            val actionTop = listOfNotNull(
                completeBounds.top.takeIf { completeBounds.height() > 0 },
                backspaceBounds.top.takeIf { backspaceBounds.height() > 0 },
            ).minOrNull()?.toFloat() ?: (keyboardBounds.top + keyboardBounds.height() * 4f / 5f)
            val digitAreaHeight = actionTop - keypadTop
            if (digitAreaHeight <= 0f) {
                return null
            }

            val slotWidth = keyboardBounds.width() / 4f
            val slotHeight = digitAreaHeight / 3f
            for (row in 0 until 3) {
                for (column in 0 until 4) {
                    probeSlots.add(
                        PointF(
                            keyboardBounds.left + slotWidth * column + slotWidth / 2f,
                            keypadTop + slotHeight * row + slotHeight / 2f,
                        ),
                    )
                }
            }

            digitSlots.addAll(probeSlots)

            actionCenterY = when {
                completeBounds.height() > 0 -> completeBounds.centerY().toFloat()
                backspaceBounds.height() > 0 -> backspaceBounds.centerY().toFloat()
                else -> actionTop + (keyboardBounds.bottom - actionTop) / 2f
            }
        } else {
            val actionTop = if (completeBounds.height() > 0) {
                completeBounds.top.toFloat()
            } else {
                keyboardBounds.top + (keyboardBounds.height() * 3 / 4f)
            }
            val digitAreaHeight = actionTop - keyboardBounds.top
            if (digitAreaHeight <= 0f) {
                return null
            }

            val slotWidth = keyboardBounds.width() / 4f
            val slotHeight = digitAreaHeight / 3f
            for (row in 0 until 3) {
                for (column in 0 until 4) {
                    val point = PointF(
                        keyboardBounds.left + slotWidth * column + slotWidth / 2f,
                        keyboardBounds.top + slotHeight * row + slotHeight / 2f,
                    )
                    digitSlots.add(point)
                    probeSlots.add(point)
                }
            }
            actionCenterY = if (completeBounds.height() > 0) {
                completeBounds.centerY().toFloat()
            } else {
                actionTop + (keyboardBounds.bottom - actionTop) / 2f
            }
        }

        return VirtualKeypadProbeLayout(
            digitSlots = digitSlots,
            probeSlots = probeSlots.ifEmpty { digitSlots },
            backspacePoint = PointF(
                if (isAccountPopup) keyboardBounds.left + keyboardBounds.width() / 4f else keyboardBounds.left + keyboardBounds.width() / 4f,
                actionCenterY,
            ),
            completePoint = PointF(
                if (completeBounds.width() > 0) completeBounds.centerX().toFloat()
                else if (isAccountPopup) keyboardBounds.left + keyboardBounds.width() * 3f / 4f
                else keyboardBounds.left + keyboardBounds.width() * 3f / 4f,
                actionCenterY,
            ),
        )
    }

    private fun pressVirtualKeyboardBackspace(layout: VirtualKeypadProbeLayout, previousLength: Int): Boolean {
        resetPasswordInputTracking()

        val root = waitForRoot(timeoutMs = 2000) { isVirtualKeyboardAvailableRoot(it) }
        val backspaceNode = root?.let {
            firstVisibleNodeByViewId(it, "ib_clear")
                ?: findFirstActionableNodeByTextOrDescription(it, "삭제")
                ?: findFirstActionableNodeByTextOrDescription(it, "backspace")
        }

        val pressed = if (backspaceNode != null) {
            clickNode(backspaceNode)
        } else {
            tapPoint(layout.backspacePoint.x, layout.backspacePoint.y)
        }

        return pressed && waitForVirtualKeyboardLengthDecrease(previousLength)
    }

    private fun clearVirtualKeyboardInputByBackspace(layout: VirtualKeypadProbeLayout): Boolean {
        var currentLength = virtualKeyboardPasswordLength() ?: return true
        var attempts = 0
        while (currentLength > 0 && attempts < 16) {
            if (!pressVirtualKeyboardBackspace(layout, currentLength)) {
                return false
            }
            currentLength -= 1
            attempts += 1
        }
        return currentLength == 0
    }

    private fun mapAccountPopupDigitsByVisibleNodes(root: AccessibilityNodeInfo): List<Char?>? {
        val layout = resolveVirtualKeypadProbeLayout(root) ?: return null
        if (layout.probeSlots.size != 12) {
            return null
        }

        val slotDigits = MutableList<Char?>(12) { null }
        collectAccountPopupDigitNodes(root).forEach { (digit, digitNode) ->
            val slotIndex = nearestGridSlotIndex(
                digitNode.bounds.centerX().toFloat(),
                digitNode.bounds.centerY().toFloat(),
                layout.probeSlots,
            )
            if (slotIndex in slotDigits.indices) {
                slotDigits[slotIndex] = digit
            }
        }

        return if (slotDigits.count { it != null } >= 10) slotDigits else null
    }

    private fun formatAccountPopupProbeMatrix(slotDigits: List<Char?>): List<String> {
        if (slotDigits.size != 12) {
            return emptyList()
        }

        return slotDigits.chunked(4).map { row ->
            row.joinToString(" ") { digit -> (digit ?: '-').toString() }
        }
    }

    private fun mapNumericKeypadSlotsByProbe(): NumericKeypadProbeResult? {
        resetPasswordInputTracking()

        val root = waitForRoot(timeoutMs = 3000) { isVirtualKeyboardAvailableRoot(it) } ?: return null
        val layout = resolveVirtualKeypadProbeLayout(root) ?: return null
        val isAccountPopup = isAccountPasswordPopupRoot(root)
        if (isAccountPopup) {
            if (!clearVirtualKeyboardInputByBackspace(layout)) {
                Log.w(TAG, "Failed to clear account popup input before probe")
                return null
            }
        } else {
            clearVirtualKeyboardInput()
        }

        val mappedSlots = mutableMapOf<Char, PointF>()
        val slotDigits = if (isAccountPopup) MutableList<Char?>(layout.probeSlots.size) { null } else mutableListOf()
        val digitOrder = "1234567890"
        var digitIndex = 0
        var expectedLength = virtualKeyboardPasswordLength() ?: 0
        val slots = if (isAccountPopup) layout.probeSlots else layout.digitSlots

        // For the account popup keypad, scan the 4x3 grid left-to-right and map each
        // accepted press to the next digit in 1234567890 while blank slots are skipped.
        for ((slotIndex, slot) in slots.withIndex()) {
            if (digitIndex >= digitOrder.length) {
                break
            }

            resetPasswordInputTracking()
            if (!tapPoint(slot.x, slot.y)) {
                continue
            }

            if (!waitForVirtualKeyboardLengthIncrease(expectedLength)) {
                if (isAccountPopup) {
                    Log.d(TAG, "Account popup probe blank slot index=$slotIndex")
                }
                continue
            }

            val digit = digitOrder[digitIndex]
            mappedSlots[digit] = slot
            if (isAccountPopup) {
                slotDigits[slotIndex] = digit
            }
            if (isAccountPopup) {
                Log.d(TAG, "Account popup probe mapped digit=$digit slotIndex=$slotIndex")
            }
            expectedLength += 1

            if (!pressVirtualKeyboardBackspace(layout, expectedLength)) {
                Log.w(TAG, "Failed to rollback probed digit $digit")
                return null
            }

            expectedLength -= 1
            digitIndex += 1
        }

        if (digitIndex != digitOrder.length) {
            Log.w(TAG, "Failed to map full keypad. mapped=$digitIndex")
            return null
        }

        return NumericKeypadProbeResult(
            slotMap = mappedSlots,
            slotDigits = slotDigits,
        )
    }

    private fun buildDefaultAccountPasswordSlotMap(layout: VirtualKeypadProbeLayout): Map<Char, PointF> {
        val digits = "1234567890"
        return buildMap {
            digits.forEachIndexed { index, digit ->
                val point = layout.digitSlots.getOrNull(index) ?: return@forEachIndexed
                put(digit, point)
            }
        }
    }

    private fun collectAccountPopupDigitNodes(root: AccessibilityNodeInfo): Map<Char, AccountPopupDigitNode> {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val result = linkedMapOf<Char, AccountPopupDigitNode>()

        nodes.forEach { node ->
            val text = nodeText(node).trim()
            if (text.length != 1 || !text[0].isDigit()) {
                return@forEach
            }

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0 || !node.isVisibleToUser) {
                return@forEach
            }

            val digit = text[0]
            val parentBounds = generateSequence(node.parent) { it.parent }
                .mapNotNull { parent ->
                    Rect().also { parent.getBoundsInScreen(it) }.takeIf { it.width() > bounds.width() && it.height() > bounds.height() }
                }
                .firstOrNull()

            val existing = result[digit]
            if (existing == null || bounds.top > existing.bounds.top) {
                result[digit] = AccountPopupDigitNode(digit, node, bounds, parentBounds)
            }
        }

        result.values.sortedBy { it.bounds.top * 10_000 + it.bounds.left }.forEach { selected ->
            val rawText = selected.node.text?.toString()?.trim().orEmpty()
            val rawDesc = selected.node.contentDescription?.toString()?.trim().orEmpty()
            val rawViewId = selected.node.viewIdResourceName?.substringAfterLast('/')?.trim().orEmpty()
            Log.d(
                TAG,
                "Selected digit node digit=${selected.digit} text=\"${rawText}\" desc=\"${rawDesc}\" viewId=\"${rawViewId}\" bounds=${formatRect(selected.bounds)} parent=${selected.parentBounds?.let { formatRect(it) } ?: "none"}",
            )
        }

        return result
    }

    private fun dumpAccountPopupKeypadNodes(root: AccessibilityNodeInfo, label: String = "accountPopup") {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val popupBounds = Rect().also { root.getBoundsInScreen(it) }
        val keypadTopCutoff = (popupBounds.top + popupBounds.height() * 0.58f).toInt()

        val interesting = nodes.mapNotNull { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName?.substringAfterLast('/')?.trim().orEmpty()
            val className = node.className?.toString()?.substringAfterLast('.')?.trim().orEmpty()
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.width() <= 0 || bounds.height() <= 0 || !node.isVisibleToUser) {
                return@mapNotNull null
            }

            val normalized = listOf(text, desc, viewId).joinToString(" ") { it.lowercase() }
            val hasKeypadLabel = Regex("(^|\\b)[0-9](\\b|$)").containsMatchIn(text) ||
                Regex("(^|\\b)[0-9](\\b|$)").containsMatchIn(desc) ||
                normalized.contains("입력완료") ||
                normalized.contains("비밀번호") ||
                normalized.contains("delete") ||
                normalized.contains("backspace") ||
                normalized.contains("transkey") ||
                normalized.contains("done")

            val isInteresting = bounds.top >= keypadTopCutoff ||
                hasKeypadLabel ||
                viewId.contains("transkey", ignoreCase = true) ||
                viewId.contains("password", ignoreCase = true) ||
                viewId.contains("key", ignoreCase = true)

            if (!isInteresting) {
                return@mapNotNull null
            }

            buildString {
                append("text=")
                append(if (text.isBlank()) "\"\"" else "\"$text\"")
                append(" desc=")
                append(if (desc.isBlank()) "\"\"" else "\"$desc\"")
                append(" viewId=")
                append(if (viewId.isBlank()) "\"\"" else "\"$viewId\"")
                append(" class=")
                append(if (className.isBlank()) "\"\"" else className)
                append(" clickable=")
                append(node.isClickable)
                append(" enabled=")
                append(node.isEnabled)
                append(" bounds=")
                append(bounds.toShortString())
            }
        }.sortedBy { line ->
            val match = Regex("\\[(\\d+),(\\d+)").find(line)
            val top = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: Int.MAX_VALUE
            val left = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE
            top * 10_000 + left
        }

        Log.d(TAG, "[$label] keypad node dump count=${interesting.size}")
        interesting.take(220).forEachIndexed { index, line ->
            Log.d(TAG, "[$label][$index] $line")
        }
    }

    private fun tryAccountPopupDigitStrategies(
        digitNode: AccountPopupDigitNode,
        previousLength: Int,
        maxAttempts: Int,
        forcedStrategyIndex: Int? = null,
    ): Boolean {
        val bounds = digitNode.bounds
        val targetBounds = digitNode.parentBounds ?: bounds
        val clickableAncestor = generateSequence(digitNode.node.parent) { it.parent }.firstOrNull { it.isClickable }
        val strategies = listOf<() -> Boolean>(
            { clickNode(digitNode.node) },
            {
                digitNode.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                digitNode.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                sleep(100)
                clickNode(digitNode.node)
            },
            {
                clickableAncestor?.let {
                    it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    it.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                    sleep(100)
                    it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } ?: false
            },
            { tapCenter(bounds) },
            { tapCenter(targetBounds) },
            { tapPoint(targetBounds.centerX().toFloat(), targetBounds.bottom - maxOf(18, targetBounds.height() / 5).toFloat()) },
            { tapPoint(bounds.centerX().toFloat(), bounds.top + bounds.height() * 0.32f) },
            { tapPoint(bounds.centerX().toFloat(), bounds.bottom - bounds.height() * 0.32f) },
        )

        val indices = forcedStrategyIndex?.let { listOf(it) } ?: (0 until minOf(maxAttempts, strategies.size)).toList()
        for (index in indices) {
            resetPasswordInputTracking()
            waitForRoot(timeoutMs = 500) { isVirtualKeyboardAvailableRoot(it) }?.let {
                logVirtualKeyboardIndicatorState(it, "before-digit-${digitNode.digit}-strategy-${index + 1}")
            }
            val tapped = strategies[index]()
            if (tapped && waitForVirtualKeyboardLengthIncrease(previousLength)) {
                waitForRoot(timeoutMs = 500) { isVirtualKeyboardAvailableRoot(it) }?.let {
                    logVirtualKeyboardIndicatorState(it, "after-digit-${digitNode.digit}-strategy-${index + 1}")
                }
                Log.d(TAG, "Digit ${digitNode.digit} accepted via strategy ${index + 1}")
                return true
            }
            waitForRoot(timeoutMs = 500) { isVirtualKeyboardAvailableRoot(it) }?.let {
                logVirtualKeyboardIndicatorState(it, "rejected-digit-${digitNode.digit}-strategy-${index + 1}")
            }
            sleep(180)
        }

        return false
    }

    private fun completeAccountPasswordEntry(): Boolean {
        val currentRoot = waitForRoot(timeoutMs = 3000) { isVirtualKeyboardAvailableRoot(it) } ?: return false
        val completeNode = firstVisibleNodeByViewId(currentRoot, "transkey_navi_complete_button")
            ?: firstVisibleNodeByViewId(currentRoot, "done")
            ?: findFirstActionableNodeByTextOrDescription(currentRoot, "입력완료")
        if (completeNode != null && clickNode(completeNode)) {
            return waitForRoot(timeoutMs = 5000) { current ->
                !isAccountPasswordPopupRoot(current) && !isTransKeyRoot(current)
            } != null
        }

        val layout = resolveVirtualKeypadProbeLayout(currentRoot) ?: return false
        if (!tapPoint(layout.completePoint.x, layout.completePoint.y)) {
            return false
        }

        return waitForRoot(timeoutMs = 5000) { current ->
            !isAccountPasswordPopupRoot(current) && !isTransKeyRoot(current)
        } != null
    }

    private fun enterAccountPasswordByDigitNodes(
        password: String,
        maxDigits: Int = password.length,
        submitAtEnd: Boolean = true,
        forcedStrategyIndex: Int? = null,
    ): Boolean {
        var expectedLength = virtualKeyboardPasswordLength() ?: 0
        val targetDigits = password.take(maxDigits)
        val perDigitAttempts = if (submitAtEnd) 3 else 1

        for (digit in targetDigits) {
            val root = waitForRoot(timeoutMs = 3000) { isVirtualKeyboardAvailableRoot(it) } ?: return false
            dumpAccountPopupKeypadNodes(root, "enterAccountPasswordByDigitNodes")
            logVirtualKeyboardIndicatorState(root, "enter-before-$digit")
            val digitNodes = collectAccountPopupDigitNodes(root)
            if (digitNodes.size < 8) {
                return false
            }
            val digitNode = digitNodes[digit] ?: return false
            Log.d(
                TAG,
                "Digit-node target $digit bounds=${formatRect(digitNode.bounds)} parent=${digitNode.parentBounds?.let { formatRect(it) } ?: "none"}",
            )
            if (!tryAccountPopupDigitStrategies(digitNode, expectedLength, perDigitAttempts, forcedStrategyIndex)) {
                Log.w(TAG, "Digit-node strategies failed for $digit")
                return false
            }
            expectedLength += 1
            waitForRoot(timeoutMs = 500) { isVirtualKeyboardAvailableRoot(it) }?.let {
                logVirtualKeyboardIndicatorState(it, "enter-after-$digit")
            }
        }

        if (!submitAtEnd) {
            return true
        }

        val autoClosed = waitForRoot(timeoutMs = 2500) { current ->
            !isAccountPasswordPopupRoot(current) && !isTransKeyRoot(current)
        } != null
        if (autoClosed) {
            Log.d(TAG, "Account password popup auto-closed after digit entry")
            return true
        }

        return completeAccountPasswordEntry()
    }

    private fun pressAccountDigitBlind(
        digitNode: AccountPopupDigitNode,
        forcedStrategyIndex: Int? = null,
    ): Boolean {
        val bounds = digitNode.bounds
        val targetBounds = digitNode.parentBounds ?: bounds
        val clickableAncestor = generateSequence(digitNode.node.parent) { it.parent }.firstOrNull { it.isClickable }
        val strategies = listOf<() -> Boolean>(
            { clickNode(digitNode.node) },
            {
                digitNode.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                digitNode.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                sleep(100)
                clickNode(digitNode.node)
            },
            {
                clickableAncestor?.let {
                    it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    it.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                    sleep(100)
                    it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } ?: false
            },
            { tapCenter(bounds) },
            { tapCenter(targetBounds) },
            { tapPoint(targetBounds.centerX().toFloat(), targetBounds.bottom - maxOf(18, targetBounds.height() / 5).toFloat()) },
            { tapPoint(bounds.centerX().toFloat(), bounds.top + bounds.height() * 0.32f) },
            { tapPoint(bounds.centerX().toFloat(), bounds.bottom - bounds.height() * 0.32f) },
        )
        val indices = forcedStrategyIndex?.let { listOf(it) } ?: strategies.indices.toList()
        for (index in indices) {
            if (strategies[index]()) {
                Log.d(TAG, "Digit ${digitNode.digit} blindly pressed via strategy ${index + 1}")
                sleep(220)
                return true
            }
            sleep(120)
        }
        return false
    }

    private fun enterAccountPasswordByDigitNodesBlind(
        password: String,
        submitAtEnd: Boolean = true,
        forcedStrategyIndex: Int? = null,
    ): Boolean {
        for (digit in password) {
            val root = waitForRoot(timeoutMs = 3000) { isVirtualKeyboardAvailableRoot(it) } ?: return false
            val digitNodes = collectAccountPopupDigitNodes(root)
            if (digitNodes.size < 8) {
                return false
            }
            val digitNode = digitNodes[digit] ?: return false
            Log.d(
                TAG,
                "Blind digit target $digit bounds=${formatRect(digitNode.bounds)} parent=${digitNode.parentBounds?.let { formatRect(it) } ?: "none"}",
            )
            if (!pressAccountDigitBlind(digitNode, forcedStrategyIndex)) {
                Log.w(TAG, "Blind digit press failed for $digit")
                return false
            }
        }

        if (!submitAtEnd) {
            return true
        }

        return completeAccountPasswordEntry()
    }

    private fun enterAccountPasswordByAccessibleDigits(password: String): Boolean {
        if (enterAccountPasswordByDigitNodesBlind(password, forcedStrategyIndex = 0)) {
            return true
        }
        if (enterAccountPasswordByDigitNodesBlind(password, forcedStrategyIndex = 1)) {
            return true
        }
        if (enterAccountPasswordByDigitNodes(password, forcedStrategyIndex = 0)) {
            return true
        }
        if (enterAccountPasswordByDigitNodes(password, forcedStrategyIndex = 1)) {
            return true
        }
        if (enterAccountPasswordByDigitNodes(password, forcedStrategyIndex = 2)) {
            return true
        }
        return enterAccountPasswordByDigitNodesBlind(password, forcedStrategyIndex = 2)
    }

    private fun blindClearAccountPasswordInput(maxPresses: Int = 4) {
        val root = waitForRoot(timeoutMs = 2000) { isAccountPasswordPopupRoot(it) && isVirtualKeyboardAvailableRoot(it) } ?: return
        val deleteNode = firstVisibleNodeByViewId(root, "ib_clear")
            ?: findFirstActionableNodeByTextOrDescription(root, "삭제")
            ?: findFirstActionableNodeByTextOrDescription(root, "backspace")
        repeat(maxPresses) {
            if (deleteNode != null) {
                clickNode(deleteNode)
            } else {
                val layout = resolveVirtualKeypadProbeLayout(root) ?: return
                tapPoint(layout.backspacePoint.x, layout.backspacePoint.y)
            }
            sleep(150)
        }
    }

    private fun testAccountPasswordSingleDigit(forcedStrategyIndex: Int? = null) {
        Thread(Runnable {
            try {
                Log.d(TAG, "단일 계좌 비밀번호 숫자 입력 테스트 시작 strategy=${forcedStrategyIndex ?: "all"}")
                if (openAccountPasswordPopupBlocking() == null) {
                    Log.w(TAG, "단일 테스트: 비밀번호 팝업을 열지 못했습니다")
                    return@Runnable
                }
                if (!openAccountPasswordKeyboard()) {
                    Log.w(TAG, "단일 테스트: 비밀번호 키패드를 열지 못했습니다")
                    return@Runnable
                }

                val root = waitForRoot(timeoutMs = 3000) { isAccountPasswordPopupRoot(it) && isVirtualKeyboardAvailableRoot(it) }
                    ?: run {
                        Log.w(TAG, "단일 테스트: 키패드 루트를 찾지 못했습니다")
                        return@Runnable
                    }
                dumpAccountPopupKeypadNodes(root, "singleDigitTest")
                logVirtualKeyboardIndicatorState(root, "single-before")
                blindClearAccountPasswordInput()
                val digitNodes = collectAccountPopupDigitNodes(root)
                val targetDigit = '7'
                val digitNode = digitNodes[targetDigit]
                    ?: run {
                        Log.w(TAG, "단일 테스트: 숫자 $targetDigit 노드를 찾지 못했습니다")
                        return@Runnable
                    }
                if (forcedStrategyIndex != null) {
                    if (!pressAccountDigitBlind(digitNode, forcedStrategyIndex)) {
                        Log.w(TAG, "단일 테스트: 숫자 $targetDigit blind strategy $forcedStrategyIndex 실패")
                        return@Runnable
                    }
                    Log.d(TAG, "단일 테스트: 숫자 $targetDigit blind strategy $forcedStrategyIndex 실행 완료")
                } else {
                    if (!tryAccountPopupDigitStrategies(digitNode, previousLength = 0, maxAttempts = 7)) {
                        Log.w(TAG, "단일 테스트: 숫자 $targetDigit 입력 감지 실패")
                        return@Runnable
                    }
                }

                sleep(300)
                val afterRoot = waitForRoot(timeoutMs = 1500) { isAccountPasswordPopupRoot(it) && isVirtualKeyboardAvailableRoot(it) } ?: root
                logVirtualKeyboardIndicatorState(afterRoot, "single-after")
            } catch (t: Throwable) {
                Log.e(TAG, "단일 계좌 비밀번호 숫자 입력 테스트 실패", t)
            }
        }).start()
    }

    private fun testAccountPasswordFillNoSubmit() {
        Thread {
            try {
                val popup = waitForRoot(timeoutMs = 1500) { isAccountPasswordPopupRoot(it) }
                    ?: openAccountPasswordPopupBlocking(requirePopup = true)
                if (popup == null) {
                    Log.w(TAG, "무제출 테스트: 비밀번호 팝업을 열지 못했습니다")
                    return@Thread
                }
                val target = resolveRetirementAccountTargetFromRoot(popup)
                    ?: waitForMtsWindow(timeoutMs = 2000)?.let { resolveRetirementAccountTargetFromRoot(it) }
                if (target == null) {
                    Log.w(TAG, "무제출 테스트: 현재 계좌 유형을 판별하지 못했습니다")
                    return@Thread
                }
                val password = accountPasswordFor(target)
                if (password.isBlank()) {
                    Log.w(TAG, "무제출 테스트: 저장된 계좌 비밀번호가 없습니다")
                    return@Thread
                }
                if (!openAccountPasswordKeyboard()) {
                    Log.w(TAG, "무제출 테스트: 키패드를 열지 못했습니다")
                    return@Thread
                }
                blindClearAccountPasswordInput()
                val success = enterAccountPasswordByDigitNodesBlind(password, submitAtEnd = false, forcedStrategyIndex = 0)
                Log.d(TAG, "무제출 테스트: blind strategy 0 full password result=$success")
            } catch (t: Throwable) {
                Log.e(TAG, "무제출 테스트 실패", t)
            }
        }.start()
    }

    private fun completeVirtualKeyboardEntry(
        exitPredicate: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        val root = waitForRoot(timeoutMs = 3000) { isTransKeyRoot(it) } ?: return false
        val completeButton = firstVisibleNodeByViewId(root, "transkey_navi_complete_button")
            ?: firstVisibleNodeByViewId(root, "done")
            ?: findFirstActionableNodeByTextOrDescription(root, "입력완료")
        if (completeButton == null) {
            return completeVirtualKeyboardEntryByOcr(root, exitPredicate)
        }

        if (!clickNode(completeButton)) {
            return false
        }

        return waitForRoot(timeoutMs = 5000) { exitPredicate(it) } != null
    }

    private fun completeVirtualKeyboardEntryByOcr(
        root: AccessibilityNodeInfo,
        exitPredicate: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        val keyboardBounds = virtualKeyboardBounds(root)
        val boxes = readVisibleTextBoxes() ?: return false
        val completeBox = boxes
            .filter { keyboardBounds.intersectedWith(it.bounds) }
            .firstOrNull { normalizeOcrLabel(it.text).contains("입력완료") }
            ?: boxes
                .filter { keyboardBounds.intersectedWith(it.bounds) }
                .maxByOrNull { it.bounds.centerY() * 10_000 + it.bounds.centerX() }

        if (completeBox == null || !tapCenter(completeBox.bounds)) {
            return false
        }

        return waitForRoot(timeoutMs = 5000) { exitPredicate(it) } != null
    }

    private fun readVisibleTextBoxes(): List<OcrTextBox>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Accessibility screenshot OCR requires Android 11(API 30)+")
            return null
        }

        val bitmap = takeCurrentScreenshotBitmap() ?: return null
        return try {
            recognizeTextBoxes(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun takeCurrentScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        val latch = CountDownLatch(1)
        val bitmapRef = AtomicReference<Bitmap?>()
        val errorRef = AtomicReference<Int?>()

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    bitmapRef.set(bitmap)
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    errorRef.set(errorCode)
                    latch.countDown()
                }
            },
        )

        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out while taking accessibility screenshot")
            return null
        }

        errorRef.get()?.let { Log.w(TAG, "Accessibility screenshot failed: $it") }
        return bitmapRef.get()
    }

    private fun recognizeTextBoxes(bitmap: Bitmap): List<OcrTextBox>? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<List<OcrTextBox>?>()
        val errorRef = AtomicReference<Exception?>()

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val boxes = visionText.textBlocks.flatMap { block ->
                    block.lines.flatMap { line ->
                        line.elements.mapNotNull { element ->
                            val bounds = element.boundingBox
                            if (bounds == null) {
                                null
                            } else {
                                OcrTextBox(element.text, bounds)
                            }
                        }
                    }
                }
                resultRef.set(boxes)
                latch.countDown()
            }
            .addOnFailureListener { e ->
                errorRef.set(e)
                latch.countDown()
            }

        if (!latch.await(4, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out while recognizing keyboard text")
            return null
        }

        errorRef.get()?.let { Log.w(TAG, "Keyboard OCR failed: ${it.message}") }
        return resultRef.get()
    }

    private fun virtualKeyboardBounds(root: AccessibilityNodeInfo): Rect {
        val keyboardNode = firstVisibleNodeByViewId(root, "fl_transkey")
            ?: firstVisibleNodeByViewId(root, "keypadContainer")
        if (keyboardNode != null) {
            val bounds = Rect()
            keyboardNode.getBoundsInScreen(bounds)
            return bounds
        }

        val popupKeypadBounds = accountPopupKeypadBounds(root)
        if (popupKeypadBounds != null) {
            return popupKeypadBounds
        }

        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        return bounds
    }

    private fun accountPopupKeypadBounds(root: AccessibilityNodeInfo): Rect? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)

        val keyRects = nodes.mapNotNull { node ->
            val text = nodeText(node)
            val isKey = text.matches(Regex("\\d")) ||
                text.contains("입력완료") ||
                text.contains("삭제") ||
                text.contains("backspace", ignoreCase = true)
            if (!isKey) {
                null
            } else {
                Rect().also { node.getBoundsInScreen(it) }.takeIf { it.width() > 0 && it.height() > 0 }
            }
        }

        if (keyRects.size < 6) {
            return null
        }

        val popupRootBounds = Rect()
        root.getBoundsInScreen(popupRootBounds)

        val union = Rect(keyRects.first())
        for (rect in keyRects.drop(1)) {
            union.union(rect)
        }
        if (popupRootBounds.width() > 0 && popupRootBounds.height() > 0) {
            union.left = popupRootBounds.left
            union.right = popupRootBounds.right
        }
        return union
    }

    private fun tapCenter(bounds: Rect): Boolean {
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        return tapPoint(x, y)
    }

    private fun tapPoint(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val latch = CountDownLatch(1)
        val successRef = AtomicReference(false)

        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    successRef.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null,
        )

        latch.await(1, TimeUnit.SECONDS)
        return successRef.get()
    }

    private fun swipe(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        val latch = CountDownLatch(1)
        val successRef = AtomicReference(false)

        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    successRef.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null,
        )

        latch.await(2, TimeUnit.SECONDS)
        return successRef.get()
    }

    private fun normalizeOcrLabel(value: String): String {
        return value
            .trim()
            .replace(" ", "")
            .replace("\n", "")
            .replace("|", "I")
            .lowercase()
    }

    private fun Rect.intersectedWith(other: Rect): Boolean {
        return left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    private fun submitCertificateLogin(): Boolean {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            val root = findCertLoginRootInAnyWindow()
            if (root != null) {
                val submitButton = firstVisibleNodeByViewId(root, "btn_cert_login_start")
                if (submitButton != null && submitButton.isEnabled) {
                    Log.d(TAG, "Login button enabled, clicking")
                    return clickNode(submitButton)
                }
                Log.d(TAG, "Login button not yet enabled, waiting...")
            }
            sleep(500)
        }

        Log.w(TAG, "Timeout waiting for login button to enable, attempting click anyway")
        val root = waitForRoot(timeoutMs = 3000) { isCertLoginRoot(it) } ?: return false
        val submitButton = firstVisibleNodeByViewId(root, "btn_cert_login_start")
        if (submitButton == null) {
            Log.e(TAG, "Login button not found on cert login screen")
            return false
        }
        Log.w(TAG, "Login button isEnabled=${submitButton.isEnabled}")
        return clickNode(submitButton)
    }

    private fun waitForCertificateLoginResult(): Boolean {
        return waitForRoot(timeoutMs = 15000) { root ->
            !isCertLoginRoot(root) && !isTransKeyRoot(root)
        } != null
    }

    private fun waitForCertLoaded(): Boolean {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val root = findCertLoginRootInAnyWindow()
            if (root != null) {
                val certInfo = firstVisibleNodeByViewId(root, "cl_cert_login_info")
                val certInfoEmpty = firstVisibleNodeByViewId(root, "cl_cert_login_info_empty")
                if (certInfo != null && certInfo.isVisibleToUser && certInfoEmpty?.isVisibleToUser != true) {
                    val subjectName = firstVisibleNodeByViewId(root, "tv_cert_login_info_subject_name")
                    Log.d(TAG, "Cert loaded: ${subjectName?.text}")
                    return true
                }
            }
            sleep(300)
        }
        return false
    }

    private fun dumpCertLoginState() {
        val root = findCertLoginRootInAnyWindow() ?: rootInActiveWindow ?: run {
            Log.w(TAG, "[dumpCertLoginState] rootInActiveWindow is null")
            return
        }
        if (root.packageName?.toString() != MTS_PACKAGE) {
            Log.w(TAG, "[dumpCertLoginState] Not in MTS app: ${root.packageName}")
            return
        }

        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        Log.d(TAG, "[dumpCertLoginState] Total nodes: ${nodes.size}")

        val certInfo = firstVisibleNodeByViewId(root, "cl_cert_login_info")
        val certInfoEmpty = firstVisibleNodeByViewId(root, "cl_cert_login_info_empty")
        val subjectName = firstVisibleNodeByViewId(root, "tv_cert_login_info_subject_name")
        val passwordField = firstVisibleNodeByViewId(root, "tf_cert_login_password")
        val loginButton = firstVisibleNodeByViewId(root, "btn_cert_login_start")

        Log.d(TAG, "[dumpCertLoginState] certInfo=${certInfo != null} visible=${certInfo?.isVisibleToUser}")
        Log.d(TAG, "[dumpCertLoginState] certInfoEmpty=${certInfoEmpty != null} visible=${certInfoEmpty?.isVisibleToUser}")
        Log.d(TAG, "[dumpCertLoginState] subjectName=${subjectName?.text}")
        Log.d(TAG, "[dumpCertLoginState] passwordField=${passwordField != null} text=${passwordField?.text}")
        Log.d(TAG, "[dumpCertLoginState] loginButton=${loginButton != null} enabled=${loginButton?.isEnabled}")
    }

    private fun isCertLoginRoot(root: AccessibilityNodeInfo): Boolean {
        return hasViewId(root, "cl_cert_login_content") ||
            hasViewId(root, "tv_cert_login_title") ||
            hasViewId(root, "btn_cert_login_start") ||
            hasViewId(root, "tf_cert_login_password")
    }

    private fun isTransKeyRoot(root: AccessibilityNodeInfo): Boolean {
        return hasViewId(root, "fl_transkey") ||
            hasViewId(root, "keypadContainer") ||
            hasViewId(root, "transkey_navi_complete_button")
    }

    private fun waitForRoot(
        timeoutMs: Long,
        intervalMs: Long = 250,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val matched = findRootInAnyWindow(predicate)
            if (matched != null) {
                return matched
            }
            sleep(intervalMs)
        }
        return null
    }

    private fun waitForMtsWindow(timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val mtsRoot = findMtsRootInAnyWindow()
            if (mtsRoot != null) {
                return mtsRoot
            }
            sleep(250)
        }
        return null
    }

    private fun findCertLoginRootInAnyWindow(): AccessibilityNodeInfo? {
        return findRootInAnyWindow { isCertLoginRoot(it) }
    }

    private fun findMtsRootInAnyWindow(): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot.packageName?.toString() == MTS_PACKAGE) {
            return activeRoot
        }

        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == MTS_PACKAGE) {
                return root
            }
        }

        return null
    }

    private fun findRootInAnyWindow(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && predicate(activeRoot)) {
            return activeRoot
        }

        for (window in windows) {
            val root = window.root ?: continue
            if (predicate(root)) {
                return root
            }
        }

        return null
    }

    private fun hasViewId(root: AccessibilityNodeInfo, id: String): Boolean {
        return findVisibleNodesByViewId(root, id).isNotEmpty()
    }

    private fun findVisibleNodesByViewId(root: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> {
        return root.findAccessibilityNodeInfosByViewId("$MTS_PACKAGE:id/$id")
            ?.filter { it.isVisibleToUser }
            .orEmpty()
    }

    private fun firstVisibleNodeByViewId(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        return findVisibleNodesByViewId(root, id).firstOrNull()
    }

    private fun findFirstActionableNodeByTextOrDescription(
        root: AccessibilityNodeInfo,
        query: String,
    ): AccessibilityNodeInfo? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        return nodes.firstOrNull { node ->
            val text = nodeText(node)
            text.contains(query) && isActionableNode(node)
        }
    }

    private fun isActionableNode(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || node.parent?.isClickable == true
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        return node.text?.toString()?.trim().orEmpty()
            .ifBlank { node.contentDescription?.toString()?.trim().orEmpty() }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun getBalanceFrom7201() {
        Thread {
            try {
                Log.d(TAG, "퇴직연금 ETF/리츠 잔고 조회 시작")

                if (!navigateToRetirementBalanceScreen()) {
                    sendBalanceData("오류: 퇴직연금 ETF 리츠 잔고 화면으로 이동하지 못했습니다")
                    return@Thread
                }

                val screenRoot = waitForMtsWindow(timeoutMs = 5000)
                if (screenRoot == null) {
                    sendBalanceData("오류: 퇴직연금 잔고 화면을 읽을 수 없습니다")
                    return@Thread
                }

                val snapshots = mutableListOf<RetirementHoldingSnapshot>()
                for (target in RETIREMENT_ACCOUNT_TARGETS) {
                    Log.d(TAG, "${target.type} 계좌 처리 시작")
                    if (snapshots.isEmpty()) {
                        navigateToRetirementBalanceScreen()
                        Log.d(TAG, "${target.type} 잔고 화면 재진입 시도 완료")
                    }
                    if (!selectRetirementAccount(target)) {
                        snapshots.add(
                            RetirementHoldingSnapshot(
                                accountType = target.type,
                                accountLabel = target.accountPrefix,
                                itemName = null,
                                ticker = null,
                                sellableQuantity = null,
                                averagePurchasePrice = null,
                            )
                        )
                        Log.w(TAG, "${target.type} 계좌 전환 실패")
                        continue
                    }

                    val refreshedRoot = waitForMtsWindow(timeoutMs = 5000)
                    if (refreshedRoot == null) {
                        Log.w(TAG, "${target.type} 계좌 화면을 읽을 수 없습니다")
                        snapshots.add(
                            RetirementHoldingSnapshot(
                                accountType = target.type,
                                accountLabel = target.accountPrefix,
                                itemName = null,
                                ticker = null,
                                sellableQuantity = null,
                                averagePurchasePrice = null,
                            )
                        )
                        continue
                    }

                    val snapshot = extractRetirementHoldingSnapshot(target, refreshedRoot)
                    snapshots.add(snapshot)
                    Log.d(TAG, "${target.type} 잔고 추출 결과: $snapshot")
                    Log.d(TAG, "${target.type} 계좌 처리 종료")
                }

                val balanceData = formatRetirementHoldings(snapshots)
                sendBalanceData(balanceData)
                Log.d(TAG, "퇴직연금 ETF/리츠 잔고 조회 완료")
            } catch (e: Exception) {
                Log.e(TAG, "잔고 조회 중 오류 발생", e)
                sendBalanceData("오류: ${e.message}")
            } finally {
                clearPendingCommand()
            }
        }.start()
    }

    private fun navigateToRetirementBalanceScreen(): Boolean {
        Log.d(TAG, "퇴직연금 잔고 화면 진입 시작")
        var root = waitForMtsWindow(timeoutMs = 5000)
        if (root == null) {
            launchMtsApp()
            root = waitForMtsWindow(timeoutMs = 12000)
        }

        if (root == null) {
            Log.w(TAG, "MTS 접근성 root를 아직 잡지 못했지만 7202 직접 진입을 시도합니다")
        }
        if (root != null && isRetirementBalanceScreen(root)) {
            Log.d(TAG, "이미 퇴직연금 잔고 화면에 있음")
            return true
        }

        navigateToRetirementBalanceDirectScreen()
        val directRoot = waitForRetirementBalanceScreen(timeoutMs = 12000)
        if (directRoot != null) {
            Log.d(TAG, "직접 진입으로 퇴직연금 잔고 화면 도달")
            return true
        }

        val fallbackRoot = waitForMtsWindow(timeoutMs = 5000) ?: root ?: return false
        if (!openBottomMenu(fallbackRoot)) {
            Log.w(TAG, "하단 메뉴 열기 실패")
            return false
        }

        val menuRoot = waitForMtsWindow(timeoutMs = 5000) ?: return false
        if (!clickTextOrOcr(menuRoot, listOf("연금"))) {
            Log.w(TAG, "연금 메뉴 클릭 실패")
            return false
        }

        sleep(1500)
        val pensionRoot = waitForMtsWindow(timeoutMs = 5000) ?: return false
        if (isRetirementBalanceScreen(pensionRoot)) {
            Log.d(TAG, "연금 메뉴 진입 후 잔고 화면 도달")
            return true
        }

        val targetQueries = listOf(
            "퇴직연금ETF리츠 잔고",
            "퇴직연금 ETF 리츠 잔고",
            "퇴직연금ETF/리츠 잔고",
            "퇴직연금 ETF/리츠 잔고",
        )
        if (!clickTextOrOcr(pensionRoot, targetQueries)) {
            Log.w(TAG, "퇴직연금 ETF/리츠 잔고 메뉴 클릭 실패")
            return false
        }

        sleep(2000)
        val finalRoot = waitForMtsWindow(timeoutMs = 8000) ?: return false
        val success = isRetirementBalanceScreen(finalRoot)
        Log.d(TAG, "퇴직연금 잔고 화면 진입 결과=$success")
        return success
    }

    private fun waitForRetirementBalanceScreen(timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = waitForMtsWindow(timeoutMs = 1500)
            if (root != null && isRetirementBalanceScreen(root)) {
                return root
            }
            sleep(500)
        }
        return null
    }

    private fun waitForRetirementOrderScreen(timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = waitForMtsWindow(timeoutMs = 1500)
            if (root != null && isRetirementOrderScreen(root)) {
                return root
            }
            sleep(500)
        }
        return null
    }

    private fun openBottomMenu(root: AccessibilityNodeInfo): Boolean {
        if (clickBottomMenuButton(root)) {
            sleep(1500)
            return true
        }

        if (closeBottomRightTickerOverlay(root, allowCoordinateFallback = true)) {
            sleep(700)
            val refreshedRoot = waitForMtsWindow(timeoutMs = 3000) ?: root
            if (clickBottomMenuButton(refreshedRoot)) {
                sleep(1500)
                return true
            }
        }

        if (clickTextOrOcr(root, listOf("메뉴"))) {
            sleep(1500)
            return true
        }

        return false
    }

    private fun tapBottomMenuFallback(root: AccessibilityNodeInfo): Boolean {
        return tapBottomNavigationByLabel(root, "메뉴")
    }

    private fun clickTextOrOcr(root: AccessibilityNodeInfo, queries: List<String>): Boolean {
        if (clickTextNode(root, queries)) {
            return true
        }

        val boxes = readVisibleTextBoxes() ?: return false
        val normalizedQueries = queries.map { normalizeOcrLabel(it) }
        val matches = boxes.filter { box ->
            val normalized = normalizeOcrLabel(box.text)
            normalizedQueries.any { query ->
                normalized.contains(query) || query.contains(normalized)
            }
        }
        if (matches.isEmpty()) {
            return false
        }

        val candidate = if (normalizedQueries.any { it == normalizeOcrLabel("메뉴") }) {
            matches.maxByOrNull { it.bounds.centerY() * 10_000 + it.bounds.centerX() }
        } else {
            matches.minByOrNull { it.bounds.top * 10_000 + it.bounds.left }
        } ?: return false

        Log.d(TAG, "OCR tap matched text='${candidate.text}' for queries=$queries")
        return tapCenter(candidate.bounds)
    }

    private fun clickTextNode(root: AccessibilityNodeInfo, queries: List<String>): Boolean {
        for (query in queries) {
            val actionable = findFirstActionableNodeByTextOrDescription(root, query)
            if (actionable != null && clickNode(actionable)) {
                return true
            }

            val node = findNodeByText(root, query)
            if (node != null && clickNode(node)) {
                return true
            }
        }

        return false
    }

    private fun isRetirementBalanceScreen(root: AccessibilityNodeInfo): Boolean {
        if (hasRetirementListMarkers(root)) {
            return true
        }

        val texts = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, texts)
        val visibleTexts = texts.map { nodeText(it) }.filter { it.isNotBlank() }
        if (isRetirementOrderLikeScreen(visibleTexts)) {
            return false
        }

        val normalizedTexts = visibleTexts.map { normalizeOcrLabel(it) }
        val hasRetirementTitle = normalizedTexts.any { it.contains(normalizeOcrLabel("퇴직연금")) } &&
            normalizedTexts.any { it.contains(normalizeOcrLabel("리츠")) } &&
            normalizedTexts.any { it.contains(normalizeOcrLabel("잔고")) }
        val balanceHeaders = listOf("종목명", "매도가능", "매입단가", "보유수량", "매수금액", "평가손익", "매도가")
        val headerMatches = visibleTexts.count { text -> balanceHeaders.any { header -> text.contains(header) } }

        return hasRetirementTitle &&
            visibleTexts.any { it.contains("보유잔고") || it.contains("개인형IRP") || it.contains("DC") } &&
            headerMatches >= 2
    }

    private fun isRetirementOrderScreen(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val visibleTexts = nodes.map { nodeText(it) }.filter { it.isNotBlank() }
        val homeMarkers = listOf("ETF마켓", "IMA", "ISA", "발행어음", "연금 ETF", "장외채권", "해외채권", "RIA")
        if (homeMarkers.count { marker -> visibleTexts.any { it.contains(marker) } } >= 3) {
            return false
        }

        val normalizedTexts = visibleTexts.map { normalizeOcrLabel(it) }
        val hasRetirementTitle = normalizedTexts.any { it.contains(normalizeOcrLabel("퇴직연금")) }
        val hasOrderContext = normalizedTexts.any { it.contains(normalizeOcrLabel("주문")) } ||
            normalizedTexts.any { it.contains(normalizeOcrLabel("ETF리츠")) } ||
            normalizedTexts.any { it.contains(normalizeOcrLabel("ETF/리츠")) }
        val hasPasswordButton = normalizedTexts.any { it.contains(normalizeOcrLabel("비밀번호")) }
        val hasUserTab = normalizedTexts.any { it.contains(normalizeOcrLabel("사용자")) }
        val hasSelectedRetirementOrderTab = normalizedTexts.any { text ->
            text == normalizeOcrLabel("퇴직주문") || text.contains(normalizeOcrLabel("퇴직주문"))
        }
        val hasTradeAction = normalizedTexts.any { text ->
            text.contains(normalizeOcrLabel("매수")) || text.contains(normalizeOcrLabel("매도"))
        }
        val unlockedMarkerCount = listOf("KRX", "지정가", "시장가", "주문금액", "사용자")
            .count { marker -> visibleTexts.any { it.contains(marker) } }
        val strongOrderMarkers = listOf("호가", "지정가", "시장가", "주문금액", "사용자", "가입자1", "가입자2", "비밀번호")
            .count { marker -> visibleTexts.any { it.contains(marker) } }

        return ((hasRetirementTitle && hasOrderContext && strongOrderMarkers >= 2) ||
            (hasPasswordButton && hasUserTab && hasTradeAction && hasSelectedRetirementOrderTab) ||
            (hasTradeAction && unlockedMarkerCount >= 4)) &&
            !hasRetirementListMarkers(root)
    }

    private fun openRetirementBalanceTab(): Boolean {
        val root = waitForMtsWindow(timeoutMs = 5000) ?: return false

        if (hasRetirementBalanceMarkers(root)) {
            return true
        }

        val candidates = listOf("보유잔고 조회", "보유잔고", "잔고")
        for (candidate in candidates) {
            val node = findFirstActionableNodeByTextOrDescription(root, candidate)
                ?: findNodeByText(root, candidate)
            if (node != null && clickNode(node)) {
                sleep(1500)
                val updatedRoot = waitForMtsWindow(timeoutMs = 5000) ?: return false
                if (hasRetirementBalanceMarkers(updatedRoot)) {
                    return true
                }
            }
        }

        return false
    }

    private fun hasRetirementBalanceMarkers(root: AccessibilityNodeInfo): Boolean {
        return hasRetirementListMarkers(root)
    }

    private fun isRetirementOrderLikeScreen(texts: List<String>): Boolean {
        val markers = listOf("호가", "지정가", "시장가", "주문단가", "미체결량", "투자한도", "현금매도가능", "주문금액", "가입자1", "가입자2", "사용자")
        return markers.count { marker -> texts.any { it.contains(marker) } } >= 2
    }

    private fun hasRetirementListMarkers(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val visibleTexts = nodes.map { nodeText(it) }.filter { it.isNotBlank() }
        if (isRetirementOrderLikeScreen(visibleTexts)) {
            return false
        }

        val balanceHeaders = listOf("종목명", "매도가능", "매입단가", "보유수량", "매수금액", "평가손익", "매도가")
        val headerMatches = balanceHeaders.count { header -> visibleTexts.any { it.contains(header) } }

        return visibleTexts.any { it.contains("퇴직연금") } &&
            visibleTexts.any { it.contains("잔고") } &&
            visibleTexts.any { it.contains("보유잔고") || it.contains("개인형IRP") || it.contains("DC") } &&
            headerMatches >= 2
    }

    private fun openRetirementDetailTab(): Boolean {
        val root = waitForMtsWindow(timeoutMs = 5000) ?: return false
        val detailTab = findRetirementDetailTabNode(root)
            ?: return false
        if (!clickNode(detailTab)) {
            val bounds = Rect()
            detailTab.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0 || !tapCenter(bounds)) {
                return false
            }
        }

        sleep(1500)
        val updatedRoot = waitForMtsWindow(timeoutMs = 5000) ?: return false
        return hasRetirementListMarkers(updatedRoot) || scrollForRetirementDetail(updatedRoot)
    }

    private fun scrollForRetirementDetail(root: AccessibilityNodeInfo): Boolean {
        if (hasRetirementListMarkers(root)) {
            return true
        }

        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false
        }

        repeat(3) {
            val centerX = bounds.centerX().toFloat()
            val startY = bounds.top + bounds.height() * 0.72f
            val endY = bounds.top + bounds.height() * 0.40f
            if (!swipe(centerX, startY, centerX, endY)) {
                return@repeat
            }
            sleep(1500)
            val updatedRoot = waitForMtsWindow(timeoutMs = 4000) ?: return false
            if (hasRetirementListMarkers(updatedRoot)) {
                return true
            }
        }

        return false
    }

    private fun findRetirementDetailTabNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        return nodes
            .filter { node ->
                val text = nodeText(node)
                if (!text.contains("잔고")) {
                    return@filter false
                }
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                bounds.width() > 0 && bounds.height() > 0 && bounds.centerY() < 1200
            }
            .sortedByDescending { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                bounds.centerX() * 10_000 - bounds.centerY()
            }
            .firstOrNull()
    }

    private fun selectRetirementAccount(target: RetirementAccountTarget): Boolean {
        val root = waitForMtsWindow(timeoutMs = 5000) ?: return false
        if (isAccountPasswordPopupRoot(root)) {
            return enterAccountPassword(target)
        }
        if (isTargetAccountVisible(root, target)) {
            return true
        }

        val dropdown = findRetirementAccountDropdown(root)
        if ((dropdown == null || !clickNode(dropdown)) && !clickTextOrOcr(root, listOf("DC", "IRP", "개인형IRP"))) {
            return false
        }

        sleep(1500)
        val selectionRoot = waitForMtsWindow(timeoutMs = 5000) ?: return false
        val targetNode = findRetirementAccountNode(selectionRoot, target)
        if ((targetNode == null || !clickNode(targetNode)) && !clickTextOrOcr(selectionRoot, accountQueriesFor(target))) {
            return false
        }

        sleep(2000)
        val updatedRoot = waitForMtsWindow(timeoutMs = 5000) ?: return false
        if (isAccountPasswordPopupRoot(updatedRoot)) {
            return enterAccountPassword(target)
        }
        return isTargetAccountVisible(updatedRoot, target)
    }

    private fun isTargetAccountVisible(root: AccessibilityNodeInfo, target: RetirementAccountTarget): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val queries = accountQueriesFor(target).map { normalizeOcrLabel(it) }
        return nodes.any { node ->
            val normalized = normalizeOcrLabel(nodeText(node))
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.centerY() < 700 &&
                (normalized.contains("-") || normalized.contains(normalizeOcrLabel(target.accountPrefix))) &&
                queries.any { query -> normalized.contains(query) }
        }
    }

    private fun findRetirementAccountDropdown(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        return nodes.firstOrNull { node ->
            val text = nodeText(node)
            val normalized = normalizeOcrLabel(text)
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.centerY() < 700 &&
            text.contains('-') && (
                normalized.contains(normalizeOcrLabel("개인형IRP")) ||
                    normalized.contains(normalizeOcrLabel("IRP")) ||
                    normalized.contains(normalizeOcrLabel("DC"))
                )
        } ?: nodes.firstOrNull { node ->
            val normalized = normalizeOcrLabel(nodeText(node))
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.centerY() < 700 && (
                normalized.contains(normalizeOcrLabel("개인형IRP")) ||
                    normalized.contains(normalizeOcrLabel("IRP")) ||
                    normalized.contains(normalizeOcrLabel("DC"))
                )
        }
    }

    private fun findRetirementAccountNode(
        root: AccessibilityNodeInfo,
        target: RetirementAccountTarget,
    ): AccessibilityNodeInfo? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val queries = accountQueriesFor(target).map { normalizeOcrLabel(it) }
        return nodes.firstOrNull { node ->
            val normalized = normalizeOcrLabel(nodeText(node))
            (normalized.contains("-") || normalized.contains(normalizeOcrLabel(target.accountPrefix))) &&
                queries.any { query -> normalized.contains(query) }
        }
    }

    private fun accountQueriesFor(target: RetirementAccountTarget): List<String> {
        return listOf(target.accountName, target.type)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractRetirementHoldingSnapshot(
        target: RetirementAccountTarget,
        root: AccessibilityNodeInfo,
    ): RetirementHoldingSnapshot {
        val preparedRoot = if (isAccountPasswordPopupRoot(root) && enterAccountPassword(target)) {
            waitForMtsWindow(timeoutMs = 5000) ?: root
        } else {
            root
        }
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(preparedRoot, nodes)

        val accountLabel = nodes.map { nodeText(it) }
            .firstOrNull { text ->
                text.contains(target.accountPrefix) ||
                    (text.contains(target.accountName) && text.contains(target.type))
            }
            ?: target.accountPrefix

        val listSnapshot = extractListSnapshot(preparedRoot)

        val itemName = listSnapshot.itemName ?: extractHoldingName(nodes)
        val persistedTicker = itemName?.let { configManager.loadHoldingTickerMap()[normalizeHoldingKey(it)] }
        val explicitTicker = extractTicker(itemName)
        val discoveredTicker = if (persistedTicker == null && explicitTicker == null) {
            discoverTickerForHolding(target, preparedRoot, itemName)
        } else {
            null
        }
        val inferredTicker = persistedTicker ?: explicitTicker ?: discoveredTicker
        val sellableQuantity = listSnapshot.sellableQuantity
            ?: extractGridColumnFallback(preparedRoot, "매도가능")
            ?: extractListValue(nodes, "보유수량")
        val averagePurchasePrice = listSnapshot.averagePurchasePrice
            ?: extractGridColumnFallback(preparedRoot, "매입단가")

        return RetirementHoldingSnapshot(
            accountType = target.type,
            accountLabel = accountLabel,
            itemName = itemName,
            ticker = inferredTicker,
            sellableQuantity = sellableQuantity,
            averagePurchasePrice = averagePurchasePrice,
        )
    }

    private fun extractHoldingName(nodes: List<AccessibilityNodeInfo>): String? {
        val texts = nodes.map { nodeText(it) }
        val sellableIndex = texts.indexOfFirst { it.contains("매도가능") }
        if (sellableIndex > 0) {
            for (index in sellableIndex - 1 downTo 0) {
                val candidate = texts[index].trim()
                if (isHoldingNameCandidate(candidate)) {
                    return candidate
                }
            }
        }

        return texts.firstOrNull { text ->
            val candidate = text.trim()
            isHoldingNameCandidate(candidate) && extractTicker(candidate) != null
        }
            ?: texts.firstOrNull { text -> isHoldingNameCandidate(text.trim()) }
    }

    private fun isHoldingNameCandidate(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.contains("매도가능") || text.contains("매입단가") || text.contains("잔고")) return false
        if (text.contains("종목명") || text.contains("보유수량") || text.contains("매수금액") || text.contains("비중")) return false
        if (text.contains("두줄") || text.contains("KRX")) return false
        if (text.contains("0주 종목") || text.contains("표시")) return false
        if (text.contains("IRP") || text.contains("DC") || text.contains("개인형IRP")) return false
        if (text.contains("평가금액") || text.contains("평가손익") || text.contains("보유수량") || text.contains("매도가")) return false
        if (text.contains("수익률") || text.contains("초기화") || text.contains("잔고구분") || text.contains("투자한도")) return false
        if (text.contains("매수") || text.contains("매도") || text.contains("정정/취소") || text.contains("체결")) return false
        if (text.contains("현금최대가능") || text.contains("위험자산한도조회") || text.contains("메뉴") || text.contains("자산")) return false
        if (text.matches(Regex("[\\d,]+"))) return false
        return text.any { it.isLetter() }
    }

    private fun extractTicker(text: String?): String? {
        if (text.isNullOrBlank()) {
            return null
        }
        val match = Regex("(?<![A-Z0-9])([A-Z0-9]{6})(?![A-Z0-9])").find(text)
        return match?.groupValues?.get(1)
    }

    private fun extractTickerFromNodes(nodes: List<AccessibilityNodeInfo>): String? {
        for (node in nodes) {
            val text = nodeText(node)
            val ticker = extractTicker(text)
            if (ticker != null) {
                return ticker
            }
        }
        return null
    }

    private fun extractLabeledValue(
        nodes: List<AccessibilityNodeInfo>,
        label: String,
        valueRegex: Regex,
    ): String? {
        val texts = nodes.map { nodeText(it) }
        for (i in texts.indices) {
            val currentText = texts[i]
            if (!currentText.contains(label)) {
                continue
            }

            for (j in i + 1 until minOf(i + 5, texts.size)) {
                val candidate = texts[j]
                val match = valueRegex.find(candidate)
                if (match != null) {
                    return match.value
                }
            }

            val inlineMatch = valueRegex.find(currentText.substringAfter(label, ""))
            if (inlineMatch != null) {
                return inlineMatch.value
            }
        }
        return null
    }

    private fun extractListValue(nodes: List<AccessibilityNodeInfo>, header: String): String? {
        val texts = nodes.map { nodeText(it) }
        val headerIndex = texts.indexOfFirst { it.contains(header) }
        if (headerIndex < 0) {
            return null
        }

        for (index in headerIndex + 1 until texts.size) {
            val candidate = texts[index].trim()
            if (candidate.matches(Regex("[\\d,]+"))) {
                return candidate
            }
        }

        return null
    }

    private fun extractListSnapshot(root: AccessibilityNodeInfo): RetirementHoldingSnapshot {
        var currentRoot = root
        var mergedRow = GridRowSnapshot(null, null, null)

        repeat(3) { attempt ->
            val boxes = collectRetirementGridBoxes(currentRoot)
            val texts = boxes.map { it.text }
            if (isRetirementOrderLikeScreen(texts)) {
                Log.d(TAG, "extractListSnapshot attempt=$attempt skipped: order-like texts detected")
                return RetirementHoldingSnapshot("", "", null, null, null, null)
            }

            logGridBoxes("baseline-$attempt", boxes)
            mergedRow = mergeGridRows(mergedRow, resolveBestGridRow(boxes))
            if (mergedRow.itemName != null && mergedRow.sellableQuantity != null && mergedRow.averagePurchasePrice != null) {
                return RetirementHoldingSnapshot(
                    accountType = "",
                    accountLabel = "",
                    itemName = mergedRow.itemName,
                    ticker = null,
                    sellableQuantity = mergedRow.sellableQuantity,
                    averagePurchasePrice = mergedRow.averagePurchasePrice,
                )
            }

            if (!swipeRetirementGrid(currentRoot)) {
                return@repeat
            }
            sleep(1200)
            currentRoot = waitForMtsWindow(timeoutMs = 3000) ?: return@repeat
        }

        return RetirementHoldingSnapshot(
            accountType = "",
            accountLabel = "",
            itemName = mergedRow.itemName,
            ticker = null,
            sellableQuantity = mergedRow.sellableQuantity,
            averagePurchasePrice = mergedRow.averagePurchasePrice,
        )
    }

    private fun mergeGridRows(base: GridRowSnapshot, incoming: GridRowSnapshot): GridRowSnapshot {
        return GridRowSnapshot(
            itemName = base.itemName ?: incoming.itemName,
            sellableQuantity = base.sellableQuantity ?: incoming.sellableQuantity,
            averagePurchasePrice = base.averagePurchasePrice ?: incoming.averagePurchasePrice,
        )
    }

    private fun ensureTargetGridColumns(root: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var currentRoot = root
        repeat(3) { attempt ->
            val texts = collectRetirementGridBoxes(currentRoot).map { it.text }
            val hasSellable = texts.any { value -> value.contains("\uB9E4\uB3C4\uAC00\uB2A5") }
            val hasBuyPrice = texts.any { value -> value.contains("\uB9E4\uC785\uB2E8\uAC00") }
            Log.d(TAG, "ensureTargetGridColumns attempt=${attempt + 1} hasSellable=$hasSellable hasBuyPrice=$hasBuyPrice")
            if (hasSellable && hasBuyPrice) {
                return currentRoot
            }
            val moved = scrollRetirementGridNode(currentRoot) || swipeRetirementGrid(currentRoot)
            Log.d(TAG, "ensureTargetGridColumns swipeMoved=$moved")
            if (!moved) {
                return currentRoot
            }
            sleep(1200)
            currentRoot = waitForMtsWindow(timeoutMs = 3000) ?: return currentRoot
        }
        return currentRoot
    }

    private fun scrollRetirementGridNode(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val gridNode = nodes.filter { node ->
            node.isScrollable && node.isVisibleToUser
        }.filter { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.top > 900
        }.maxByOrNull { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.width() * bounds.height()
        } ?: return false

        return gridNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ||
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gridNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id)
            } else {
                false
            }
    }

    private fun collectVisibleTextBoxes(root: AccessibilityNodeInfo): List<VisibleTextBox> {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        return nodes.mapNotNull { node ->
            val text = nodeText(node).trim()
            if (text.isBlank()) {
                return@mapNotNull null
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0 || !node.isVisibleToUser) {
                null
            } else {
                VisibleTextBox(text, bounds)
            }
        }
    }

    private fun collectRetirementGridBoxes(root: AccessibilityNodeInfo): List<VisibleTextBox> {
        val boxes = collectVisibleTextBoxes(root)
        val headerBoxes = boxes.filter { box ->
            box.text.contains("종목명") || box.text.contains("매도가능") || box.text.contains("매입단가")
        }
        if (headerBoxes.isEmpty()) {
            return boxes
        }

        val headerBottom = headerBoxes.maxOf { it.bounds.bottom }
        val leftBoundary = (headerBoxes.minOf { it.bounds.left } - 40).coerceAtLeast(0)
        val rightBoundary = headerBoxes.maxOf { it.bounds.right } + 220

        return boxes.filter { box ->
            box.bounds.bottom >= headerBoxes.minOf { it.bounds.top } &&
                box.bounds.top > headerBottom - 40 &&
                box.bounds.left >= leftBoundary &&
                box.bounds.left <= rightBoundary
        }
    }

    private fun logGridBoxes(label: String, boxes: List<VisibleTextBox>) {
        val headers = listOf("종목명", "매도가능", "매입단가", "보유수량", "매수금액")
        val interesting = boxes.filter { box ->
            headers.any { box.text.contains(it) } ||
                box.text.contains("RISE") ||
                box.text.contains("KODEX") ||
                box.text.contains("엔비디아") ||
                box.text.trim().matches(Regex("[\\d,]+"))
        }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        Log.d(TAG, "[$label] grid boxes count=${interesting.size}")
        interesting.take(40).forEach { box ->
            Log.d(TAG, "[$label] ${box.text} @ ${box.bounds.toShortString()}")
        }
    }

    private fun resolveBestGridRow(boxes: List<VisibleTextBox>): GridRowSnapshot {
        val itemHeader = boxes.filter { it.text.contains("종목명") }
            .maxByOrNull { it.bounds.top * 10_000 + it.bounds.left }
        val sellableHeader = boxes.filter { it.text.contains("매도가능") }
            .maxByOrNull { it.bounds.top * 10_000 + it.bounds.left }
        val priceHeader = boxes.filter { it.text.contains("매입단가") }
            .maxByOrNull { it.bounds.top * 10_000 + it.bounds.left }
        if (sellableHeader == null && priceHeader == null) {
            return GridRowSnapshot(null, null, null)
        }
        if (itemHeader == null) {
            return GridRowSnapshot(
                itemName = null,
                sellableQuantity = sellableHeader?.let { findFirstGridColumnValue(boxes, it) },
                averagePurchasePrice = priceHeader?.let { findFirstGridColumnValue(boxes, it) },
            )
        }

        val candidateItems = boxes.filter { box ->
            box.bounds.top > itemHeader.bounds.bottom &&
                box.bounds.left <= itemHeader.bounds.left + 80 &&
                isHoldingNameCandidate(box.text)
        }
        if (candidateItems.isEmpty()) {
            return GridRowSnapshot(
                itemName = null,
                sellableQuantity = sellableHeader?.let { findFirstGridColumnValue(boxes, it) },
                averagePurchasePrice = priceHeader?.let { findFirstGridColumnValue(boxes, it) },
            )
        }

        val bestItem = candidateItems.maxByOrNull { item ->
            val y = item.bounds.centerY()
            var score = 0
            if (sellableHeader != null && findGridValueAt(boxes, sellableHeader.bounds.centerX(), y) != null) score += 1
            if (priceHeader != null && findGridValueAt(boxes, priceHeader.bounds.centerX(), y) != null) score += 1
            score * 10_000 - item.bounds.top
        } ?: return GridRowSnapshot(null, null, null)

        val itemName = candidateItems.filter {
            kotlin.math.abs(it.bounds.centerY() - bestItem.bounds.centerY()) < 90 &&
                kotlin.math.abs(it.bounds.left - bestItem.bounds.left) < 60
        }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .joinToString(" ") { it.text.trim() }
            .replace("  ", " ")
            .trim()

        return GridRowSnapshot(
            itemName = itemName.ifBlank { null },
            sellableQuantity = sellableHeader?.let { findGridValueAt(boxes, it.bounds.centerX(), bestItem.bounds.centerY()) },
            averagePurchasePrice = priceHeader?.let { findGridValueAt(boxes, it.bounds.centerX(), bestItem.bounds.centerY()) },
        )
    }

    private fun findGridValueAt(boxes: List<VisibleTextBox>, targetX: Int, targetY: Int): String? {
        return boxes.filter { box ->
            kotlin.math.abs(box.bounds.centerY() - targetY) < 90 &&
                kotlin.math.abs(box.bounds.centerX() - targetX) < 140 &&
                box.text.trim().matches(Regex("[\\d,]+"))
        }.minByOrNull { box ->
            kotlin.math.abs(box.bounds.centerY() - targetY) * 10_000 + kotlin.math.abs(box.bounds.centerX() - targetX)
        }?.text?.trim()
    }

    private fun findFirstGridColumnValue(boxes: List<VisibleTextBox>, headerBox: VisibleTextBox): String? {
        return boxes.filter { box ->
            box.bounds.top > headerBox.bounds.bottom &&
                kotlin.math.abs(box.bounds.centerX() - headerBox.bounds.centerX()) < 140 &&
                box.text.trim().matches(Regex("[\\d,]+"))
        }.minByOrNull { box ->
            box.bounds.top * 10_000 + kotlin.math.abs(box.bounds.centerX() - headerBox.bounds.centerX())
        }?.text?.trim()
    }

    private fun extractGridColumnFallback(root: AccessibilityNodeInfo, header: String): String? {
        val boxes = collectRetirementGridBoxes(root)
        val headerBox = boxes.filter { it.text.contains(header) }
            .maxByOrNull { it.bounds.top * 10_000 + it.bounds.left }
            ?: return null

        return boxes.filter { box ->
            box.bounds.top > headerBox.bounds.bottom &&
                kotlin.math.abs(box.bounds.centerX() - headerBox.bounds.centerX()) < 140 &&
                box.text.trim().matches(Regex("[\\d,]+"))
        }.minByOrNull { it.bounds.top }?.text?.trim()
    }

    private fun swipeRetirementGrid(root: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false
        }

        val startX = bounds.left + bounds.width() * 0.88f
        val endX = bounds.left + bounds.width() * 0.23f
        val y = bounds.top + bounds.height() * 0.48f
        var moved = false
        repeat(2) {
            moved = swipe(startX, y, endX, y) || moved
            sleep(700)
        }
        return moved
    }

    private fun discoverTickerForHolding(
        target: RetirementAccountTarget,
        root: AccessibilityNodeInfo,
        itemName: String?,
    ): String? {
        if (itemName.isNullOrBlank()) {
            return null
        }

        val boxes = collectVisibleTextBoxes(root)
        val itemBox = boxes.filter { box ->
            box.bounds.left <= 120 && isHoldingNameCandidate(box.text)
        }.maxByOrNull { box ->
            val normalizedTarget = normalizeHoldingKey(itemName)
            val normalizedText = normalizeHoldingKey(box.text)
            val score = if (normalizedTarget.contains(normalizedText) || normalizedText.contains(normalizedTarget)) 1 else 0
            score * 10_000 - box.bounds.top
        } ?: return null
        if (!tapCenter(itemBox.bounds)) {
            return null
        }

        sleep(1500)
        if (waitForRoot(timeoutMs = 2000) { isAccountPasswordPopupRoot(it) } != null && !enterAccountPassword(target)) {
            return null
        }

        sleep(1500)
        val currentPriceRoot = waitForMtsWindow(timeoutMs = 5000) ?: return null
        val ticker = extractTickerFromTopArea(currentPriceRoot)
        performGlobalAction(GLOBAL_ACTION_BACK)
        sleep(1200)

        if (!ticker.isNullOrBlank()) {
            configManager.saveHoldingTicker(normalizeHoldingKey(itemName), ticker)
        }
        return ticker
    }

    private fun extractTickerFromTopArea(root: AccessibilityNodeInfo): String? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        return nodes
            .mapNotNull { node ->
                val text = nodeText(node).trim()
                if (text.isBlank()) return@mapNotNull null
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.bottom > 500) return@mapNotNull null
                extractTicker(text)
            }
            .firstOrNull()
    }

    private fun normalizeHoldingKey(value: String): String {
        return value.replace("\u00A0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun openHoldingDetailAndResolvePassword(
        target: RetirementAccountTarget,
        root: AccessibilityNodeInfo,
    ): AccessibilityNodeInfo? {
        if (hasRetirementBalanceMarkers(root)) {
            return root
        }

        val holdingNode = findPrimaryHoldingNode(root) ?: return null
        if (!clickNode(holdingNode)) {
            val bounds = Rect()
            holdingNode.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0 || !tapCenter(bounds)) {
                return null
            }
        }

        sleep(1500)
        if (waitForRoot(timeoutMs = 2000) { isAccountPasswordPopupRoot(it) } != null) {
            if (!enterAccountPassword(target)) {
                return null
            }
        }

        sleep(1500)
        val detailRoot = waitForMtsWindow(timeoutMs = 5000) ?: return null
        if (openRetirementDetailTab()) {
            return waitForMtsWindow(timeoutMs = 5000) ?: detailRoot
        }
        return detailRoot
    }

    private fun findPrimaryHoldingNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        return nodes
            .filter { node ->
                val text = nodeText(node).trim()
                isHoldingNameCandidate(text)
            }
            .sortedBy { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                bounds.top * 10_000 + bounds.left
            }
            .firstOrNull()
    }

    private fun isAccountPasswordPopupRoot(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val texts = nodes.map { nodeText(it) }.filter { it.isNotBlank() }
        return texts.any { it.contains("계좌 비밀번호 입력") } ||
            (texts.any { it.contains("비밀번호") } && texts.any { it.contains("자동저장") })
    }

    private fun isVirtualKeyboardAvailableRoot(root: AccessibilityNodeInfo): Boolean {
        return isTransKeyRoot(root) || (isAccountPasswordPopupRoot(root) && hasVisibleAccountPasswordKeypad(root))
    }

    private fun hasVisibleAccountPasswordKeypad(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val digitLabels = nodes.map { nodeText(it) }
            .filter { it.matches(Regex("\\d")) }
            .distinct()
        val hasComplete = nodes.any { nodeText(it).contains("입력완료") }
        return hasComplete || digitLabels.size >= 8
    }

    private fun openAccountPasswordKeyboard(): Boolean {
        val transKeyRoot = waitForRoot(timeoutMs = 1000) { isVirtualKeyboardAvailableRoot(it) }
        if (transKeyRoot != null) {
            Log.d(TAG, "계좌 비밀번호 키패드가 이미 열려 있음")
            return true
        }

        val popupRoot = waitForRoot(timeoutMs = 8000) { isAccountPasswordPopupRoot(it) } ?: return false
        Log.d(TAG, "계좌 비밀번호 팝업 감지")
        val passwordField = firstVisibleNodeByViewId(popupRoot, "et_password")
            ?: firstVisibleNodeByViewId(popupRoot, "tf_password")
            ?: findFirstActionableNodeByTextOrDescription(popupRoot, "비밀번호")
            ?: findNodeByText(popupRoot, "비밀번호")
            ?: return false

        val opened = focusAndTapPasswordField(passwordField)
        Log.d(TAG, "계좌 비밀번호 키보드 열기 결과=$opened")
        return opened
    }

    private fun accountPasswordFor(target: RetirementAccountTarget): String {
        Log.w(TAG, "Account password input is owned by the pension subsystem, not the login helper.")
        return ""
    }

    private fun resolveRetirementAccountTargetFromRoot(root: AccessibilityNodeInfo): RetirementAccountTarget? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val texts = nodes.map { nodeText(it).trim() }
            .filter { it.isNotBlank() }

        return RETIREMENT_ACCOUNT_TARGETS.firstOrNull { target ->
            texts.any { text ->
                text.contains(target.accountPrefix) ||
                    text.contains(target.accountName) ||
                    text.contains(target.type)
            }
        }
    }

    private fun enterAccountPassword(target: RetirementAccountTarget): Boolean {
        val password = accountPasswordFor(target)
        if (password.isBlank()) {
            Log.w(TAG, "${target.type} 계좌 비밀번호가 저장되어 있지 않습니다")
            return false
        }

        Log.d(TAG, "${target.type} 계좌 비밀번호 입력 시작")

        if (!openAccountPasswordKeyboard()) {
            Log.w(TAG, "${target.type} 계좌 비밀번호 키보드를 열지 못했습니다")
            return false
        }

        val success = enterAccountPasswordByAccessibleDigits(password)
        Log.d(TAG, "${target.type} 계좌 비밀번호 입력 결과=$success")
        return success
    }

    private fun closeHoldingDetailIfNeeded() {
        val root = waitForMtsWindow(timeoutMs = 1000) ?: return
        if (hasRetirementBalanceMarkers(root) || isAccountPasswordPopupRoot(root)) {
            return
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        sleep(1200)
    }

    private fun formatRetirementHoldings(snapshots: List<RetirementHoldingSnapshot>): String {
        val builder = StringBuilder()
        builder.append("=== 퇴직연금 ETF/리츠 잔고 ===\n")

        for (snapshot in snapshots) {
            builder.append("[${snapshot.accountType}] ${snapshot.accountLabel}\n")
            builder.append("보유종목: ${snapshot.itemName ?: "미확인"}\n")
            builder.append("ticker: ${snapshot.ticker ?: "미확인"}\n")
            builder.append("매도가능: ${snapshot.sellableQuantity ?: "미확인"}\n")
            builder.append("매입단가: ${snapshot.averagePurchasePrice?.let { "${it}원" } ?: "미확인"}\n\n")
        }

        return builder.toString().trimEnd()
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, text)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun extractBalanceData(rootNode: AccessibilityNodeInfo): String {
        val balanceInfo = StringBuilder()
        balanceInfo.append("=== 잔고 정보 ===\n")

        val allNodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(rootNode, allNodes)

        Log.d(TAG, "Extracting data using ${extractionRules.size} rules from ${allNodes.size} nodes")

        for (rule in extractionRules) {
            val value = when (rule.extraction_method) {
                "nearby_text" -> extractByNearbyText(allNodes, rule)
                "resource_id" -> extractByResourceId(allNodes, rule)
                "text" -> extractByText(allNodes, rule)
                else -> {
                    Log.w(TAG, "Unknown extraction method: ${rule.extraction_method}")
                    null
                }
            }

            if (value != null) {
                balanceInfo.append("${rule.label}: $value\n")
                Log.d(TAG, "Extracted ${rule.label}: $value")
            } else {
                Log.w(TAG, "Failed to extract ${rule.label}")
            }
        }

        if (balanceInfo.length == 14) {
            balanceInfo.append("데이터를 추출할 수 없습니다.\n")
            balanceInfo.append("화면 구조: ${allNodes.size}개 노드 발견\n")
            balanceInfo.append("사용된 규칙 수: ${extractionRules.size}\n")
        }

        return balanceInfo.toString()
    }

    private fun extractByNearbyText(nodes: List<AccessibilityNodeInfo>, rule: ExtractionRule): String? {
        for (i in 0 until nodes.size - 1) {
            val currentNode = nodes[i]
            if (currentNode.text?.contains(rule.label) == true) {
                val startIdx = i + 1
                val endIdx = minOf(startIdx + rule.position_offset, nodes.size)

                for (j in startIdx until endIdx) {
                    val nextNode = nodes[j]
                    val text = nextNode.text?.toString()
                    if (!text.isNullOrBlank()) {
                        if (rule.regex_pattern != null) {
                            val regex = Regex(rule.regex_pattern)
                            val match = regex.find(text)
                            if (match != null) {
                                return match.value.trim()
                            }
                        } else {
                            return text.trim()
                        }
                    }
                }
            }
        }

        for (node in nodes) {
            val text = node.text
            if (text != null && text.contains(rule.label) && text.contains(":")) {
                val parts = text.split(":")
                if (parts.size >= 2) {
                    val value = parts[1].trim()
                    if (rule.regex_pattern != null) {
                        val regex = Regex(rule.regex_pattern)
                        val match = regex.find(value)
                        if (match != null) {
                            return match.value.trim()
                        }
                    }
                    return value
                }
            }
        }

        return null
    }

    private fun extractByResourceId(nodes: List<AccessibilityNodeInfo>, rule: ExtractionRule): String? {
        if (rule.resource_id.isEmpty()) {
            return null
        }

        for (node in nodes) {
            val nodeId = node.viewIdResourceName ?: continue
            if (nodeId.contains(rule.resource_id)) {
                val text = node.text?.toString()
                if (!text.isNullOrBlank()) {
                    if (rule.regex_pattern != null) {
                        val regex = Regex(rule.regex_pattern)
                        val match = regex.find(text)
                        if (match != null) {
                            return match.value.trim()
                        }
                    }
                    return text.trim()
                }
            }
        }

        return null
    }

    private fun extractByText(nodes: List<AccessibilityNodeInfo>, rule: ExtractionRule): String? {
        for (node in nodes) {
            val text = node.text?.toString() ?: continue
            if (text.contains(rule.target_text)) {
                return text.trim()
            }
        }
        return null
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo, nodes: ArrayList<AccessibilityNodeInfo>) {
        nodes.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodes(child, nodes)
        }
    }

    private fun sendBalanceData(data: String) {
        val intent = Intent(ACTION_BALANCE_DATA)
        intent.putExtra("balance_data", data)
        sendBroadcast(intent)
        Log.d(TAG, "잔고 데이터 전송: $data")
    }

    private fun sendLoginStatus(status: String, success: Boolean = false, finished: Boolean = false) {
        val intent = Intent(ACTION_LOGIN_STATUS)
        intent.putExtra("status", status)
        intent.putExtra("success", success)
        intent.putExtra("finished", finished)
        sendBroadcast(intent)
        maybeLogCommandResult(status, success, finished)
        Log.d(TAG, "로그인 상태 전송: $status / success=$success / finished=$finished")
    }

    private fun maybeLogCommandResult(status: String, success: Boolean, finished: Boolean) {
        val requestId = currentRequestId.get()?.takeIf { it.isNotBlank() } ?: return
        val command = currentCommand.get().orEmpty()
        val payload = JSONObject()
            .put("request_id", requestId)
            .put("command", command)
            .put("finished", finished)
            .put("success", success)
            .put("state", inferCommandResultState(command, status, success, finished))
            .put("message", status)
        Log.i(TAG_COMMAND_RESULT, payload.toString())
    }

    private fun inferCommandResultState(
        command: String,
        status: String,
        success: Boolean,
        finished: Boolean,
    ): String {
        if (!finished) {
            return "UNKNOWN"
        }
        return when {
            status.contains("로그인된 상태") -> "LOGGED_IN"
            status.contains("로그인되지 않은 상태") -> "LOGGED_OUT"
            command == CMD_LOGIN && success -> "LOGGED_IN"
            success -> "UNKNOWN"
            else -> "ERROR"
        }
    }

    private fun switchAccount() {
        Thread {
            try {
                Log.d(TAG, "계좌 변경 시작")

                val rootNode = rootInActiveWindow
                if (rootNode == null) {
                    Log.e(TAG, "rootInActiveWindow가 null입니다")
                    sendBalanceData("오류: 화면 정보를 가져올 수 없습니다")
                    return@Thread
                }

                val accountDropdown = findNodeByText(rootNode, "64923286")
                if (accountDropdown != null) {
                    Log.d(TAG, "계좌 드롭다운 찾음: ${accountDropdown.text}")
                    clickNode(accountDropdown)
                    sleep(2000)

                    val updatedRoot = rootInActiveWindow
                    if (updatedRoot == null) {
                        sendBalanceData("오류: 계좌 목록을 불러오지 못했습니다")
                        return@Thread
                    }

                    val accounts = mutableListOf<String>()
                    val allNodes = ArrayList<AccessibilityNodeInfo>()
                    collectAllNodes(updatedRoot, allNodes)

                    for (node in allNodes) {
                        val text = node.text?.toString() ?: ""
                        if (text.contains("DC") || text.contains("IRP") || text.contains("64664736") || text.contains("64923286")) {
                            if (!accounts.contains(text)) {
                                accounts.add(text)
                            }
                        }
                    }

                    Log.d(TAG, "발견된 계좌 목록: $accounts")

                    if (accounts.size > 1) {
                        val targetAccount = accounts[1]
                        val searchText = if (targetAccount.length > 10) targetAccount.substring(0, 10) else targetAccount
                        val targetNode = findNodeByText(updatedRoot, searchText)

                        if (targetNode != null) {
                            Log.d(TAG, "다른 계좌 클릭: $targetAccount")
                            clickNode(targetNode)
                            sleep(2000)

                            val refreshedRoot = rootInActiveWindow
                            if (refreshedRoot == null) {
                                sendBalanceData("오류: 계좌 변경 후 화면을 읽지 못했습니다")
                                return@Thread
                            }

                            val balanceData = extractBalanceData(refreshedRoot)
                            sendBalanceData(balanceData)
                        } else {
                            Log.w(TAG, "다른 계좌를 찾을 수 없습니다")
                            sendBalanceData("오류: 다른 계좌를 찾을 수 없습니다")
                        }
                    } else {
                        Log.w(TAG, "다른 계좌가 없습니다")
                        sendBalanceData("오류: 다른 계좌가 없습니다")
                    }
                } else {
                    Log.w(TAG, "계좌 드롭다운을 찾을 수 없습니다")
                    sendBalanceData("오류: 계좌 드롭다운을 찾을 수 없습니다")
                }
            } catch (e: Exception) {
                Log.e(TAG, "계좌 변경 중 오류 발생", e)
                sendBalanceData("오류: ${e.message}")
            }
        }.start()
    }
}
