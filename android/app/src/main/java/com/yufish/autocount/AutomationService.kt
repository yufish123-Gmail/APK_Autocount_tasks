package com.yufish.autocount

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.chaquo.python.Python
import androidx.core.graphics.createBitmap
import androidx.core.content.edit
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AutomationService : AccessibilityService() {
    companion object {
        @Volatile var instance: AutomationService? = null
        const val UPDATE = "com.yufish.autocount.UPDATE"
        private const val CHANNEL = "task_status"
        private const val NOTIFICATION = 101
        private const val MEITUAN = "com.sankuai.meituan"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val captureCallbacks = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val session = SessionGate()
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    @Volatile private var lastActivityPackage = ""
    @Volatile private var lastActivityName = ""
    @Volatile private var screenshots = false
    @Volatile private var lastCapture = 0L
    @Volatile private var detail = "服务已连接"
    @Volatile private var resultJson = ""
    @Volatile private var logs = ArrayList<String>()

    override fun onServiceConnected() {
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "任务运行", NotificationManager.IMPORTANCE_LOW)
        )
        status("服务已连接，请选择任务。")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val name = event.className?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            name.contains(".") && name.endsWith("Activity")) {
            lastActivityPackage = pkg
            lastActivityName = if (name.startsWith(".")) pkg + name else name
        }
    }

    override fun onInterrupt() { stop() }

    override fun onDestroy() {
        stop()
        instance = null
        worker.shutdown()
        captureCallbacks.shutdown()
        super.onDestroy()
    }

    fun running() = session.busy()
    fun currentStatus() = detail
    fun lastResult() = resultJson
    fun logText() = synchronized(this) { logs.joinToString("\n") }

    fun start(task: String, foregroundGames: Boolean, saveImages: Boolean): Boolean {
        if (android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) != 4096L) {
            status("当前安装包适配 4 KB 内存页设备，16 KB 设备需更新 native 依赖后使用。")
            return false
        }
        if (!session.begin()) return false
        screenshots = saveImages
        resultJson = ""
        logs = ArrayList()
        status(if (task == "selftest") "正在检查截图、中文识别和任务引擎。" else "准备执行，请保持手机解锁并打开加次数页面。")
        worker.execute {
            try {
                checkActive()
                if (task != "selftest") {
                    openMeituan()
                    Thread.sleep(1500)
                }
                checkActive()
                val result = Python.getInstance().getModule("android_runtime")
                    .callAttr("run", this, task, foregroundGames).toString()
                resultJson = result
                status(JSONObject(result).optString("message", "处理结束"))
            } catch (error: Exception) {
                status(if (session.active()) "运行停止：" + (error.message ?: error.javaClass.simpleName) else "任务已停止")
            } finally {
                session.finish()
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
                sendBroadcast(Intent(UPDATE).setPackage(packageName))
            }
        }
        return true
    }

    fun stop() {
        session.cancel()
        status("正在停止，继续时会重新读取平台进度。")
    }

    fun isActive(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) session.cancel()
        return session.active() && instance === this
    }

    private fun checkActive() {
        check(isActive()) { "会话已停止或屏幕已锁定。" }
        check(Looper.myLooper() != Looper.getMainLooper()) { "识别和控制必须在工作线程执行。" }
    }

    fun saveScreenshots() = screenshots
    fun storagePath(): String = File(filesDir, "automation").apply { mkdirs() }.absolutePath

    fun foreground(): String {
        checkActive()
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        val name = if (pkg != null && pkg == lastActivityPackage) lastActivityName else null
        return JSONArray().put(pkg ?: JSONObject.NULL).put(name ?: JSONObject.NULL).toString()
    }

    private fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun capture(): ByteArray {
        checkActive()
        val elapsed = android.os.SystemClock.elapsedRealtime() - lastCapture
        if (elapsed < 350) Thread.sleep(350 - elapsed)
        checkActive()
        val latch = CountDownLatch(1)
        var data: ByteArray? = null
        var failure: String? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, captureCallbacks, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    result.hardwareBuffer.use { buffer ->
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?: error("无法读取截图缓冲区")
                        val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                        hardware.recycle()
                        try {
                            ByteArrayOutputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                data = it.toByteArray()
                            }
                        } finally { bitmap.recycle() }
                    }
                } catch (error: Exception) { failure = error.message }
                finally { latch.countDown() }
            }
            override fun onFailure(errorCode: Int) {
                failure = "截图不可用，错误码 $errorCode"
                latch.countDown()
            }
        })
        await(latch, 12)
        lastCapture = android.os.SystemClock.elapsedRealtime()
        return data ?: error(failure ?: "截图超时")
    }

    fun ocr(png: ByteArray, layout: Boolean): String {
        checkActive()
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("无法解码识别区域")
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS)
            checkActive()
            if (!layout) return result.text.trim()
            val rows = JSONArray()
            result.textBlocks.forEach { block ->
                block.lines.forEach { line ->
                    line.boundingBox?.let { bounds ->
                        rows.put(JSONObject().put("text", line.text).put("box",
                            JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
                    }
                }
            }
            return rows.toString()
        } finally { bitmap.recycle() }
    }

    fun testImage(): ByteArray {
        val bitmap = createBitmap(720, 180)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawText("任务完成 123", 30f, 115f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 80f
            })
            return ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    fun tap(x: Int, y: Int) = gesture(x, y, x, y, 60)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int) = gesture(x1, y1, x2, y2, duration)

    private fun gesture(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int) {
        checkActive()
        require(x1 in 0..1079 && x2 in 0..1079 && y1 in 0..2399 && y2 in 0..2399)
        val pkg = currentPackage() ?: error("无法确认前台窗口")
        val token = session.token()
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            if (x1 != x2 || y1 != y2) lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong())).build()
        val latch = CountDownLatch(1)
        var complete = false
        main.post {
            if (!isActive() || !session.accepts(token) || currentPackage() != pkg) {
                latch.countDown()
                return@post
            }
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { complete = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            }, null)
            if (!accepted) latch.countDown()
        }
        await(latch, 5)
        check(complete) { "手势取消或前台页面已变化。" }
    }

    fun globalAction(name: String) {
        checkActive()
        val token = session.token()
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            else -> error("未知系统动作")
        }
        val latch = CountDownLatch(1)
        var accepted = false
        main.post {
            if (isActive() && session.accepts(token)) accepted = performGlobalAction(action)
            latch.countDown()
        }
        await(latch, 3)
        check(accepted) { "系统未执行返回或桌面操作。" }
    }

    fun installed(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }

    fun launcherPackage(): String = packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ?: ""

    fun openMeituan() {
        checkActive()
        val token = session.token()
        val intent = packageManager.getLaunchIntentForPackage(MEITUAN) ?: error("未找到已安装的美团")
        val latch = CountDownLatch(1)
        var failure: String? = null
        main.post {
            try {
                if (isActive() && session.accepts(token)) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (error: Exception) { failure = error.message }
            finally { latch.countDown() }
        }
        await(latch, 4)
        failure?.let { error(it) }
    }

    private fun await(latch: CountDownLatch, seconds: Int) {
        val deadline = android.os.SystemClock.elapsedRealtime() + seconds * 1000
        while (!latch.await(100, TimeUnit.MILLISECONDS)) {
            checkActive()
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Android 操作超时。" }
        }
        checkActive()
    }

    fun report(json: String) {
        val event = JSONObject(json)
        val message = event.optString("event") + " " + json
        synchronized(this) {
            logs.add(message)
            if (logs.size > 300) logs.removeAt(0)
        }
        when (event.optString("event")) {
            "stage_started" -> {
                val stage = event.optString("stage")
                val names = mapOf("page" to "浏览页面", "goods" to "浏览好物",
                    "promo" to "优惠活动", "games" to "60 秒游戏", "browse10" to "10 秒浏览",
                    "play20" to "20 秒游戏", "listen30" to "听书", "watch60" to "短剧",
                    "appjump" to "跳转登录 APP 领奖励")
                status("正在执行：" + (names[stage] ?: "任务"))
            }
            "app_jump_progress" -> status("跳转奖励进度：" + event.optInt("completed") + "/8")
            "stopped" -> status(event.optString("reason"))
            else -> sendBroadcast(Intent(UPDATE).setPackage(packageName))
        }
    }

    private fun status(message: String) {
        detail = message
        getSharedPreferences("state", MODE_PRIVATE).edit { putString("status", message) }
        sendBroadcast(Intent(UPDATE).setPackage(packageName))
        if (session.busy()) {
            val stop = PendingIntent.getBroadcast(this, 1, Intent(this, StopReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("加次数助手")
                .setContentText(message).setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
            try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification) }
            catch (_: SecurityException) { /* Controls remain available in the activity. */ }
        }
    }
}

class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) { AutomationService.instance?.stop() }
}
