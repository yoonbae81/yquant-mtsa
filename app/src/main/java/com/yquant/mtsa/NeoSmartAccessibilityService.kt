package com.yquant.mtsa

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayList

class NeoSmartAccessibilityService : AccessibilityService() {

    private var commandReceiver: BroadcastReceiver? = null
    private lateinit var configManager: ConfigManager
    private var extractionRules: List<ExtractionRule> = emptyList()

    companion object {
        private const val TAG = "MtsaAccessibility"
        private const val MTS_PACKAGE = "com.truefriend.neosmartarenewal"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AccessibilityService created")

        configManager = ConfigManager(this)
        extractionRules = configManager.loadRules()
        Log.d(TAG, "Loaded ${extractionRules.size} extraction rules")

        // 브로드캐스트 리시버 등록
        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val command = intent?.getStringExtra("command")
                Log.d(TAG, "Received command: $command")
                handleCommand(command)
            }
        }

        val filter = IntentFilter("com.yquant.mtsa.ACTION_NAVIGATE")
        registerReceiver(commandReceiver, filter)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            when (it.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    Log.d(TAG, "Window changed: ${it.packageName} / ${it.className}")
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // 화면 내용 변경 감지
                }
                else -> {
                    // 다른 이벤트 무시
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        commandReceiver?.let { unregisterReceiver(it) }
        Log.d(TAG, "AccessibilityService destroyed")
    }

    private fun handleCommand(command: String?) {
        when (command) {
            "NAVIGATE_TO_7201" -> {
                navigateToScreen7201()
            }
            "GET_BALANCE" -> {
                getBalanceFrom7201()
            }
            "SWITCH_ACCOUNT" -> {
                switchAccount()
            }
            else -> {
                Log.w(TAG, "Unknown command: $command")
            }
        }
    }

    private fun navigateToScreen7201() {
        try {
            // 딥링크를 사용하여 7201 화면으로 이동
            val intent = Intent().apply {
                component = ComponentName(MTS_PACKAGE, "$MTS_PACKAGE.ui.main.MTSMainActivity")
                action = "ActionDeepLink"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("KeyOpenScreenNo", "7201")
                putExtra("KeyOpenScreenData", "")
            }

            startActivity(intent)
            Log.d(TAG, "7201 화면으로 이동 명령 전송 완료")

        } catch (e: Exception) {
            Log.e(TAG, "7201 화면 이동 실패", e)
        }
    }

    private fun isMtsAppRunning(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val packageName = rootNode.packageName?.toString()
        return packageName == MTS_PACKAGE
    }

    private fun launchMtsApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(MTS_PACKAGE)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.d(TAG, "MTS 앱 시작 완료")
        } catch (e: Exception) {
            Log.e(TAG, "MTS 앱 시작 실패", e)
        }
    }

    private fun getBalanceFrom7201() {
        Thread {
            try {
                Log.d(TAG, "잔고 조회 시작")

                navigateToScreen7201()
                Thread.sleep(3000)

                val rootNode = rootInActiveWindow
                if (rootNode == null) {
                    Log.e(TAG, "rootInActiveWindow가 null입니다")
                    sendBalanceData("오류: 화면 정보를 가져올 수 없습니다")
                    return@Thread
                }

                val balanceNode = findNodeByText(rootNode, "잔고")
                if (balanceNode != null) {
                    Log.d(TAG, "잔고 노드 찾음: ${balanceNode.text}")
                    performClick(balanceNode)
                    Thread.sleep(2000)
                } else {
                    Log.w(TAG, "잔고 텍스트를 찾을 수 없습니다")
                }

                Thread.sleep(1000)
                val balanceData = extractBalanceData(rootInActiveWindow)
                sendBalanceData(balanceData)

                Log.d(TAG, "잔고 조회 완료")
            } catch (e: Exception) {
                Log.e(TAG, "잔고 조회 중 오류 발생", e)
                sendBalanceData("오류: ${e.message}")
            }
        }.start()
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.contains(text) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findNodeByText(child, text)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo) {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            val parent = node.parent
            parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
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
            collectAllNodes(node.getChild(i), nodes)
        }
    }

    private fun findNearbyLabel(nodes: List<AccessibilityNodeInfo>, label: String): String? {
        for (i in 0 until nodes.size - 1) {
            val currentNode = nodes[i]
            if (currentNode.text?.contains(label) == true) {
                for (j in i + 1 until minOf(i + 5, nodes.size)) {
                    val nextNode = nodes[j]
                    val text = nextNode.text
                    if (!text.isNullOrBlank() && text.matches(Regex(".*\\d+.*"))) {
                        return text.toString().trim()
                    }
                }
            }
        }

        for (node in nodes) {
            val text = node.text
            if (text != null && text.contains(label) && text.contains(":")) {
                val parts = text.split(":")
                if (parts.size >= 2) {
                    return parts[1].trim()
                }
            }
        }

        return null
    }

    private fun sendBalanceData(data: String) {
        val intent = Intent("com.yquant.mtsa.ACTION_BALANCE_DATA")
        intent.putExtra("balance_data", data)
        sendBroadcast(intent)
        Log.d(TAG, "잔고 데이터 전송: $data")
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

                // 계좌 드롭다운 찾기 (64923286 포함)
                val accountDropdown = findNodeByText(rootNode, "64923286")
                if (accountDropdown != null) {
                    Log.d(TAG, "계좌 드롭다운 찾음: ${accountDropdown.text}")
                    performClick(accountDropdown)
                    Thread.sleep(2000)

                    val updatedRoot = rootInActiveWindow
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

                    // 첫 번째 계좌가 아니라면 두 번째 계좌 클릭
                    if (accounts.size > 1) {
                        val targetAccount = accounts[1]
                        val targetNode = findNodeByText(updatedRoot, if (targetAccount.length > 10) targetAccount.substring(0, 10) else targetAccount)
                        
                        if (targetNode != null) {
                            Log.d(TAG, "다른 계좌 클릭: $targetAccount")
                            performClick(targetNode)
                            Thread.sleep(2000)

                            val balanceData = extractBalanceData(rootInActiveWindow)
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

                Log.d(TAG, "계좌 변경 완료")
            } catch (e: Exception) {
                Log.e(TAG, "계좌 변경 중 오류 발생", e)
                sendBalanceData("오류: ${e.message}")
            }
        }.start()
    }
}
