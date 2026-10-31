package com.yunx.app.data.download

import java.io.File

interface DownloadSystemEvents {
    fun started(keepAwake: Boolean) {}
    fun finished() {}
    fun progress(done: Long, total: Long, title: String, detail: String, speed: String) {}
    object None : DownloadSystemEvents
}

/** Optional external engine; the local Mac edition only uses the built-in downloader. */
interface ExternalDownloadEngine {
    data class TaskView(val id: String, val status: String, val downloaded: Long, val total: Long, val speed: Long)
    data class TaskDetail(val name: String, val fileCount: Int, val folder: Boolean)
    fun isInstalled(): Boolean
    fun isRunning(): Boolean
    fun start(directory: File): Int
    fun settingsSignature(): String
    fun applyRuntimeConfig(maxRunning: Int): Int?
    fun createTask(url: String, saveDir: String, fileName: String, headers: Map<String, String>, connections: Int, label: String): String
    fun pauseTask(id: String)
    fun continueTask(id: String)
    fun deleteTask(id: String)
    fun taskStatus(id: String): TaskView
    fun taskConnections(id: String): Int?
    fun taskDetail(id: String): TaskDetail
}

/**
 * 合轨工具链钩子：ffmpeg 定位与「缺引擎」提示。
 * 桌面端注入实现（FfplayManager 定位 + EngineInstallPrompt 弹窗）；无桌面环境（Docker server）
 * 不注入 —— ffmpeg 合轨自动跳过，退回纯 Java 的 [DashMuxer] / 保留 TS 产物。
 */
interface MergeToolchain {
    /** 定位本机 ffmpeg 可执行文件；null = 不可用 */
    fun locateFfmpeg(): File?

    /** 缺引擎时提示用户安装（桌面端弹窗，非阻塞；服务端空操作） */
    fun askEngineInstall()
}
