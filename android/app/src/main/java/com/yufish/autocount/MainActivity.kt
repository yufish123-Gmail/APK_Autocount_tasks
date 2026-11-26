package com.yufish.autocount

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var task: Spinner
    private lateinit var foreground: CheckBox
    private lateinit var images: CheckBox
    private lateinit var start: Button
    private val tasks = linkedMapOf(
        "八个日常阶段" to "all", "本机自检（截图 / OCR / 引擎）" to "selftest", "只读识别当前页面" to "preview",
        "跳转登录 APP 领奖励（独立）" to "appjump", "浏览页面" to "page",
        "浏览好物" to "goods", "优惠活动" to "promo", "60 秒游戏" to "games",
        "10 秒浏览" to "browse10", "20 秒游戏" to "play20", "听书" to "listen30",
        "短剧" to "watch60", "游戏中心导航" to "center"
    )
    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 48)
            setBackgroundColor(Color.rgb(248, 249, 252))
        }
        fun text(content: String, size: Float = 16f): TextView {
            return TextView(this).apply {
                text = content
                textSize = size
                setTextColor(Color.rgb(28, 37, 54))
                setPadding(0, 16, 0, 16)
                body.addView(this)
            }
        }
        fun button(label: String, action: () -> Unit): Button {
            return Button(this).apply {
                text = label
                setOnClickListener { action() }
                body.addView(this)
            }
        }
        text("加次数助手", 28f)
        text("0.1.0 · Android 本地版\n运行前请将屏幕设置为 1080×2400 竖屏，保持解锁，并打开美团「加次数」页面。")
        status = text("正在检查服务状态", 18f)
        button("开启无障碍服务") { explainAccess() }
        if (Build.VERSION.SDK_INT >= 33) {
            button("允许运行通知") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        task = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, tasks.keys.toList())
            body.addView(this)
        }
        foreground = CheckBox(this).apply {
            setText(R.string.foreground_games)
            body.addView(this)
        }
        images = CheckBox(this).apply {
            text = "保存诊断截图（可能含页面个人信息）"
            body.addView(this)
        }
        start = button("开始 / 按实际进度继续") {
            val service = AutomationService.instance
            if (service == null) {
                Toast.makeText(this, "请先开启无障碍服务。", Toast.LENGTH_LONG).show()
            } else if (!service.start(tasks.values.elementAt(task.selectedItemPosition), foreground.isChecked, images.isChecked)) {
                Toast.makeText(this, if (service.running()) "前一次运行正在结束，请稍候。" else service.currentStatus(), Toast.LENGTH_LONG).show()
            }
            refresh()
        }
        button("暂停 / 停止操作") { AutomationService.instance?.stop(); refresh() }
        button("导出运行记录") { exportLogs() }
        details = text("", 12f)
        setContentView(ScrollView(this).apply { addView(body) })
        val filter = IntentFilter(AutomationService.UPDATE)
        ContextCompat.registerReceiver(this, updates, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) refresh() }
    override fun onDestroy() { unregisterReceiver(updates); super.onDestroy() }

    private fun refresh() {
        val service = AutomationService.instance
        status.text = service?.currentStatus()
            ?: getSharedPreferences("state", MODE_PRIVATE).getString("status", "请先开启无障碍服务")
        if (service == null) status.append("\n无障碍服务当前未连接")
        start.isEnabled = service != null && !service.running()
        details.text = service?.logText()?.takeLast(12000) ?: ""
    }

    private fun explainAccess() {
        AlertDialog.Builder(this).setTitle("屏幕操作授权")
            .setMessage("此服务会在您主动启动任务后读取相关应用窗口、截图并执行点击、滑动和返回，用于美团加次数。识别在手机本地进行，运行记录保存在本应用内。您可通过通知或本页停止，也可在系统设置关闭服务。\n\n开启前请确认您理解并同意这些操作。")
            .setNegativeButton("取消", null)
            .setPositiveButton("同意并打开设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.show()
    }

    private fun exportLogs() {
        if (AutomationService.instance?.running() == true) {
            Toast.makeText(this, "请先停止任务再导出。", Toast.LENGTH_LONG).show()
            return
        }
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val runs = File(filesDir, "automation/runs")
                val export = File(cacheDir, "exports").apply { mkdirs() }
                val archive = File(export, "autocount-logs.zip")
                ZipOutputStream(archive.outputStream()).use { zip ->
                    runs.walkTopDown().filter { it.isFile }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.relativeTo(runs).invariantSeparatorsPath))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                runOnUiThread {
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", archive)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "导出运行记录"))
                }
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, "导出失败：" + error.message, Toast.LENGTH_LONG).show() }
            } finally { executor.shutdown() }
        }
    }
}
