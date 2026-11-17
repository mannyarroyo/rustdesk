package com.carriez.flutter_hbb

import android.Manifest.permission.*
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioRecord
import android.media.AudioRecord.READ_BLOCKING
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.provider.Settings.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat.getSystemService
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import ffi.FFI
import java.nio.ByteBuffer
import java.util.*


// intent action, extra
const val ACT_REQUEST_MEDIA_PROJECTION = "REQUEST_MEDIA_PROJECTION"
const val ACT_INIT_MEDIA_PROJECTION_AND_SERVICE = "INIT_MEDIA_PROJECTION_AND_SERVICE"
const val ACT_LOGIN_REQ_NOTIFY = "LOGIN_REQ_NOTIFY"
const val EXT_INIT_FROM_BOOT = "EXT_INIT_FROM_BOOT"
const val EXT_MEDIA_PROJECTION_RES_INTENT = "MEDIA_PROJECTION_RES_INTENT"
const val EXT_MEDIA_PROJECTION_RESULT_RECEIVER = "MEDIA_PROJECTION_RESULT_RECEIVER"
const val EXT_LOGIN_REQ_NOTIFY = "LOGIN_REQ_NOTIFY"

// Activity requestCode
const val REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION = 101
const val REQ_REQUEST_MEDIA_PROJECTION = 201
const val REQ_EXPORT_FILE = 301
const val REQ_IMPORT_FILES = 302
const val REQ_IMPORT_DIRECTORY = 303
const val REQ_EXPORT_FILES = 304

// Activity responseCode
const val RES_FAILED = -100

// Flutter channel
const val START_ACTION = "start_action"
const val GET_START_ON_BOOT_OPT = "get_start_on_boot_opt"
const val SET_START_ON_BOOT_OPT = "set_start_on_boot_opt"
const val SYNC_APP_DIR_CONFIG_PATH = "sync_app_dir"
const val PICK_IMPORT_FILES = "pick_import_files"
const val IMPORT_FILE = "import_file"
const val EXPORT_FILE = "export_file"
const val PICK_IMPORT_DIRECTORY = "pick_import_directory"
const val IMPORT_DIRECTORY = "import_directory"
const val EXPORT_FILES = "export_files"
const val GET_VALUE = "get_value"

const val KEY_IS_SUPPORT_VOICE_CALL = "KEY_IS_SUPPORT_VOICE_CALL"

const val KEY_SHARED_PREFERENCES = "KEY_SHARED_PREFERENCES"
const val KEY_START_ON_BOOT_OPT = "KEY_START_ON_BOOT_OPT"
const val KEY_APP_DIR_CONFIG_PATH = "KEY_APP_DIR_CONFIG_PATH"

// U-Streaming Support branded build
const val USTREAMING = true
const val ACT_USTREAMING_START = "USTREAMING_START"
const val KEY_USTREAMING_SETUP_PENDING = "KEY_USTREAMING_SETUP_PENDING"

// While Android's screen-capture prompt is open, remote input is dropped: the
// controller sends clicks before any picture arrives, and on Android 7-9 a
// click outside the prompt closes it before the customer can answer.
@Volatile
var capturePromptOpenedAt = 0L

fun isCapturePromptOpen(): Boolean =
    capturePromptOpenedAt != 0L && java.lang.System.currentTimeMillis() - capturePromptOpenedAt < 120_000

@SuppressLint("ConstantLocale")
val LOCAL_NAME = Locale.getDefault().toString()
val SCREEN_INFO = Info(0, 0, 1, 200)

data class Info(
    var width: Int, var height: Int, var scale: Int, var dpi: Int
)

fun isSupportVoiceCall(): Boolean {
    // https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#VOICE_COMMUNICATION
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}

fun requestPermission(context: Context, type: String) {
    XXPermissions.with(context)
        .permission(type)
        .request { _, all ->
            if (all) {
                Handler(Looper.getMainLooper()).post {
                    MainActivity.flutterMethodChannel?.invokeMethod(
                        "on_android_permission_result",
                        mapOf("type" to type, "result" to all)
                    )
                }
            }
        }
}

fun startAction(context: Context, action: String) {
    try {
        context.startActivity(Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // don't pass package name when launch ACTION_ACCESSIBILITY_SETTINGS
            if (ACTION_ACCESSIBILITY_SETTINGS != action) {
                data = Uri.parse("package:" + context.packageName)
            }
        })
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

class AudioReader(val bufSize: Int, private val maxFrames: Int) {
    private var currentPos = 0
    private val bufferPool: Array<ByteBuffer>

    init {
        if (maxFrames < 0 || maxFrames > 32) {
            throw Exception("Out of bounds")
        }
        if (bufSize <= 0) {
            throw Exception("Wrong bufSize")
        }
        bufferPool = Array(maxFrames) {
            ByteBuffer.allocateDirect(bufSize)
        }
    }

    private fun next() {
        currentPos++
        if (currentPos >= maxFrames) {
            currentPos = 0
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun readSync(audioRecord: AudioRecord): ByteBuffer? {
        val buffer = bufferPool[currentPos]
        val res = audioRecord.read(buffer, bufSize, READ_BLOCKING)
        return if (res > 0) {
            next()
            buffer
        } else {
            null
        }
    }
}


fun getScreenSize(windowManager: WindowManager) : Pair<Int, Int>{
    var w = 0
    var h = 0
    @Suppress("DEPRECATION")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val m = windowManager.maximumWindowMetrics
        w = m.bounds.width()
        h = m.bounds.height()
    } else {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        w = dm.widthPixels
        h = dm.heightPixels
    }
    return Pair(w, h)
}

 fun translate(input: String): String {
    Log.d("common", "translate:$LOCAL_NAME")
    return FFI.translateLocale(LOCAL_NAME, input)
}

/** Starts the U-Streaming host service in the background, if the app was set up. */
fun startUStreamingService(context: Context) {
    val prefs = context.getSharedPreferences(KEY_SHARED_PREFERENCES, Context.MODE_PRIVATE)
    if (prefs.getString(KEY_APP_DIR_CONFIG_PATH, "").isNullOrEmpty()) {
        Log.d("ustreaming", "app never opened, not starting")
        return
    }
    val start = Intent(context, MainService::class.java).apply { action = ACT_USTREAMING_START }
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(start)
        } else {
            context.startService(start)
        }
    } catch (e: Exception) {
        Log.e("ustreaming", "start service failed: $e")
    }
}

/**
 * While the customer looks for the switch in the long Accessibility list,
 * keep reminding them what to do. Stops once the switch is on.
 */
fun startAccessibilityHints(context: Context) {
    val handler = Handler(Looper.getMainLooper())
    val until = java.lang.System.currentTimeMillis() + 3 * 60 * 1000
    val hint = object : Runnable {
        override fun run() {
            if (InputService.isOpen || java.lang.System.currentTimeMillis() > until) return
            Toast.makeText(
                context,
                "Press DOWN to \"U-Streaming Support\" at the bottom of the list, press OK, then Enable.",
                Toast.LENGTH_LONG
            ).show()
            handler.postDelayed(this, 7000)
        }
    }
    handler.postDelayed(hint, 2000)
}
