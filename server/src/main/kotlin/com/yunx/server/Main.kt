package com.yunx.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.yunx.app.AppContext
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.download.ChunkDownloader
import com.yunx.app.data.download.DownloadCacheMaintenance
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.network.CredentialExpiredException
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.LogRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class WebServer(
    private val service: ServerService,
    private val username: String,
    private val password: String,
    address: InetSocketAddress,
    private val login: LoginAuth = LoginAuth(null, password)
) {
    init {
        require(username.isNotBlank() && ':' !in username) { "YUNX_USERNAME must be nonempty and cannot contain ':'" }
        require(password.length >= 12) { "Set YUNX_PASSWORD to at least 12 characters" }
    }
    private val server = HttpServer.create(address, 64)
    private val executor = ThreadPoolExecutor(
        8, 8, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64), ThreadPoolExecutor.CallerRunsPolicy()
    )
    val port: Int get() = server.address.port

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                try { handle(exchange) }
                catch (_: BodyTooLarge) { json(exchange, 413, JSONObject().put("error", "请求内容过大")) }
                catch (e: Exception) {
                    // 凭证失效类错误带 kind=auth：前端账号卡容量区红字「登录失败」，
                    // 其余（网络/限流/上游抖动）维持普通错误样式。
                    val payload = JSONObject().put("error", LogRedactor.line(e.message ?: "请求失败").take(1000))
                    if (e is CredentialExpiredException) payload.put("kind", "auth")
                    json(exchange, 400, payload)
                }
            } finally { exchange.close() }
        }
    }

    fun start() = server.start()
    fun stop() {
        server.stop(1)
        executor.shutdownNow()
    }

    private fun authenticated(exchange: HttpExchange): Boolean {
        return login.valid(sessionToken(exchange))
    }
    private fun sessionToken(e: HttpExchange): String? = e.requestHeaders.getFirst("Cookie")?.split(';')
        ?.map { it.trim() }?.firstOrNull { it.startsWith("yunx_session=") }?.substringAfter('=')
    private fun sessionCookie(e: HttpExchange, token: String, lifetime: Int = 43200) {
        val secure = if (e.requestHeaders.getFirst("X-Forwarded-Proto") == "https") "; Secure" else ""
        e.responseHeaders.add("Set-Cookie", "yunx_session=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=$lifetime$secure")
    }

    private fun handle(e: HttpExchange) {
        e.responseHeaders.set("X-Content-Type-Options", "nosniff")
        e.responseHeaders.set("Referrer-Policy", "no-referrer")
        e.responseHeaders.set("X-Frame-Options", "DENY")
        e.responseHeaders.set("Cache-Control", "no-store")
        e.responseHeaders.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        val path = e.requestURI.path
        if (path == "/health" && e.requestMethod == "GET") {
            json(e, 200, JSONObject().put("status", "ok"))
            return
        }
        if (e.requestMethod == "GET" && !path.startsWith("/api/")) {
            when (path) {
                "/", "/login" -> resource(e, "index.html", "text/html; charset=utf-8")
                "/app.js", "/lucide.min.js" -> resource(e, path.drop(1), "text/javascript; charset=utf-8")
                "/style.css" -> resource(e, "style.css", "text/css; charset=utf-8")
                "/icon.png" -> resource(e, "icon.png", "image/png")
                "/fonts/space-grotesk.ttf", "/fonts/space-mono.ttf" -> resource(e, path.drop(1), "font/ttf")
                else -> json(e, 404, JSONObject().put("error", "页面不存在"))
            }
            return
        }
        if (path == "/api/auth/session" && e.requestMethod == "GET") {
            val valid = authenticated(e)
            json(e, 200, JSONObject().put("authenticated", valid).put("username", if (valid) username else "")
                .put("passwordManaged", login.managed))
            return
        }
        if (path != "/api/auth/login" && !authenticated(e)) {
            json(e, 401, JSONObject().put("error", "请登录"))
            return
        }
        if (e.requestMethod == "GET") {
            when (path) {
                "/" -> resource(e, "index.html", "text/html; charset=utf-8")
                "/app.js" -> resource(e, "app.js", "text/javascript; charset=utf-8")
                "/lucide.min.js" -> resource(e, "lucide.min.js", "text/javascript; charset=utf-8")
                "/style.css" -> resource(e, "style.css", "text/css; charset=utf-8")
                "/icon.png" -> resource(e, "icon.png", "image/png")
                "/api/accounts" -> json(e, 200, service.accounts())
                "/api/tasks" -> json(e, 200, runBlocking { service.tasks() })
                "/api/task-file" -> serveTaskFile(e)
                "/api/settings" -> json(e, 200, service.settings())
                "/api/engine" -> json(e, 200, service.engineStatus())
                "/api/library" -> json(e, 200, runBlocking { service.library() })
                else -> json(e, 404, JSONObject().put("error", "页面不存在"))
            }
            return
        }
        if (e.requestMethod != "POST") {
            json(e, 405, JSONObject().put("error", "不支持此请求方式"))
            return
        }
        // Cross-origin forms cannot supply this header; CORS preflight is never allowed.
        if (e.requestHeaders.getFirst("X-YunX-Request") != "1" ||
            e.requestHeaders.getFirst("Sec-Fetch-Site") == "cross-site") {
            json(e, 403, JSONObject().put("error", "请求来源不允许"))
            return
        }
        if (e.requestHeaders.getFirst("Content-Type")?.substringBefore(';') != "application/json") {
            json(e, 415, JSONObject().put("error", "需要 JSON 请求"))
            return
        }
        // 备份导入要装下完整加密备份（AccountBackup.decode 上限 2MB，JSON 转义留余量）；
        // 其余接口维持 256KB 防滥用上限。
        val maxBody = if (path == "/api/backup/import") 4 * 1024 * 1024 else 262144
        val bytes = e.requestBody.readNBytes(maxBody + 1)
        if (bytes.size > maxBody) throw BodyTooLarge()
        val body = JSONObject(String(bytes, Charsets.UTF_8))
        if (path == "/api/auth/login") {
            val token = if (body.optString("username") == username)
                login.login(body.optString("password"), e.remoteAddress.address.hostAddress) else {
                login.login("", e.remoteAddress.address.hostAddress); null
            }
            if (token == null) { json(e, 401, JSONObject().put("error", "用户名或密码不正确，连续失败后请稍后重试")); return }
            sessionCookie(e, token)
            json(e, 200, JSONObject().put("ok", true)); return
        }
        if (path == "/api/auth/logout") {
            login.logout(sessionToken(e)); sessionCookie(e, "", 0)
            json(e, 200, JSONObject().put("ok", true)); return
        }
        if (path == "/api/auth/password") {
            login.change(body.getString("current"), body.getString("password"))
            sessionCookie(e, "", 0)
            json(e, 200, JSONObject().put("ok", true)); return
        }
        val result: Any = runBlocking {
            when (path) {
                "/api/accounts" -> { service.saveAccount(body); JSONObject().put("ok", true) }
                "/api/resolve" -> service.resolve(body)
                "/api/files" -> service.list(body)
                "/api/download" -> service.enqueue(body)
                "/api/direct" -> service.direct(body)
                "/api/tasks" -> { service.taskAction(body); JSONObject().put("ok", true) }
                "/api/account-info" -> service.accountInfo(body)
                "/api/cloud/files" -> service.cloudFiles(body)
                "/api/cloud/download" -> service.cloudDownload(body)
                "/api/video" -> service.video(body)
                "/api/video/download" -> service.videoDownload(body)
                "/api/backup/export" -> service.backupExport(body)
                "/api/backup/import" -> service.backupImport(body)
                "/api/engine" -> service.engineInstall()
                "/api/settings" -> service.saveSettings(body)
                "/api/settings/threads" -> { service.savePlatformThreads(body); JSONObject().put("ok", true) }
                "/api/library" -> service.libraryAction(body)
                else -> { json(e, 404, JSONObject().put("error", "接口不存在")); return@runBlocking null }
            }
        } ?: return
        json(e, 200, result)
    }

    private fun resource(e: HttpExchange, name: String, type: String) {
        val bytes = javaClass.getResourceAsStream("/web/$name")?.use { it.readBytes() }
            ?: error("Missing web resource")
        respond(e, 200, type, bytes)
    }

    /**
     * GET /api/task-file?id=N：把已完成任务的落盘文件流式发给浏览器（会话 Cookie 鉴权）。
     * 支持单段 Range 断点续传；文件名经 Content-Disposition（UTF-8 * 编码）下发给浏览器保存。
     */
    private fun serveTaskFile(e: HttpExchange) {
        val id = e.requestURI.rawQuery.orEmpty().split('&')
            .firstOrNull { it.substringBefore('=') == "id" }?.substringAfter('=')?.toLongOrNull()
        if (id == null) { json(e, 400, JSONObject().put("error", "参数无效")); return }
        val (file, fileName) = runBlocking { service.completedTaskFile(id) }
            ?: run { json(e, 404, JSONObject().put("error", "文件不存在或已删除")); return }
        val length = file.length()
        var status = 200
        var start = 0L
        var end = length - 1
        e.requestHeaders.getFirst("Range")?.trim()?.let { range ->
            Regex("^bytes=(\\d*)-(\\d*)$").find(range)?.let { match ->
                val (s, t) = match.destructured
                when {
                    s.isNotEmpty() -> { start = s.toLong(); if (t.isNotEmpty()) end = t.toLong().coerceAtMost(end) }
                    t.isNotEmpty() -> { start = (length - t.toLong()).coerceAtLeast(0) }
                }
                if (start in 0 until length && end in start until length) status = 206 else { start = 0; end = length - 1 }
            }
        }
        e.responseHeaders.set("Content-Type", "application/octet-stream")
        e.responseHeaders.set("Accept-Ranges", "bytes")
        val encoded = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val asciiSafe = fileName.replace(Regex("[^\\x20-\\x7e]"), "_").replace("\"", "_")
        e.responseHeaders.set("Content-Disposition", "attachment; filename=\"$asciiSafe\"; filename*=UTF-8''$encoded")
        if (status == 206) e.responseHeaders.set("Content-Range", "bytes $start-$end/$length")
        e.sendResponseHeaders(status, if (length == 0L) -1L else end - start + 1)
        file.inputStream().use { input ->
            var toSkip = start
            while (toSkip > 0) { val n = input.skip(toSkip); if (n <= 0) break; toSkip -= n }
            var remaining = end - start + 1
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) break
                e.responseBody.write(buffer, 0, n)
                remaining -= n
            }
            e.responseBody.flush()
        }
    }

    private fun json(e: HttpExchange, status: Int, value: Any) =
        respond(e, status, "application/json; charset=utf-8", value.toString().toByteArray(Charsets.UTF_8))

    private fun respond(e: HttpExchange, status: Int, type: String, bytes: ByteArray) {
        e.responseHeaders.set("Content-Type", type)
        e.sendResponseHeaders(status, bytes.size.toLong())
        e.responseBody.write(bytes)
    }

    private class BodyTooLarge : RuntimeException()
}

