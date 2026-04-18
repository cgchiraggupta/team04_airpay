package com.airpay.upi.ussd

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class USSDService : AccessibilityService() {

    companion object {
        private const val TAG = "USSDService"

        // All known USSD dialog package names across Android OEMs and versions
        private val USSD_PACKAGES = listOf(
            "com.android.phone",            // Stock Android
            "com.nothing.phone",            // Nothing Phone 1, 2, 3
            "com.samsung.android.phone",    // Samsung
            "com.android.dialer",           // AOSP dialer
            "com.qualcomm.qti.phone",       // Qualcomm reference devices
            "com.mediatek.phone",           // MediaTek devices
            "com.oneplus.phone",            // OnePlus
            "com.oppo.phone",               // OPPO
            "com.vivo.phone",               // Vivo
            "com.realme.phone",             // Realme
            "com.zte.mifavor.telecom",      // ZTE
            "com.lge.phone",                // LG
            "com.motorola.phone",           // Motorola
            "com.htc.phone",                // HTC
            "com.airtel.ussd",             // Airtel USSD (if exists)
            "com.airtel.phone",            // Airtel Phone
            // NOTE: com.google.android.dialer is intentionally EXCLUDED
            // On Nothing Phone 3, the dialer package fires events for the
            // in-call screen (showing call timer "00:13", "00:14" etc).
            // The actual USSD popup comes from com.android.phone, not the dialer.
            // Including the dialer caused the service to read the call timer
            // as USSD text and jump straight to CONFIRM state.
        )

        // Class name fragments that indicate a USSD dialog
        private val USSD_CLASS_FRAGMENTS = listOf(
            "UssdAlertActivity",
            "UssdActivity",
            "AlertDialog",
            "MMIActivity",
            "UssdResponseActivity",
            "UssdRunningActivity"
        )

        // Text fragments that confirm this is a USSD response screen
        // IMPORTANT: these must be specific enough to not match dialer UI text
        private val USSD_TEXT_HINTS = listOf(
            "*99#",
            "bhim",
            "send money",
            "check balance",
            "enter upi",
            "enter amount",
            "mpin",
            "enter pin",
            "welcome to *99",
            "bank of",
            "account no",
            "upi id",
            "enter vpa",
            "mobile no",
            "mobile number",
            "enter mobile",
            "phone number",
            "00.bac",
            "00.back",
            "or 00",
            "no.",
            "airtel message",  // Airtel specific
            "airtel",         // Airtel specific
            "ussd",           // Generic USSD
            "dial",           // USSD dialing
            "response"        // USSD response
        )

        // Regex to detect call timer format "MM:SS" or "HH:MM:SS"
        // If extracted text matches only this pattern it is the dialer
        // in-call screen timer, not a USSD dialog — must be ignored
        private val CALL_TIMER_REGEX = Regex("^\\s*\\d{1,2}:\\d{2}(:\\d{2})?\\s*$")
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSendClick: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Only care about window state changes and content changes
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val source = event.source ?: return
        val packageName = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()

        Log.d(TAG, "Raw event pkg=$packageName class=$className type=${event.eventType}")

        if (!USSDController.hasActiveSession()) {
            cancelPendingSendClick()
            Log.d(TAG, "Ignoring phone event because no active USSD session exists")
            return
        }

        // Check if this is a USSD dialog — log for debugging
        val isUssd = isUSSDDialog(source, event)
        Log.d(TAG, "Event pkg=${event.packageName} class=${event.className} isUssd=$isUssd")

        if (!isUssd) {
            return
        }

        // Extract all visible text from the dialog
        val ussdText = extractUSSDText(source)
        Log.d(TAG, "USSD text extracted; length=${ussdText.length}")
        
        // Log current flow and state for debugging
        Log.d(TAG, "Current flow: ${USSDController.currentFlow}")
        Log.d(TAG, "Current state: ${USSDController.currentState}")

        if (ussdText.isEmpty()) {
            Log.d(TAG, "Empty USSD text, skipping")
            return
        }

        if (isTerminalState()) {
            cancelPendingSendClick()
            dismissTerminalDialog(source)
            Log.d(TAG, "Ignoring terminal USSD update for state=${USSDController.currentState}")
            return
        }

        // Guard: if the entire extracted text is just a call timer (e.g. "00:14")
        // this is the dialer in-call screen, NOT a USSD dialog — skip it entirely
        if (CALL_TIMER_REGEX.matches(ussdText)) {
            Log.d(TAG, "Skipping event because text looks like a call timer")
            return
        }

        if (maybeAutoAdvanceInitialDialog(source, ussdText)) {
            Log.d(TAG, "Auto-advanced initial USSD confirmation dialog")
            return
        }

        // Ask the state machine what to type next
        val nextInput = USSDController.getNextInput(ussdText)
        Log.d(TAG, "State=${USSDController.currentState} inputPresent=${nextInput != null}")
        
        // Extra logging for debugging payment issues
        if (USSDController.currentFlow == USSDController.Flow.PAYMENT) {
            Log.d(TAG, "PAYMENT FLOW: state=${USSDController.currentState} inputPresent=${nextInput != null}")
        }

        if (nextInput != null) {
            val inputFilled = fillUSSDInput(source, nextInput)
            if (inputFilled) {
                cancelPendingSendClick()
                val clickRunnable = Runnable {
                    if (isTerminalState()) {
                        Log.d(TAG, "Skipping delayed send click because state is terminal")
                        return@Runnable
                    }
                    val root = rootInActiveWindow
                    if (root != null) {
                        clickSendButton(root)
                    }
                }
                pendingSendClick = clickRunnable
                // Use Handler instead of Thread.sleep to avoid blocking accessibility thread
                handler.postDelayed(clickRunnable, 300)
            } else {
                // No text field — try clicking a matching menu option directly
                clickMatchingOption(source, nextInput)
            }
        } else if (USSDController.currentState == USSDController.State.BALANCE_RESULT ||
                   USSDController.currentState == USSDController.State.SUCCESS) {
            // Auto-dismiss any lingering USSD dialog in a terminal state.
            // For SUCCESS the network may show a final "beneficiary added" or confirmation
            // dialog with a Send button that must be tapped to close the USSD session.
            dismissTerminalDialog(source)
        }

    }

    /**
     * Determines if the given accessibility node is a USSD dialog.
     * Uses multiple strategies layered together so it works across all OEMs.
     *
     * Strategy priority:
     * 1. Package name match — most reliable, fast
     * 2. Class name match — catches dialog variants
     * 3. Text keyword match — fallback for unknown OEMs, but only if text
     *    is NOT a call timer (which the dialer also fires events for)
     */
    private fun isUSSDDialog(node: AccessibilityNodeInfo, event: AccessibilityEvent): Boolean {
        val pkg = event.packageName?.toString()?.lowercase() ?: ""
        val cls = event.className?.toString() ?: ""

        // Never treat our own activities as USSD dialogs.
        if (pkg == packageName.lowercase()) {
            return false
        }

        // Ignore system UI surfaces that may mention carrier/network text.
        if (pkg == "com.android.systemui") {
            return false
        }

        val extractedText = extractUSSDText(node).lowercase().trim()

        // Strategy 1: Known USSD package name — fastest check
        // Nothing's regular dialer/contact surfaces also use com.nothing.phone,
        // so require stronger evidence there before treating the event as USSD.
        val rawPackageMatch = USSD_PACKAGES.any { pkg.contains(it) }
        val packageMatch = if (pkg.contains("com.nothing.phone")) {
            rawPackageMatch && (
                cls.contains("ussd", ignoreCase = true) ||
                    cls.contains("mmi", ignoreCase = true) ||
                    extractedText.contains("*99#") ||
                    extractedText.contains("ussd") ||
                    extractedText.contains("send money") ||
                    extractedText.contains("check balance") ||
                    extractedText.contains("enter upi") ||
                    extractedText.contains("enter amount") ||
                    extractedText.contains("upi pin")
                )
        } else {
            rawPackageMatch
        }

        // Strategy 2: Known USSD class name fragment
        val classMatch = USSD_CLASS_FRAGMENTS.any { cls.contains(it, ignoreCase = true) }

        // Strategy 3: Text keyword fallback — only used if package/class didn't match
        // Extract text first, then reject if it looks like a call timer
        val textMatch = if (!packageMatch && !classMatch) {
            val textForHint = extractedText
            // Don't treat call timer text as USSD
            if (CALL_TIMER_REGEX.matches(textForHint.trim())) {
                false
            } else {
                USSD_TEXT_HINTS.any { textForHint.contains(it) }
            }
        } else {
            false
        }

        Log.d(TAG, "isUSSDDialog → pkg=$pkg packageMatch=$packageMatch classMatch=$classMatch textMatch=$textMatch")

        return packageMatch || classMatch || textMatch
    }

    private fun extractUSSDText(node: AccessibilityNodeInfo): String {
        val builder = StringBuilder()
        extractTextRecursive(node, builder)
        return builder.toString().trim()
    }

    private fun extractTextRecursive(node: AccessibilityNodeInfo, builder: StringBuilder) {
        node.text?.let {
            builder.append(it).append(" ")
        }
        node.contentDescription?.let {
            builder.append(it).append(" ")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractTextRecursive(child, builder)
        }
    }

    private fun fillUSSDInput(root: AccessibilityNodeInfo, text: String): Boolean {
        if (!USSDController.hasActiveSession()) {
            Log.d(TAG, "Skipping text injection because session is no longer active")
            return false
        }

        val editTexts = ArrayList<AccessibilityNodeInfo>()
        findNodesByClassName(root, "android.widget.EditText", editTexts)

        var filled = false
        for (editText in editTexts) {
            if (editText.isEditable) {
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
                editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                filled = true
                val redacted = if (text.length <= 2) {
                    "**"
                } else {
                    "${text.take(1)}***"
                }
                Log.d(TAG, "Filled input with redacted value: $redacted")
            }
        }
        return filled
    }

    private fun maybeAutoAdvanceInitialDialog(root: AccessibilityNodeInfo, ussdText: String): Boolean {
        if (USSDController.currentState != USSDController.State.IDLE) return false
        if (hasEditableInput(root)) return false

        val text = ussdText.lowercase()
        val isBalanceFlow = USSDController.currentFlow == USSDController.Flow.BALANCE
        val looksLikeRealPrompt =
            text.contains("send money") ||
                (!isBalanceFlow && text.contains("check balance")) ||
                text.contains("enter upi") ||
                text.contains("enter amount") ||
                text.contains("upi id") ||
                text.contains("mobile number") ||
                text.contains("enter pin") ||
                text.contains("upi pin") ||
                text.contains("mpin") ||
                text.contains("1.") ||
                text.contains("2.") ||
                text.contains("3.")

        if (looksLikeRealPrompt) return false

        return clickPositiveDialogButton(root)
    }

    private fun hasEditableInput(root: AccessibilityNodeInfo): Boolean {
        val editTexts = ArrayList<AccessibilityNodeInfo>()
        findNodesByClassName(root, "android.widget.EditText", editTexts)
        return editTexts.any { it.isEditable }
    }

    private fun clickPositiveDialogButton(root: AccessibilityNodeInfo): Boolean {
        val buttons = ArrayList<AccessibilityNodeInfo>()
        findNodesByClassName(root, "android.widget.Button", buttons)

        for (button in buttons) {
            val btnText = button.text?.toString()?.lowercase()?.trim().orEmpty()
            if (btnText == "ok" ||
                btnText.contains("send") ||
                btnText.contains("reply") ||
                btnText.contains("yes") ||
                btnText.contains("continue")
            ) {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked initial confirmation button: $btnText")
                return true
            }
        }

        return false
    }

    private fun clickMatchingOption(root: AccessibilityNodeInfo, choice: String) {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        findClickableNodes(root, nodes)

        for (node in nodes) {
            val nodeText = node.text?.toString()?.lowercase() ?: ""
            if (nodeText == choice.lowercase() ||
                nodeText.contains("sim ${choice.lowercase()}") ||
                nodeText.startsWith("${choice.lowercase()}.")
            ) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked option: $nodeText")
                return
            }
        }
    }

    private fun findClickableNodes(
        root: AccessibilityNodeInfo,
        outList: MutableList<AccessibilityNodeInfo>
    ) {
        if (root.isClickable) outList.add(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findClickableNodes(child, outList)
        }
    }

    private fun clickSendButton(root: AccessibilityNodeInfo) {
        val buttons = ArrayList<AccessibilityNodeInfo>()
        findNodesByClassName(root, "android.widget.Button", buttons)

        for (button in buttons) {
            val btnText = button.text?.toString()?.lowercase() ?: ""
            if (btnText.contains("send") ||
                btnText.contains("ok") ||
                btnText.contains("reply") ||
                btnText.contains("yes") ||
                btnText.contains("submit")
            ) {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked send button: $btnText")
                return
            }
        }

        // Fallback: if no button found by text, try the first clickable button
        if (buttons.isNotEmpty()) {
            buttons[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Clicked fallback first button")
        }
    }

    private fun dismissTerminalDialog(root: AccessibilityNodeInfo) {
        val state = USSDController.currentState
        if (state != USSDController.State.BALANCE_RESULT &&
            state != USSDController.State.SUCCESS) {
            return
        }

        val buttons = ArrayList<AccessibilityNodeInfo>()
        findNodesByClassName(root, "android.widget.Button", buttons)

        if (state == USSDController.State.SUCCESS) {
            // For a post-payment dialog (e.g. "beneficiary added" or carrier confirmation)
            // click Send first so the USSD session is closed cleanly by the network.
            // Fall through to Cancel/OK/back only if Send is not found.
            for (button in buttons) {
                val btnText = button.text?.toString()?.lowercase() ?: ""
                if (btnText.contains("send") ||
                    btnText.contains("ok") ||
                    btnText.contains("done") ||
                    btnText.contains("submit")
                ) {
                    button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Dismissed SUCCESS terminal dialog via button: $btnText")
                    return
                }
            }
            // Fallback — close with Cancel/back
            for (button in buttons) {
                val btnText = button.text?.toString()?.lowercase() ?: ""
                if (btnText.contains("cancel") ||
                    btnText.contains("close") ||
                    btnText.contains("dismiss") ||
                    btnText.contains("back")
                ) {
                    button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Dismissed SUCCESS terminal dialog via cancel button: $btnText")
                    return
                }
            }
        } else {
            // BALANCE_RESULT — prefer Cancel/Close to simply dismiss without sending anything
            for (button in buttons) {
                val btnText = button.text?.toString()?.lowercase() ?: ""
                if (btnText.contains("cancel") ||
                    btnText.contains("close") ||
                    btnText.contains("dismiss") ||
                    btnText.contains("back") ||
                    btnText.contains("ok")
                ) {
                    button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Dismissed terminal balance dialog via button: $btnText")
                    return
                }
            }
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        Log.d(TAG, "Dismissed terminal dialog via global back (state=$state)")
    }

    private fun isTerminalState(): Boolean {
        return USSDController.currentState == USSDController.State.SUCCESS ||
            USSDController.currentState == USSDController.State.FAILED ||
            USSDController.currentState == USSDController.State.BALANCE_RESULT
    }

    private fun cancelPendingSendClick() {
        pendingSendClick?.let(handler::removeCallbacks)
        pendingSendClick = null
    }

    private fun findNodesByClassName(
        root: AccessibilityNodeInfo,
        className: String,
        outList: MutableList<AccessibilityNodeInfo>
    ) {
        if (root.className?.toString() == className) outList.add(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findNodesByClassName(child, className, outList)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "USSDService connected and ready")
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingSendClick()
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "USSDService destroyed")
    }
}
