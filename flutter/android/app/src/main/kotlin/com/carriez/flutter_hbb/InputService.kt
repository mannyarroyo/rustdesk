package com.carriez.flutter_hbb

/**
 * Handle remote input and dispatch android gesture
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import android.view.accessibility.AccessibilityEvent
import android.view.ViewGroup.LayoutParams
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent as KeyEventAndroid
import android.view.ViewConfiguration
import android.view.View
import android.graphics.Rect
import android.media.AudioManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import java.util.*
import java.lang.Character
import kotlin.math.abs
import kotlin.math.max
import hbb.MessageOuterClass.KeyEvent
import hbb.MessageOuterClass.KeyboardMode
import hbb.KeyEventConverter

// const val BUTTON_UP = 2
// const val BUTTON_BACK = 0x08

const val LEFT_DOWN = 9
const val LEFT_MOVE = 8
const val LEFT_UP = 10
const val RIGHT_UP = 18
// (BUTTON_BACK << 3) | BUTTON_UP
const val BACK_UP = 66
const val WHEEL_BUTTON_DOWN = 33
const val WHEEL_BUTTON_UP = 34
const val WHEEL_DOWN = 523331
const val WHEEL_UP = 963

// src/common.rs MOUSE_TYPE_WHEEL: the controller sends a wheel tick as a
// MouseEvent with these type bits and the scroll delta in y (y < 0 = down).
const val MOUSE_TYPE_WHEEL = 3

// Wheel-tick travel for MOUSE_TYPE_WHEEL events. Three quarters of the legacy
// WHEEL_STEP; tuned on real devices during review (the full 120px felt too
// far per tick).
const val MOUSE_WHEEL_STEP = 90

const val TOUCH_SCALE_START = 1
const val TOUCH_SCALE = 2
const val TOUCH_SCALE_END = 3
const val TOUCH_PAN_START = 4
const val TOUCH_PAN_UPDATE = 5
const val TOUCH_PAN_END = 6

const val WHEEL_STEP = 120
const val WHEEL_DURATION = 50L
const val LONG_TAP_DELAY = 200L

class InputService : AccessibilityService() {

    companion object {
        var ctx: InputService? = null
        val isOpen: Boolean
            get() = ctx != null
    }

    private fun notifyInputState() {
        val inputState = isOpen.toString()
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to inputState)
            )
        }
    }

    private val logTag = "input service"
    private var leftIsDown = false
    private var touchPath = Path()
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastTouchGestureStartTime = 0L
    private var mouseX = 0
    private var mouseY = 0
    private var timer = Timer()
    private var recentActionTask: TimerTask? = null
    // 100(tap timeout) + 400(long press timeout)
    private val longPressDuration = ViewConfiguration.getTapTimeout().toLong() + ViewConfiguration.getLongPressTimeout().toLong()

    private val wheelActionsQueue = LinkedList<GestureDescription>()
    private var isWheelActionsPolling = false
    private var isWaitingLongPress = false

    private var fakeEditTextForTextStateCalculation: EditText? = null

    private var lastX = 0
    private var lastY = 0

    private val volumeController: VolumeController by lazy { VolumeController(applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager) }

    /**
     * Handles a remote mouse event on the gesture path. [mask] is the
     * button/phase mask of the mouse protocol ([MOUSE_TYPE_WHEEL] ticks carry
     * the scroll delta in [_y] instead of a position), x/y the screen-space
     * position. Requires API 24 for the gesture APIs.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onLegacyMouseInput(mask, _x, _y)
            return
        }
        val x = max(0, _x)
        val y = max(0, _y)

        if (mask == 0 || mask == LEFT_MOVE) {
            val oldX = mouseX
            val oldY = mouseY
            mouseX = x * SCREEN_INFO.scale
            mouseY = y * SCREEN_INFO.scale
            if (isWaitingLongPress) {
                val delta = abs(oldX - mouseX) + abs(oldY - mouseY)
                Log.d(logTag,"delta:$delta")
                if (delta > 8) {
                    isWaitingLongPress = false
                }
            }
        }

        // left button down, was up
        if (mask == LEFT_DOWN) {
            isWaitingLongPress = true
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (isWaitingLongPress) {
                        isWaitingLongPress = false
                        continueGesture(mouseX, mouseY)
                    }
                }
            }, longPressDuration)

            leftIsDown = true
            startGesture(mouseX, mouseY)
            return
        }

        // short click on TV, no touch stroke started yet
        if (mask == LEFT_UP && leftIsDown && isWaitingLongPress && stroke == null &&
            tryClickTvNodeAt(mouseX, mouseY)) {
            leftIsDown = false
            isWaitingLongPress = false
            touchPath.reset()
            return
        }

        // left down, was down
        if (leftIsDown) {
            continueGesture(mouseX, mouseY)
        }

        // left up, was down
        if (mask == LEFT_UP) {
            if (leftIsDown) {
                leftIsDown = false
                isWaitingLongPress = false
                endGesture(mouseX, mouseY)
                return
            }
        }

        if (mask == RIGHT_UP) {
            longPress(mouseX, mouseY)
            return
        }

        if (mask == BACK_UP) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        // long WHEEL_BUTTON_DOWN -> GLOBAL_ACTION_RECENTS
        if (mask == WHEEL_BUTTON_DOWN) {
            timer.purge()
            recentActionTask = object : TimerTask() {
                override fun run() {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    recentActionTask = null
                }
            }
            timer.schedule(recentActionTask, LONG_TAP_DELAY)
        }

        // wheel button up
        if (mask == WHEEL_BUTTON_UP) {
            if (recentActionTask != null) {
                recentActionTask!!.cancel()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }

        // The controller sends a wheel tick as a MouseEvent with the type bits
        // MOUSE_TYPE_WHEEL (src/common.rs) and the scroll delta in _y
        // (y < 0 = wheel down); the WHEEL_DOWN/WHEEL_UP masks below are legacy
        // values that current controllers never send. Gated to API 24+ like
        // the rest of the gesture path - below that, legacy devices take the
        // Shizuku backend or a no-op, not this class.
        if (mask == MOUSE_TYPE_WHEEL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // A horizontal tick (the controller sends y = 0 when the
            // horizontal delta dominates) or a delta truncated to zero is not
            // a vertical swipe.
            if (_y == 0) {
                return
            }
            if (mouseY < MOUSE_WHEEL_STEP) {
                return
            }
            val path = Path()
            path.moveTo(mouseX.toFloat(), mouseY.toFloat())
            val deltaY = if (_y < 0) -MOUSE_WHEEL_STEP else MOUSE_WHEEL_STEP
            path.lineTo(mouseX.toFloat(), (mouseY + deltaY).toFloat())
            val stroke = GestureDescription.StrokeDescription(
                path,
                0,
                WHEEL_DURATION
            )
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            wheelActionsQueue.offer(builder.build())
            consumeWheelActions()
            return
        }

        if (mask == WHEEL_DOWN) {
            if (mouseY < WHEEL_STEP) {
                return
            }
            val path = Path()
            path.moveTo(mouseX.toFloat(), mouseY.toFloat())
            path.lineTo(mouseX.toFloat(), (mouseY - WHEEL_STEP).toFloat())
            val stroke = GestureDescription.StrokeDescription(
                path,
                0,
                WHEEL_DURATION
            )
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            wheelActionsQueue.offer(builder.build())
            consumeWheelActions()

        }

        if (mask == WHEEL_UP) {
            if (mouseY < WHEEL_STEP) {
                return
            }
            val path = Path()
            path.moveTo(mouseX.toFloat(), mouseY.toFloat())
            path.lineTo(mouseX.toFloat(), (mouseY + WHEEL_STEP).toFloat())
            val stroke = GestureDescription.StrokeDescription(
                path,
                0,
                WHEEL_DURATION
            )
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            wheelActionsQueue.offer(builder.build())
            consumeWheelActions()
        }
    }

    private var legacyDownX = 0
    private var legacyDownY = 0
    private var legacyDownTime = 0L

    // Below API 24 there is no gesture API: taps, drags and long presses go
    // through the device's own ADB shell instead.
    private fun onLegacyMouseInput(mask: Int, _x: Int, _y: Int) {
        if (mask == MOUSE_TYPE_WHEEL) {
            if (_y != 0) {
                val toY = max(0, mouseY + if (_y < 0) -300 else 300)
                LocalAdb.run("input swipe $mouseX $mouseY $mouseX $toY 150")
            }
            return
        }
        val x = max(0, _x) * SCREEN_INFO.scale
        val y = max(0, _y) * SCREEN_INFO.scale
        if (mask != 0 && mask != LEFT_MOVE) {
            Log.d(logTag, "legacy mouse mask=$mask at=$mouseX,$mouseY down=$leftIsDown adb=${LocalAdb.ready}")
        }
        when (mask) {
            0, LEFT_MOVE -> {
                mouseX = x
                mouseY = y
            }
            // Button events carry no position; use the last pointer move.
            LEFT_DOWN -> {
                legacyDownX = mouseX
                legacyDownY = mouseY
                legacyDownTime = android.os.SystemClock.uptimeMillis()
                leftIsDown = true
            }
            LEFT_UP -> if (leftIsDown) {
                leftIsDown = false
                val held = android.os.SystemClock.uptimeMillis() - legacyDownTime
                if (abs(mouseX - legacyDownX) + abs(mouseY - legacyDownY) > 16) {
                    LocalAdb.run("input swipe $legacyDownX $legacyDownY $mouseX $mouseY ${held.coerceIn(100L, 2000L)}")
                } else if (held >= longPressDuration) {
                    LocalAdb.run("input swipe $mouseX $mouseY $mouseX $mouseY $held")
                } else if (!LocalAdb.run("input tap $mouseX $mouseY")) {
                    tryClickTvNodeAt(mouseX, mouseY)
                }
            }
            RIGHT_UP -> LocalAdb.run("input swipe $mouseX $mouseY $mouseX $mouseY $longPressDuration")
            BACK_UP -> performGlobalAction(GLOBAL_ACTION_BACK)
            WHEEL_BUTTON_UP -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onTouchInput(mask: Int, _x: Int, _y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.d(logTag, "legacy touch mask=$mask x=$_x y=$_y ignored")
            return
        }
        when (mask) {
            TOUCH_PAN_UPDATE -> {
                mouseX -= _x * SCREEN_INFO.scale
                mouseY -= _y * SCREEN_INFO.scale
                mouseX = max(0, mouseX);
                mouseY = max(0, mouseY);
                continueGesture(mouseX, mouseY)
            }
            TOUCH_PAN_START -> {
                mouseX = max(0, _x) * SCREEN_INFO.scale
                mouseY = max(0, _y) * SCREEN_INFO.scale
                startGesture(mouseX, mouseY)
            }
            TOUCH_PAN_END -> {
                endGesture(mouseX, mouseY)
                mouseX = max(0, _x) * SCREEN_INFO.scale
                mouseY = max(0, _y) * SCREEN_INFO.scale
            }
            else -> {}
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun consumeWheelActions() {
        if (isWheelActionsPolling) {
            return
        } else {
            isWheelActionsPolling = true
        }
        wheelActionsQueue.poll()?.let {
            dispatchGesture(it, null, null)
            timer.purge()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    isWheelActionsPolling = false
                    consumeWheelActions()
                }
            }, WHEEL_DURATION + 10)
        } ?: let {
            isWheelActionsPolling = false
            return
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun performClick(x: Int, y: Int, duration: Long) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        try {
            val longPressStroke = GestureDescription.StrokeDescription(path, 0, duration)
            val builder = GestureDescription.Builder()
            builder.addStroke(longPressStroke)
            Log.d(logTag, "performClick x:$x y:$y time:$duration")
            dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e(logTag, "performClick, error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun longPress(x: Int, y: Int) {
        performClick(x, y, longPressDuration)
    }

    private fun startGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            touchPath.reset()
        } else {
            touchPath = Path()
        }
        touchPath.moveTo(x.toFloat(), y.toFloat())
        lastTouchGestureStartTime = System.currentTimeMillis()
        lastX = x
        lastY = y
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun doDispatchGesture(x: Int, y: Int, willContinue: Boolean) {
        touchPath.lineTo(x.toFloat(), y.toFloat())
        var duration = System.currentTimeMillis() - lastTouchGestureStartTime
        if (duration <= 0) {
            duration = 1
        }
        try {
            if (stroke == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    stroke = GestureDescription.StrokeDescription(
                        touchPath,
                        0,
                        duration,
                        willContinue
                    )
                } else {
                    stroke = GestureDescription.StrokeDescription(
                        touchPath,
                        0,
                        duration
                    )
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    stroke = stroke?.continueStroke(touchPath, 0, duration, willContinue)
                } else {
                    stroke = null
                    stroke = GestureDescription.StrokeDescription(
                        touchPath,
                        0,
                        duration
                    )
                }
            }
            stroke?.let {
                val builder = GestureDescription.Builder()
                builder.addStroke(it)
                Log.d(logTag, "doDispatchGesture x:$x y:$y time:$duration")
                dispatchGesture(builder.build(), null, null)
            }
        } catch (e: Exception) {
            Log.e(logTag, "doDispatchGesture, willContinue:$willContinue, error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun continueGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            doDispatchGesture(x, y, true)
            touchPath.reset()
            touchPath.moveTo(x.toFloat(), y.toFloat())
            lastTouchGestureStartTime = System.currentTimeMillis()
            lastX = x
            lastY = y
        } else {
            touchPath.lineTo(x.toFloat(), y.toFloat())
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun endGestureBelowO(x: Int, y: Int) {
        try {
            touchPath.lineTo(x.toFloat(), y.toFloat())
            var duration = System.currentTimeMillis() - lastTouchGestureStartTime
            if (duration <= 0) {
                duration = 1
            }
            val stroke = GestureDescription.StrokeDescription(
                touchPath,
                0,
                duration
            )
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            Log.d(logTag, "end gesture x:$x y:$y time:$duration")
            dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e(logTag, "endGesture error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun endGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            doDispatchGesture(x, y, false)
            touchPath.reset()
            stroke = null
        } else {
            endGestureBelowO(x, y)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onKeyEvent(data: ByteArray) {
        val keyEvent = KeyEvent.parseFrom(data)
        val keyboardMode = keyEvent.getMode()

        var textToCommit: String? = null

        // [down] indicates the key's state(down or up).
        // [press] indicates a click event(down and up).
        // https://github.com/rustdesk/rustdesk/blob/3a7594755341f023f56fa4b6a43b60d6b47df88d/flutter/lib/models/input_model.dart#L688
        if (keyEvent.hasSeq()) {
            textToCommit = keyEvent.getSeq()
        } else if (keyboardMode == KeyboardMode.Legacy) {
            if (keyEvent.hasChr() && (keyEvent.getDown() || keyEvent.getPress())) {
                val chr = keyEvent.getChr()
                if (chr != null) {
                    textToCommit = String(Character.toChars(chr))
                }
            }
        } else if (keyboardMode == KeyboardMode.Translate) {
        } else {
        }

        Log.d(logTag, "onKeyEvent $keyEvent textToCommit:$textToCommit")

        var ke: KeyEventAndroid? = null
        if (Build.VERSION.SDK_INT < 33 || textToCommit == null) {
            ke = KeyEventConverter.toAndroidKeyEvent(keyEvent)
        }
        ke?.let { event ->
            if (tryHandleVolumeKeyEvent(event)) {
                return
            } else if (tryHandlePowerKeyEvent(event)) {
                return
            } else if (tryHandleTvNavKeyEvent(event)) {
                return
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            getInputMethod()?.let { inputMethod ->
                inputMethod.getCurrentInputConnection()?.let { inputConnection ->
                    if (textToCommit != null) {
                        textToCommit?.let { text ->
                            inputConnection.commitText(text, 1, null)
                        }
                    } else {
                        ke?.let { event ->
                            inputConnection.sendKeyEvent(event)
                            if (keyEvent.getPress()) {
                                val actionUpEvent = KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode)
                                inputConnection.sendKeyEvent(actionUpEvent)
                            }
                        }
                    }
                }
            }
        } else {
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                ke?.let { event ->
                    val possibleNodes = possibleAccessibiltyNodes()
                    Log.d(logTag, "possibleNodes:$possibleNodes")
                    for (item in possibleNodes) {
                        val success = trySendKeyEvent(event, item, textToCommit)
                        if (success) {
                            if (keyEvent.getPress()) {
                                val actionUpEvent = KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode)
                                trySendKeyEvent(actionUpEvent, item, textToCommit)
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    private fun tryHandleVolumeKeyEvent(event: KeyEventAndroid): Boolean {
        when (event.keyCode) {
            KeyEventAndroid.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    volumeController.raiseVolume(null, true, AudioManager.STREAM_SYSTEM)
                }
                return true
            }
            KeyEventAndroid.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    volumeController.lowerVolume(null, true, AudioManager.STREAM_SYSTEM)
                }
                return true
            }
            KeyEventAndroid.KEYCODE_VOLUME_MUTE -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    volumeController.toggleMute(true, AudioManager.STREAM_SYSTEM)
                }
                return true
            }
            else -> {
                return false
            }
        }
    }

    // Many Android TV boxes (e.g. Android 7 Amlogic boxes) don't declare leanback
    // and even claim a touchscreen, so also look at UI mode and the TV settings app.
    // Override over ADB: settings put global rustdesk_tv_mode 1 (or 0).
    private val isTv: Boolean by lazy {
        val forced = try {
            android.provider.Settings.Global.getInt(contentResolver, "rustdesk_tv_mode", -1)
        } catch (e: Exception) { -1 }
        if (forced >= 0) return@lazy forced == 1
        val pm = packageManager
        if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) return@lazy true
        val ui = getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        if (ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return@lazy true
        val leanbackHome = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
        if (pm.queryIntentActivities(leanbackHome, 0).isNotEmpty() &&
            pm.getLaunchIntentForPackage("com.android.tv.settings") != null) return@lazy true
        try {
            pm.getPackageInfo("com.android.tv.settings", 0); true
        } catch (e: Exception) { false }
    }

    // TV launchers and apps ignore touch and have no focused text field to take
    // key events, so remote keys must be replayed as D-pad global actions (API 33+).
    private fun tryHandleTvNavKeyEvent(event: KeyEventAndroid): Boolean {
        if (!isTv) {
            return false
        }
        if (Build.VERSION.SDK_INT < 33) {
            return tryHandleLegacyTvNavKeyEvent(event)
        }
        val action = when (event.keyCode) {
            KeyEventAndroid.KEYCODE_DPAD_UP -> GLOBAL_ACTION_DPAD_UP
            KeyEventAndroid.KEYCODE_DPAD_DOWN -> GLOBAL_ACTION_DPAD_DOWN
            KeyEventAndroid.KEYCODE_DPAD_LEFT -> GLOBAL_ACTION_DPAD_LEFT
            KeyEventAndroid.KEYCODE_DPAD_RIGHT -> GLOBAL_ACTION_DPAD_RIGHT
            KeyEventAndroid.KEYCODE_ENTER,
            KeyEventAndroid.KEYCODE_NUMPAD_ENTER,
            KeyEventAndroid.KEYCODE_DPAD_CENTER -> GLOBAL_ACTION_DPAD_CENTER
            KeyEventAndroid.KEYCODE_ESCAPE,
            KeyEventAndroid.KEYCODE_BACK -> GLOBAL_ACTION_BACK
            KeyEventAndroid.KEYCODE_MOVE_HOME,
            KeyEventAndroid.KEYCODE_HOME -> GLOBAL_ACTION_HOME
            else -> return false
        }
        if (action != GLOBAL_ACTION_BACK && isEditingText()) {
            return false
        }
        if (event.action == KeyEventAndroid.ACTION_DOWN) {
            performGlobalAction(action)
        }
        return true
    }

    // Android 5-12 has no D-pad global actions, so move the focus highlight
    // ourselves through the accessibility tree and click the focused element.
    private fun tryHandleLegacyTvNavKeyEvent(event: KeyEventAndroid): Boolean {
        val direction = when (event.keyCode) {
            KeyEventAndroid.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEventAndroid.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEventAndroid.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEventAndroid.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEventAndroid.KEYCODE_ENTER,
            KeyEventAndroid.KEYCODE_NUMPAD_ENTER,
            KeyEventAndroid.KEYCODE_DPAD_CENTER -> 0
            KeyEventAndroid.KEYCODE_ESCAPE,
            KeyEventAndroid.KEYCODE_BACK -> -1
            KeyEventAndroid.KEYCODE_MOVE_HOME,
            KeyEventAndroid.KEYCODE_HOME -> -2
            else -> return false
        }
        if (direction >= 0 && isEditingText()) {
            return false
        }
        if (event.action != KeyEventAndroid.ACTION_DOWN) {
            return true
        }
        when (direction) {
            -1 -> performGlobalAction(GLOBAL_ACTION_BACK)
            -2 -> performGlobalAction(GLOBAL_ACTION_HOME)
            0 -> clickFocusedTvNode()
            else -> moveTvFocus(direction)
        }
        return true
    }

    private fun focusedTvNode(): AccessibilityNodeInfo? {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (node != null && node.isVisibleToUser) node else null
    }

    private fun collectTvFocusables(): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val root = rootInActiveWindow ?: return result
        val queue = LinkedList<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            if (!node.isVisibleToUser) {
                continue
            }
            if (node.isFocusable && node.isEnabled) {
                result.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    private fun moveTvFocus(direction: Int) {
        val current = focusedTvNode()
        if (current == null) {
            // nothing focused yet: focus the top-left-most element
            val first = collectTvFocusables().minByOrNull {
                val r = Rect(); it.getBoundsInScreen(r); r.top * 4 + r.left
            }
            first?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return
        }
        val from = Rect()
        current.getBoundsInScreen(from)
        // Ask the app's own focus finder first, like a real remote would.
        val next = current.focusSearch(direction)
        if (next != null && next != current && next.isVisibleToUser &&
            next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            return
        }
        // Otherwise pick the nearest focusable element in that direction.
        var best: AccessibilityNodeInfo? = null
        var bestScore = Long.MAX_VALUE
        val r = Rect()
        for (node in collectTvFocusables()) {
            if (node == current) continue
            node.getBoundsInScreen(r)
            if (r.contains(from)) continue
            val dx = r.centerX() - from.centerX()
            val dy = r.centerY() - from.centerY()
            val (major, minor) = when (direction) {
                View.FOCUS_UP -> Pair(from.top - r.bottom, dx)
                View.FOCUS_DOWN -> Pair(r.top - from.bottom, dx)
                View.FOCUS_LEFT -> Pair(from.left - r.right, dy)
                else -> Pair(r.left - from.right, dy)
            }
            val ahead = when (direction) {
                View.FOCUS_UP -> dy < 0
                View.FOCUS_DOWN -> dy > 0
                View.FOCUS_LEFT -> dx < 0
                else -> dx > 0
            }
            if (!ahead) continue
            val m = maxOf(major, 0).toLong()
            val score = 13 * m * m + minor.toLong() * minor
            if (score < bestScore) {
                bestScore = score
                best = node
            }
        }
        if (best?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true) {
            return
        }
        // At the edge of a list: scroll it, the app then moves focus itself.
        val action = if (direction == View.FOCUS_UP || direction == View.FOCUS_LEFT)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var p: AccessibilityNodeInfo? = current
        while (p != null) {
            if (p.isScrollable && p.performAction(action)) return
            p = p.parent
        }
    }

    private fun clickFocusedTvNode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N && LocalAdb.run("input keyevent 23")) {
            return
        }
        val node = focusedTvNode()
        if (node == null) {
            Log.d(logTag, "tv enter: no focused node")
            return
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        Log.d(logTag, "tv enter: ${node.className} clickable=${node.isClickable} bounds=$r")
        // Only click the highlighted element itself: clicking a clickable parent
        // (a whole row or page) usually does nothing in TV launchers.
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return
        }
        // Not clickable via accessibility: tap the middle of it instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !r.isEmpty) {
            val path = Path()
            path.moveTo(r.exactCenterX(), r.exactCenterY())
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            val sent = dispatchGesture(gesture, null, null)
            Log.d(logTag, "tv enter: tap fallback sent=$sent")
        }
    }

    private fun isEditingText(): Boolean {
        return findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true
    }

    // On TV a mouse click is delivered to the UI element under the pointer,
    // since most TV apps ignore injected touches.
    private fun tryClickTvNodeAt(x: Int, y: Int): Boolean {
        if (!isTv) {
            return false
        }
        val root = rootInActiveWindow ?: return false
        var target: AccessibilityNodeInfo? = null
        val bounds = Rect()
        val queue = LinkedList<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            node.getBoundsInScreen(bounds)
            if (!node.isVisibleToUser || !bounds.contains(x, y)) {
                continue
            }
            if (node.isClickable || node.isFocusable) {
                target = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        val node = target ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun tryHandlePowerKeyEvent(event: KeyEventAndroid): Boolean {
        if (event.keyCode == KeyEventAndroid.KEYCODE_POWER) {
            // Perform power dialog action when action is up
            if (event.action == KeyEventAndroid.ACTION_UP) {
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
            }
            return true
        }
        return false
    }

    private fun insertAccessibilityNode(list: LinkedList<AccessibilityNodeInfo>, node: AccessibilityNodeInfo) {
        if (node == null) {
            return
        }
        if (list.contains(node)) {
            return
        }
        list.add(node)
    }

    private fun findChildNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) {
            return null
        }
        if (node.isEditable() && node.isFocusable()) {
            return node
        }
        val childCount = node.getChildCount()
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (child.isEditable() && child.isFocusable()) {
                    return child
                }
                if (Build.VERSION.SDK_INT < 33) {
                    child.recycle()
                }
            }
        }
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findChildNode(child)
                if (Build.VERSION.SDK_INT < 33) {
                    if (child != result) {
                        child.recycle()
                    }
                }
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }

    private fun possibleAccessibiltyNodes(): LinkedList<AccessibilityNodeInfo> {
        val linkedList = LinkedList<AccessibilityNodeInfo>()
        val latestList = LinkedList<AccessibilityNodeInfo>()

        val focusInput = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        var focusAccessibilityInput = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        val rootInActiveWindow = getRootInActiveWindow()

        Log.d(logTag, "focusInput:$focusInput focusAccessibilityInput:$focusAccessibilityInput rootInActiveWindow:$rootInActiveWindow")

        if (focusInput != null) {
            if (focusInput.isFocusable() && focusInput.isEditable()) {
                insertAccessibilityNode(linkedList, focusInput)
            } else {
                insertAccessibilityNode(latestList, focusInput)
            }
        }

        if (focusAccessibilityInput != null) {
            if (focusAccessibilityInput.isFocusable() && focusAccessibilityInput.isEditable()) {
                insertAccessibilityNode(linkedList, focusAccessibilityInput)
            } else {
                insertAccessibilityNode(latestList, focusAccessibilityInput)
            }
        }

        val childFromFocusInput = findChildNode(focusInput)
        Log.d(logTag, "childFromFocusInput:$childFromFocusInput")

        if (childFromFocusInput != null) {
            insertAccessibilityNode(linkedList, childFromFocusInput)
        }

        val childFromFocusAccessibilityInput = findChildNode(focusAccessibilityInput)
        if (childFromFocusAccessibilityInput != null) {
            insertAccessibilityNode(linkedList, childFromFocusAccessibilityInput)
        }
        Log.d(logTag, "childFromFocusAccessibilityInput:$childFromFocusAccessibilityInput")

        if (rootInActiveWindow != null) {
            insertAccessibilityNode(linkedList, rootInActiveWindow)
        }

        for (item in latestList) {
            insertAccessibilityNode(linkedList, item)
        }

        return linkedList
    }

    private fun trySendKeyEvent(event: KeyEventAndroid, node: AccessibilityNodeInfo, textToCommit: String?): Boolean {
        node.refresh()
        this.fakeEditTextForTextStateCalculation?.setSelection(0,0)
        this.fakeEditTextForTextStateCalculation?.setText(null)

        val text = node.getText()
        var isShowingHint = false
        if (Build.VERSION.SDK_INT >= 26) {
            isShowingHint = node.isShowingHintText()
        }

        var textSelectionStart = node.textSelectionStart
        var textSelectionEnd = node.textSelectionEnd

        if (text != null) {
            if (textSelectionStart > text.length) {
                textSelectionStart = text.length
            }
            if (textSelectionEnd > text.length) {
                textSelectionEnd = text.length
            }
            if (textSelectionStart > textSelectionEnd) {
                textSelectionStart = textSelectionEnd
            }
        }

        var success = false

        Log.d(logTag, "existing text:$text textToCommit:$textToCommit textSelectionStart:$textSelectionStart textSelectionEnd:$textSelectionEnd")

        if (textToCommit != null) {
            if ((textSelectionStart == -1) || (textSelectionEnd == -1)) {
                val newText = textToCommit
                this.fakeEditTextForTextStateCalculation?.setText(newText)
                success = updateTextForAccessibilityNode(node)
            } else if (text != null) {
                this.fakeEditTextForTextStateCalculation?.setText(text)
                this.fakeEditTextForTextStateCalculation?.setSelection(
                    textSelectionStart,
                    textSelectionEnd
                )
                this.fakeEditTextForTextStateCalculation?.text?.insert(textSelectionStart, textToCommit)
                success = updateTextAndSelectionForAccessibiltyNode(node)
            }
        } else {
            if (isShowingHint) {
                this.fakeEditTextForTextStateCalculation?.setText(null)
            } else {
                this.fakeEditTextForTextStateCalculation?.setText(text)
            }
            if (textSelectionStart != -1 && textSelectionEnd != -1) {
                Log.d(logTag, "setting selection $textSelectionStart $textSelectionEnd")
                this.fakeEditTextForTextStateCalculation?.setSelection(
                    textSelectionStart,
                    textSelectionEnd
                )
            }

            this.fakeEditTextForTextStateCalculation?.let {
                // This is essiential to make sure layout object is created. OnKeyDown may not work if layout is not created.
                val rect = Rect()
                node.getBoundsInScreen(rect)

                it.layout(rect.left, rect.top, rect.right, rect.bottom)
                it.onPreDraw()
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    val succ = it.onKeyDown(event.getKeyCode(), event)
                    Log.d(logTag, "onKeyDown $succ")
                } else if (event.action == KeyEventAndroid.ACTION_UP) {
                    val success = it.onKeyUp(event.getKeyCode(), event)
                    Log.d(logTag, "keyup $success")
                } else {}
            }

            success = updateTextAndSelectionForAccessibiltyNode(node)
        }
        return success
    }

    fun updateTextForAccessibilityNode(node: AccessibilityNodeInfo): Boolean {
        var success = false
        this.fakeEditTextForTextStateCalculation?.text?.let {
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                it.toString()
            )
            success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return success
    }

    fun updateTextAndSelectionForAccessibiltyNode(node: AccessibilityNodeInfo): Boolean {
        var success = updateTextForAccessibilityNode(node)

        if (success) {
            val selectionStart = this.fakeEditTextForTextStateCalculation?.selectionStart
            val selectionEnd = this.fakeEditTextForTextStateCalculation?.selectionEnd

            if (selectionStart != null && selectionEnd != null) {
                val arguments = Bundle()
                arguments.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    selectionStart
                )
                arguments.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    selectionEnd
                )
                success = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
                Log.d(logTag, "Update selection to $selectionStart $selectionEnd success:$success")
            }
        }

        return success
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent) {
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ctx = this
        notifyInputState()
        val info = AccessibilityServiceInfo()
        if (Build.VERSION.SDK_INT >= 33) {
            info.flags = FLAG_INPUT_METHOD_EDITOR or FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        } else {
            info.flags = FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        setServiceInfo(info)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            LocalAdb.start(this)
        }
        // Customer just switched us on from the setup screen: bring them back.
        val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
        if (USTREAMING && prefs.getBoolean(KEY_USTREAMING_SETUP_PENDING, false)) {
            prefs.edit().putBoolean(KEY_USTREAMING_SETUP_PENDING, false).apply()
            try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (e: Exception) {
                Log.e(logTag, "return to app failed: $e")
            }
        }
        fakeEditTextForTextStateCalculation = EditText(this)
        // Size here doesn't matter, we won't show this view.
        fakeEditTextForTextStateCalculation?.layoutParams = LayoutParams(100, 100)
        fakeEditTextForTextStateCalculation?.onPreDraw()
        val layout = fakeEditTextForTextStateCalculation?.getLayout()
        Log.d(logTag, "fakeEditTextForTextStateCalculation layout:$layout")
        Log.d(logTag, "onServiceConnected!")
    }

    override fun onDestroy() {
        ctx = null
        // Keep this fallback even though onUnbind usually notifies first.
        notifyInputState()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ctx = null
        notifyInputState()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}
}