fun main() {
    val username = System.getenv("YUNX_USERNAME") ?: "admin"
    AppContext.init()
    val authFile = File(AppContext.dataDir, "login-auth.json")
    val configured = System.getenv("YUNX_PASSWORD")?.takeIf { it.isNotBlank() }
    val password = if (authFile.exists() && configured == null) "persisted-login-placeholder"
        else DeploymentPassword.load(AppContext.dataDir, configured)
    val login = LoginAuth(authFile, password, configured != null)
    val downloadDir = File(System.getenv("YUNX_DOWNLOAD_DIR") ?: "/downloads").canonicalFile
    check(downloadDir.isDirectory || downloadDir.mkdirs()) { "Cannot create download directory" }
    check(downloadDir.canWrite()) { "Download directory is not writable by the container user; check the NAS directory owner or ACL" }
    // 网页版没有桌面版的「设置 → 储存目录」：磁盘不足时提示面向部署的处理方式，
    // 而不是让用户去一个不存在的设置项（设置页的「服务器存储」只展示路径与可用空间）。
    DownloadCacheMaintenance.storageHint = {
        "请清理服务器磁盘，或为数据卷 / 下载目录扩容后重试。"
    }
    val db = AppDatabase.get()
    val store = Credentials(File(AppContext.dataDir, "server-credentials.enc"))
    runBlocking { db.downloadTaskDao().markInterruptedAsPaused() }
    // 各平台下载线程（与桌面版同源，SettingsRepository）。旧部署的 _SETTINGS.threads 与
    // 环境变量 YUNX_THREADS 只在偏好里还没有「通用线程」时迁移一次作为默认；之后以网页
    // 「各平台线程配置」为准（每平台键 download_threads_<platform>）。
    val settingsRepo = SettingsRepository()
    if (AppContext.settingsPrefs.get("download_threads", null) == null) {
        val seed = store.get("_SETTINGS").optInt("threads", System.getenv("YUNX_THREADS")?.toIntOrNull() ?: 32)
        AppContext.settingsPrefs.putInt("download_threads", seed.coerceIn(1, 512))
    }
    // P2P 引擎内核：镜像构建期下载的压缩包（YUNX_P2P_KERNEL）在未安装时自动导入一次，
    // 落到 <dataDir>/gopeed/（随数据卷持久化）；运行期在线安装走 /api/engine。
    System.getenv("YUNX_P2P_KERNEL")?.takeIf { it.isNotBlank() }?.let { path ->
        val archive = File(path)
        if (archive.isFile && !com.yunx.app.data.gopeed.GopeedEngine.isInstalled()) {
            runCatching { com.yunx.app.data.gopeed.GopeedEngine.installFromArchive(archive) }
                .onSuccess { println("Gopeed kernel installed from $path") }
                .onFailure { println("Gopeed kernel auto-install failed: ${it.message}") }
        }
    }
    val downloads = DownloadManager(
        db.downloadTaskDao(), ChunkDownloader { HttpClients.downloadClient() },
        threadProvider = { platform -> settingsRepo.downloadThreadsFor(platform) },
        saveDirProvider = { downloadDir.absolutePath },
        concurrencyProvider = { store.get("_SETTINGS").optInt("concurrency", System.getenv("YUNX_CONCURRENCY")?.toIntOrNull() ?: 3).coerceIn(1, 10) },
        speedLimitProvider = { store.get("_SETTINGS").optLong("speedLimit", System.getenv("YUNX_SPEED_LIMIT")?.toLongOrNull() ?: 0L).coerceAtLeast(0) },
        keepAwakeProvider = { false }, showSpeedProvider = { false },
        // 下载引擎选择与桌面版同源（SettingsRepository 的 download_engine 偏好）；
        // 偏好未配置时回退环境变量 YUNX_DOWNLOAD_ENGINE（builtin/gopeed）
        engineEnabledProvider = {
            when (AppContext.settingsPrefs.get("download_engine", null)) {
                "gopeed" -> true
                "builtin" -> false
                else -> System.getenv("YUNX_DOWNLOAD_ENGINE")?.equals("gopeed", ignoreCase = true) == true
            }
        },
        externalEngine = com.yunx.app.data.gopeed.GopeedDownloadEngine
    )
    val service = ServerService(db, downloads, store)
    val server = WebServer(
        service, username, password,
        InetSocketAddress(System.getenv("YUNX_HOST") ?: "0.0.0.0", System.getenv("YUNX_PORT")?.toInt() ?: 8080), login
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
        runBlocking { downloads.shutdown() }
    })
    server.start()
    println("YunX server listening on port ${server.port}; downloads: $downloadDir")
    // 启动即后台预取各平台容量/登录态并缓存(登录失败也缓存、不自动重试,用户更新凭证后再校验),
    // 用户打开账号页直接呈现结果,无需现场等待
    CoroutineScope(Dispatchers.IO).launch { service.prefetchAccountInfos() }
}
