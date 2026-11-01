package com.yunx.server

import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.db.BookmarkEntity
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.db.LinkHistoryEntity
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.gopeed.GopeedEngine
import com.yunx.app.data.gopeed.GopeedKernelProvisioner
import com.yunx.app.data.network.*
import com.yunx.app.data.network.model.*
import com.yunx.app.data.network.video.VideoLinkParser
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.repository.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ServerService(
    private val db: AppDatabase,
    val downloads: DownloadManager,
    private val credentials: Credentials
) {
    private data class BrowserSession(
        val platform: SharePlatform,
        val source: String,
        val repo: ShareResolveRepository,
        val share: ShareSession,
        val files: MutableMap<String, ShareFile> = mutableMapOf(),
        val dirs: MutableSet<String> = mutableSetOf(),
        val mutex: Mutex = Mutex(),
        val created: Long = System.currentTimeMillis()
    )
    private val sessions = ConcurrentHashMap<String, BrowserSession>()
    private val sessionLock = Mutex()
    private data class CloudSession(val platform: SharePlatform, val revision: Long,
        val files: MutableMap<String, ShareFile> = mutableMapOf(), val dirs: MutableSet<String> = mutableSetOf(),
        val parents: MutableMap<String, String> = mutableMapOf(),
        val listed: MutableMap<String, MutableSet<String>> = mutableMapOf(),
        val mutex: Mutex = Mutex(), val created: Long = System.currentTimeMillis())
    private val cloudSessions = ConcurrentHashMap<String, CloudSession>()
    private val profiles = ConcurrentHashMap<String, JSONObject>()
    private val revisions = ConcurrentHashMap<String, Long>()
    /** 平台操作面注册表：accountInfo / cloudFiles / cloudDownload 的 per-platform 知识都收在 CloudDrives */
    private val drives = CloudDrives(credentials)
    private val github = GitHubApi(tokenProvider = { credentials.get("GITHUB").optString("accessToken") })
    private val githubBrowser = GitHubBrowser(github)
    val video = VideoService(credentials)
    private val settingsRepo = SettingsRepository()

    fun exportAccounts(password: String): String = AccountBackup.export(credentials, password, video.platforms.keys)
    fun importAccounts(content: String, password: String): Int {
        val values = AccountBackup.decode(content, password, video.platforms.keys)
        credentials.saveAll(values)
        values.keys.forEach { revisions.merge(it, 1L, Long::plus); profiles.remove(it) }
        sessions.clear()
        cloudSessions.clear()
        githubBrowser.clear()
        return values.size
    }

    /** 账号备份导出：与桌面版 .yunx 格式互通（AuthCrypto AES-GCM + yunx_auth_backup v1 信封） */
    fun backupExport(body: JSONObject): JSONObject {
        val password = body.getString("password")
        require(password.length in 8..256) { "备份口令至少 8 位" }
        return JSONObject()
            .put("content", exportAccounts(password))
            .put("filename", backupFileName())
    }

    /** 账号备份导入：恢复各平台凭证（含视频平台），返回恢复的平台数 */
    fun backupImport(body: JSONObject): JSONObject {
        val password = body.optString("password")
        val content = body.optString("content")
        require(password.length >= 8) { "备份口令至少 8 位" }
        require(content.length <= 2 * 1024 * 1024) { "备份文件过大（上限 2MB）" }
        return JSONObject().put("imported", importAccounts(content, password))
    }

    private fun backupFileName(): String =
        "yunx_auth_backup_" + java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".yunx"

    fun accounts(): JSONArray = JSONArray(buildList {
        SharePlatform.entries.map { p ->
            JSONObject().put("platform", p.name).put("configured", credentials.credential(p.name).isNotBlank())
                .put("cloudSupported", p != SharePlatform.GITHUB)
                .put("authType", if (p == SharePlatform.XUNLEI) credentials.get(p.name).optString("authType") else "")
                .put("profile", profiles[p.name] ?: JSONObject.NULL)
        }.forEach { add(it) }
        video.configurable.forEach { (key, parser) ->
            add(
                JSONObject().put("platform", key).put("configured", credentials.credential(key).isNotBlank())
                    .put("cloudSupported", false).put("authType", "")
                    .put("label", parser.platform.displayName + "（视频，实验）")
                    .put("profile", profiles[key] ?: JSONObject.NULL)
            )
        }
    })

    fun saveAccount(body: JSONObject) {
        val requested = body.getString("platform")
        if (video.platforms.containsKey(requested)) {
            video.saveCookie(requested, body.optJSONObject("credentials") ?: JSONObject())
            revisions.merge(requested, 1L, Long::plus)
            profiles.remove(requested)
            return
        }
        val platform = SharePlatform.valueOf(requested)
        val input = body.getJSONObject("credentials")
        val allowed = setOf("cookie", "accessToken", "refreshToken", "deviceId", "deviceSign", "captchaToken", "authType", "label", "uuid")
        require(input.keySet().all { it in allowed }) { "未知凭证字段" }
        require(input.keySet().all { input.get(it) is String && input.getString(it).length <= 65536 }) { "凭证格式错误" }
        val next = if (input.length() == 0) JSONObject() else credentials.get(platform.name).apply {
            input.keySet().forEach { key ->
                val value = input.getString(key)
                if (value.isNotBlank() || key in setOf("authType", "label")) put(key, value)
            }
        }
        require(input.length() == 0 || platform != SharePlatform.ILANZOU ||
            (next.optString("accessToken").isNotBlank() && next.optString("uuid").isNotBlank())) { "蓝奏优享需要 App Token 和 UUID" }
        credentials.save(platform.name, next)
        revisions.merge(platform.name, 1L, Long::plus)
        sessions.clear()
        githubBrowser.clear()
        cloudSessions.clear()
        profiles.remove(platform.name)
    }

    fun settings(): JSONObject {
        val saved = credentials.get("_SETTINGS")
        return JSONObject()
            .put("concurrency", saved.optInt("concurrency", System.getenv("YUNX_CONCURRENCY")?.toIntOrNull() ?: 3))
            .put("speedLimit", saved.optLong("speedLimit", System.getenv("YUNX_SPEED_LIMIT")?.toLongOrNull() ?: 0))
            .put("platformThreads", platformThreads())
            .put("downloadEngine", settingsRepo.downloadEngine)
            .put("btTrackers", settingsRepo.btTrackers)
    }

    /** SharePlatform → 下载线程配置键（DownloadPlatform 常量为小写字符串） */
    private fun panDownloadPlatform(p: SharePlatform): String = when (p) {
        SharePlatform.QUARK -> DownloadPlatform.QUARK
        SharePlatform.UC -> DownloadPlatform.UC
        SharePlatform.XUNLEI -> DownloadPlatform.XUNLEI
        SharePlatform.BAIDU -> DownloadPlatform.BAIDU
        SharePlatform.C139 -> DownloadPlatform.C139
        SharePlatform.PAN123 -> DownloadPlatform.PAN123
        SharePlatform.PAN115 -> DownloadPlatform.PAN115
        SharePlatform.GUANGYA -> DownloadPlatform.GUANGYA
        SharePlatform.ILANZOU -> DownloadPlatform.ILANZOU
        SharePlatform.LANZOU -> DownloadPlatform.LANZOU
        SharePlatform.GITHUB -> DownloadPlatform.GITHUB
    }

    /**
     * 各平台下载线程配置（设置页弹窗数据源）：通用 + 全部网盘 + 全部视频平台。
     * 与桌面版同源（SettingsRepository，java.util.prefs）；迅雷固定 8 不可改。
     */
    fun platformThreads(): JSONArray {
        val rows = JSONArray()
        fun row(platform: String, fixed: Boolean) = rows.put(
            JSONObject().put("platform", platform).put("fixed", fixed)
                .put("threads", settingsRepo.downloadThreadsFor(platform))
        )
        row(DownloadPlatform.GENERIC, false)
        SharePlatform.entries.forEach { row(panDownloadPlatform(it), it == SharePlatform.XUNLEI) }
        video.platforms.values.forEach { row(DownloadPlatform.forVideoPlatform(it.platform), false) }
        return rows
    }

    fun savePlatformThreads(body: JSONObject) {
        val input = body.getJSONObject("threads")
        val allowed = buildSet {
            add(DownloadPlatform.GENERIC)
            SharePlatform.entries.forEach { add(panDownloadPlatform(it)) }
            video.platforms.values.forEach { add(DownloadPlatform.forVideoPlatform(it.platform)) }
        }
        require(input.keySet().all { it in allowed }) { "未知平台" }
        require(input.keySet().all { input.get(it) is Int }) { "线程数格式错误" }
        input.keySet().forEach { key ->
            val value = input.getInt(key)
            require(value in 1..512) { "线程数超出范围" }
            settingsRepo.setDownloadThreads(key, value)
        }
    }

    fun saveSettings(body: JSONObject): JSONObject {
        val concurrency = body.getInt("concurrency")
        val speed = body.getLong("speedLimit")
        require(concurrency in 1..10 && speed in 0..1_000_000_000_000L) { "下载设置超出范围" }
        val saved = JSONObject().put("concurrency", concurrency).put("speedLimit", speed)
        credentials.save("_SETTINGS", saved)
        if (body.has("downloadEngine")) {
            val engine = body.getString("downloadEngine")
            require(engine in setOf(SettingsRepository.ENGINE_BUILTIN, SettingsRepository.ENGINE_GOPEED)) { "未知下载引擎" }
            settingsRepo.downloadEngine = engine
        }
        if (body.has("btTrackers")) {
            val trackers = body.getString("btTrackers")
            require(trackers.length <= 65536 && trackers.lines().size <= 500) { "Tracker 列表过长" }
            settingsRepo.btTrackers = trackers
        }
        return saved
    }

    /** 下载引擎状态（设置页「下载引擎」区块数据源） */
    fun engineStatus(): JSONObject = JSONObject()
        .put("enabled", settingsRepo.downloadEngine == SettingsRepository.ENGINE_GOPEED)
        .put("installed", GopeedEngine.isInstalled())
        .put("running", com.yunx.app.data.gopeed.GopeedDownloadEngine.isRunning())
        .put("kernelSize", GopeedEngine.kernelSize())

    /** 在线安装/重装 Gopeed 内核（运行时兜底；镜像内置内核走启动时自动导入） */
    suspend fun engineInstall(): JSONObject {
        val installed = GopeedKernelProvisioner.provision(null) { }
        return JSONObject().put("ok", true).put("size", installed.length())
    }
    suspend fun library(): JSONObject = JSONObject()
        .put("bookmarks", JSONArray(db.bookmarkDao().observeAll().first().map {
            JSONObject().put("id", it.id).put("link", it.link).put("title", it.title).put("password", it.pwd).put("platform", it.platform)
        }))
        .put("history", JSONArray(db.linkHistoryDao().observeAll().first().take(100).map {
            JSONObject().put("id", it.id).put("link", it.url).put("title", it.title).put("password", it.pwd).put("platform", it.platform)
        }))
    suspend fun libraryAction(body: JSONObject): JSONObject {
        val kind = body.getString("kind")
        require(kind in setOf("bookmarks", "history")) { "未知链接类型" }
        if (body.optString("action") == "remove") {
            if (kind == "bookmarks") db.bookmarkDao().delete(body.getLong("id"))
            else db.linkHistoryDao().delete(body.getLong("id"))
        } else {
            require(kind == "bookmarks") { "历史记录由解析生成" }
            val link = body.getString("link").trim()
            require(link.length in 1..10000 && (ShareLinkParser.parse(link) != null || GitHubLinkParser.parse(link) != null)) { "请输入有效分享链接" }
            require(body.optString("password").length <= 256 && body.optString("title").length <= 1000) { "链接信息过长" }
            val existing = db.bookmarkDao().observeAll().first()
            require(existing.size < 200) { "收藏数量已达到 200" }
            if (existing.none { it.link == link }) db.bookmarkDao().insert(BookmarkEntity(
                link = link, title = body.optString("title"), pwd = body.optString("password"),
                platform = ShareLinkParser.parse(link)?.platform?.name ?: "GITHUB"
            ))
        }
        return library()
    }
    /** 启动后台预取:逐平台校验并缓存容量/登录态(失败结果由 accountInfo 一并缓存),
     *  用户打开账号页即直接呈现,无需现场等待。GITHUB/LANZOU 无容量概念不取(与前端批量口径一致)。 */
    suspend fun prefetchAccountInfos() {
        suspend fun prefetch(key: String) {
            if (credentials.credential(key).isBlank()) return
            runCatching { accountInfo(JSONObject().put("platform", key)) }
            delay(300)   // 轻微间隔,避免启动瞬间并发打满上游
        }
        SharePlatform.entries.forEach { p -> if (p != SharePlatform.LANZOU) prefetch(p.name) }   // GITHUB 校验返回登录名作昵称;LANZOU 无昵称无容量不取
        video.platforms.keys.forEach { key -> prefetch(key) }
    }

    suspend fun accountInfo(body: JSONObject): JSONObject = withTimeout(25000) {
        val requested = body.getString("platform")
        if (video.platforms.containsKey(requested)) {
            val profile = video.accountInfo(requested)
            profiles[requested] = profile
            return@withTimeout profile
        }
        val p = SharePlatform.valueOf(requested)
        val c = credentials.credential(p.name)
        val revision = revisions[p.name] ?: 0L
        require(c.isNotBlank()) { "请先添加网盘账号" }
        val account = credentials.get(p.name)
        // 校验型调用：CredentialCheck.Invalid（凭证失效）→ CredentialExpiredException（HTTP 错误带 kind=auth，
        // 前端红字「登录失败」）；Unknown（网络/限流/上游抖动）→ 普通错误（前端「容量暂不可用」）。
        // 各平台凭证字段读取（cookie / token 三元组 / GuangYaDevice / uuid）与校验签名差异收在 CloudDrives。
        val checked = drives.forPlatform(p).credentialCheck()
        val check: CredentialCheck = checked.check
        val name: String? = checked.nickname
        // 失败结果也写入缓存并随账号列表下发:登录失败的平台在页面重开时不再自动重试,
        // 等用户更新凭证(saveAccount/importAccounts 会清缓存)后的下一次读取即重试
        if (check is CredentialCheck.Invalid) {
            profiles[p.name] = JSONObject().put("error", check.message).put("auth", true)
                .put("checkedAt", System.currentTimeMillis())
            throw CredentialExpiredException(check.message)
        }
        // Unknown 保留上游细节（卡片显示归纳文案,轻提示里可见真实原因;也供假 token 探测提取码值）
        if (check is CredentialCheck.Unknown) {
            val message = "暂时无法验证，请稍后重试（${check.message.take(160)}）"
            profiles[p.name] = JSONObject().put("error", message).put("auth", false)
                .put("checkedAt", System.currentTimeMillis())
            throw IllegalStateException(message)
        }
        val quota: QuotaInfo? = (check as? CredentialCheck.Valid)?.quota
        val result = JSONObject().put("nickname", name ?: account.optString("label"))
            .put("checkedAt", System.currentTimeMillis()).put("available", quota != null || name != null)
            .put("quota", quota?.let { JSONObject().put("used", it.used).put("total", it.total).put("trash", it.usedInTrash) } ?: JSONObject.NULL)
        require((revisions[p.name] ?: 0L) == revision) { "账号配置已更新，请刷新" }
        profiles[p.name] = result
        result
    }
    suspend fun cloudFiles(body: JSONObject): JSONObject {
        if (body.optString("action").isNotBlank()) return cloudAction(body)
        cloudSessions.entries.removeIf { System.currentTimeMillis() - it.value.created > 3600000 }
        val existing = body.optString("sessionId")
        val p = if (existing.isNotBlank()) cloudSessions[existing]?.platform ?: error("目录会话已失效，请重新打开网盘")
            else SharePlatform.valueOf(body.getString("platform"))
        val c = credentials.credential(p.name)
        require(c.isNotBlank()) { "请先添加网盘账号" }
        require(p != SharePlatform.GITHUB) { "请使用 GitHub 仓库入口" }
        // 根目录约定对齐桌面版：百度/139 用 "/"，迅雷用空串，蓝奏云用 "-1"，其余 "0"（收在 adapter 的 rootDir）
        val root = drives.forPlatform(p).rootDir
        val session = if (existing.isBlank()) CloudSession(p, revisions[p.name] ?: 0L).apply { dirs.add(root) }
            else cloudSessions[existing] ?: error("目录会话已失效")
        return session.mutex.withLock {
            require(session.revision == (revisions[p.name] ?: 0L)) { "账号配置已更新，请重新打开网盘" }
            val dir = if (existing.isBlank()) root else body.getString("directory")
            require(dir in session.dirs) { "请从已列出的文件夹进入" }
            val page = body.optInt("page", 1)
            require(page in 1..10000) { "页码无效" }
            // 各平台分页形态（页码/偏移/游标/全量）与凭证字段读取收在 adapter 的 listCloudDir
            val pageResult = drives.forPlatform(p).listCloudDir(dir, page, body.optString("cursor").ifBlank { null })
            val files: List<ShareFile> = pageResult.files
            val more = pageResult.hasMore
            val cursor = pageResult.cursor
            require(session.revision == (revisions[p.name] ?: 0L)) { "账号配置已更新，请重试" }
            if (page == 1) session.listed[dir] = mutableSetOf()
            files.forEach {
                session.files[it.fid] = it
                session.parents[it.fid] = dir
                session.listed.getOrPut(dir) { mutableSetOf() }.add(it.fid)
                if (it.isdir) session.dirs.add(CloudMutationPolicy.directoryId(p, it))
            }
            val id = existing.ifBlank { require(cloudSessions.size < 100) { "目录会话过多" }; UUID.randomUUID().toString() }
            cloudSessions[id] = session
            JSONObject().put("sessionId", id).put("platform", p.name).put("directory", dir).put("title", "首页")
                .put("files", JSONArray(files.map { fileJson(it).put("directoryId", CloudMutationPolicy.directoryId(p, it)) }))
                .put("directories", cloudDirectories(session))
                .put("hasMore", more).put("cursor", cursor)
                .put("limited", pageResult.limited)
        }
    }
    suspend fun cloudAction(body: JSONObject): JSONObject {
        val id = body.getString("sessionId")
        val session = cloudSessions[id] ?: error("目录会话已失效，请重新打开网盘")
        return session.mutex.withLock {
            require(session.revision == (revisions[session.platform.name] ?: 0L)) { "账号配置已更新，请重新打开网盘" }
            val listed = session.listed[body.getString("directory")].orEmpty()
            val operation = CloudMutationPolicy.validate(body, session.platform, session.files, session.parents, session.dirs, listed)
            val result = CloudMutationExecutor(drives::quark, drives::uc).execute(session.platform, credentials.get(session.platform.name), operation) {
                require(session.revision == (revisions[session.platform.name] ?: 0L)) { "账号配置已更新，操作停止" }
            }
            require(session.revision == (revisions[session.platform.name] ?: 0L)) { "账号已更新；操作结果请刷新确认" }
            cloudSessions.entries.removeIf { it.key != id && it.value.platform == session.platform }
            if (operation.action in setOf("move", "delete")) {
                val processed = result.getJSONArray("processedFids").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
                val removedDirs = operation.files.filter { it.fid in processed && it.isdir }
                    .map { CloudMutationPolicy.directoryId(session.platform, it) }.toMutableSet()
                do {
                    val previousSize = removedDirs.size
                    session.files.values.filter { it.isdir && session.parents[it.fid] in removedDirs }
                        .forEach { removedDirs.add(CloudMutationPolicy.directoryId(session.platform, it)) }
                } while (removedDirs.size != previousSize)
                session.dirs.removeAll(removedDirs)
                removedDirs.forEach { session.listed.remove(it) }
                session.files.values.filter { session.parents[it.fid] in removedDirs }.map { it.fid }.forEach {
                    session.files.remove(it); session.parents.remove(it)
                }
                operation.files.filter { it.fid in processed }.forEach {
                    session.files.remove(it.fid); session.parents.remove(it.fid)
                    if (it.isdir) session.dirs.remove(CloudMutationPolicy.directoryId(session.platform, it))
                }
            }
            result
        }
    }
    private fun cloudDirectories(session: CloudSession): JSONArray {
        val folders = session.files.values.filter { it.isdir }.associateBy { CloudMutationPolicy.directoryId(session.platform, it) }
        return JSONArray(session.dirs.map { id ->
            val components = mutableListOf<String>()
            var current: String? = id
            val visited = mutableSetOf<String>()
            while (current != null && visited.add(current)) {
                val folder = folders[current] ?: break
                components.add(0, folder.fname)
                current = session.parents[folder.fid]
            }
            JSONObject().put("id", id).put("name", if (components.isEmpty()) "根目录" else "/${components.joinToString("/")}")
        }.sortedBy { it.getString("name") })
    }
    suspend fun cloudDownload(body: JSONObject): JSONObject {
        val s = cloudSessions[body.getString("sessionId")] ?: error("目录会话已失效")
        return s.mutex.withLock {
            val c = credentials.credential(s.platform.name)
            require(s.revision == (revisions[s.platform.name] ?: 0L)) { "账号配置已更新，请重新打开网盘" }
            val f = s.files[body.getString("fid")] ?: error("请从已列出的文件中选择")
            require(!f.isdir) { "请进入文件夹选择文件" }
            val link = drives.forPlatform(s.platform).personalDownloadLink(f)
                ?: error("未取得下载地址，请检查账号权限")
            require(s.revision == (revisions[s.platform.name] ?: 0L)) { "账号配置已更新，请重新打开网盘" }
            JSONObject().put("id", downloads.enqueue(link.downloadUrl, f.fname, downloadHeaders(s.platform, link, credentials.credential(s.platform.name)),
                link.size, s.platform.name.lowercase(), threads = video.threadsOf(body)))
        }
    }

    private fun repo(p: SharePlatform): ShareResolveRepository {
        fun credential() = credentials.credential(p.name).takeIf { it.isNotBlank() }
        return when (p) {
            SharePlatform.QUARK -> QuarkResolveRepository(drives.quark())
            SharePlatform.UC -> UCResolveRepository(drives.uc())
            SharePlatform.BAIDU -> BaiduResolveRepository(BaiduApi())
            SharePlatform.C139 -> C139ResolveRepository(C139Api())
            SharePlatform.PAN123 -> Pan123ResolveRepository(Pan123Api()) { credential() }
            SharePlatform.PAN115 -> Pan115ResolveRepository(Pan115Api())
            SharePlatform.GUANGYA -> GuangYaResolveRepository(GuangYaApi()) { credential() }
            SharePlatform.ILANZOU -> {
                val api = ILanzouApi()
                ILanzouResolveRepository(api, ILanzouAccountRepository(db.ilanzouAccountDao(), api))
            }
            SharePlatform.LANZOU -> LanzouResolveRepository(LanzouApi())
            SharePlatform.XUNLEI -> {
                val api = XunleiApi()
                XunleiResolveRepository(
                    api, { credential() },
                    { credentials.get(p.name).optString("deviceId").takeIf { it.isNotBlank() } },
                    { credentials.get(p.name).optString("captchaToken") },
                    {
                        val account = credentials.get(p.name)
                        val fresh = api.refreshToken(account.optString("refreshToken"), account.optString("deviceId"), account.optString("authType"))
                        if (fresh != null) credentials.save(p.name, account.put("accessToken", fresh.first).put("refreshToken", fresh.second))
                        fresh
                    }
                )
            }
            SharePlatform.GITHUB -> error("请使用 GitHub 仓库入口")
        }
    }

    suspend fun resolve(body: JSONObject): JSONObject = sessionLock.withLock {
        val text = body.getString("link").trim()
        val parsed = ShareLinkParser.parse(text)
        if (parsed == null) {
            // 同一输入栏自动分流：不是网盘分享链接时，先试视频/图文链接，再落 GitHub/直连
            if (VideoLinkParser.parse(text) != null) {
                val result = video.parse(body).put("kind", "video")
                recordHistory(text, result.optString("title"), result.optString("platform"), pwd = "")
                return@withLock result
            }
            val github = githubResolve(text)
            // 标题回退：仓库/账号解析带 title；GitHub 直链只有 filename，退回链接本身
            recordHistory(text, github.optString("title").ifBlank { text }, "GITHUB", pwd = "")
            return@withLock github
        }
        sessions.entries.removeIf { System.currentTimeMillis() - it.value.created > 3_600_000 }
        require(sessions.size < 100) { "分享会话过多，请稍后再试" }
        val repository = repo(parsed.platform)
        val pwd = body.optString("password").ifBlank { parsed.pwd.orEmpty() }
        val share = repository.createSession(text, pwd, credentials.credential(parsed.platform.name)).getOrThrow()
        val root = when (parsed.platform) {
            SharePlatform.BAIDU, SharePlatform.GUANGYA, SharePlatform.ILANZOU, SharePlatform.LANZOU -> ""
            else -> "0"
        }
        val session = BrowserSession(parsed.platform, text, repository, share)
        session.dirs.add(root)
        val id = UUID.randomUUID().toString()
        val response = list(session, root).put("sessionId", id)
        sessions[id] = session
        recordHistory(text, share.title, parsed.platform.name, pwd)
        response
    }

    /** 解析成功即记入「最近解析」(同链接去重置顶,保留最近 100 条);网盘分享/视频/GitHub 三类共用 */
    private suspend fun recordHistory(url: String, title: String, platform: String, pwd: String) {
        val history = db.linkHistoryDao().observeAll().first()
        history.filter { it.url == url }.forEach { db.linkHistoryDao().delete(it.id) }
        db.linkHistoryDao().insert(LinkHistoryEntity(url = url, title = title, platform = platform, pwd = pwd))
        db.linkHistoryDao().observeAll().first().drop(100).forEach { db.linkHistoryDao().delete(it.id) }
    }

    private suspend fun list(s: BrowserSession, directory: String): JSONObject {
        require(directory in s.dirs) { "请从已列出的文件夹进入" }
        val files = s.repo.listFiles(s.share, directory, credentials.credential(s.platform.name)).getOrThrow()
        files.forEach { s.files[it.fid] = it; if (it.isdir) s.dirs.add(it.fid) }
        return JSONObject().put("title", s.share.title).put("platform", s.platform.name)
            .put("directory", directory).put("files", JSONArray(files.map(::fileJson)))
    }

    suspend fun list(body: JSONObject): JSONObject {
        if (githubBrowser.contains(body.getString("sessionId")))
            return githubBrowser.list(body.getString("sessionId"), body.getString("directory"))
        val s = sessions[body.getString("sessionId")] ?: error("分享会话已失效，请重新解析")
        return s.mutex.withLock { list(s, body.getString("directory")) }
    }

    suspend fun enqueue(body: JSONObject): JSONObject {
        val threads = video.threadsOf(body)
        if (githubBrowser.contains(body.getString("sessionId"))) {
            val file = githubBrowser.download(body.getString("sessionId"), body.getString("fid"))
            return JSONObject().put("id", downloads.enqueue(file.getString("url"), file.getString("name"),
                emptyMap(), file.optLong("size"), "github", shareUrl = file.optString("source"), threads = threads))
        }
        val s = sessions[body.getString("sessionId")] ?: error("分享会话已失效，请重新解析")
        return s.mutex.withLock {
            val file = s.files[body.getString("fid")] ?: error("文件未列出，请重新解析")
            require(!file.isdir) { "请进入文件夹选择文件" }
            val credential = credentials.credential(s.platform.name)
            val link = if (credential.isBlank() && s.platform in setOf(
                    SharePlatform.QUARK, SharePlatform.UC, SharePlatform.GUANGYA,
                    SharePlatform.ILANZOU, SharePlatform.LANZOU
                )) {
                s.repo.getGuestShareDownloadLink(s.share, file).getOrThrow()
            } else {
                val withoutSave = s.repo.getShareDownloadLinkWithoutSave(s.share, file, credential)
                if (withoutSave.isFailure && s.platform == SharePlatform.QUARK)
                    s.repo.getShareDownloadLink(s.share, file, credential).getOrThrow()
                else withoutSave.getOrThrow()
            }
            val latestCredential = credentials.credential(s.platform.name)
            val id = downloads.enqueue(
                link.downloadUrl, file.fname, downloadHeaders(s.platform, link, latestCredential),
                link.size, s.platform.name.lowercase(), s.source, threads = threads
            ) {
                link.cleanupDirFid?.let { s.repo.cleanupTempDir(it, latestCredential) }
            }
            JSONObject().put("id", id)
        }
    }

    suspend fun direct(body: JSONObject): JSONObject {
        val url = body.getString("url")
        require(url.length <= 8192) { "链接过长" }
        val threads = video.threadsOf(body)
        // P2P（磁力 / ed2k）：文件名交给引擎按元数据命名；引擎未就绪时 enqueue 按上游语义
        // 落失败任务并给出引导文案（设置页「下载引擎」可安装内核 / 切换引擎）。
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme == "magnet" || scheme == "ed2k") {
            return JSONObject().put("id", downloads.enqueue(url, "", threads = threads))
        }
        val uri = URI(url)
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null) { "请输入 HTTP/HTTPS 下载地址" }
        val name = body.optString("filename").ifBlank { uri.path.substringAfterLast('/').ifBlank { "download" } }
        return JSONObject().put("id", downloads.enqueue(url, name, threads = threads))
    }

    /** 视频解析（实验性）：识别 + 解析，直链留在服务器会话里 */
    suspend fun video(body: JSONObject): JSONObject = video.parse(body)

    /** 视频下载：按解析会话 + 媒体序号入队 */
    suspend fun videoDownload(body: JSONObject): JSONObject = video.download(body, downloads)

    suspend fun tasks(): JSONArray = JSONArray(downloads.tasks.first().map { t ->
        val stats = downloads.stats.value[t.id]
        JSONObject().put("id", t.id).put("filename", t.fileName).put("status", t.status)
            .put("downloaded", t.downloadedSize).put("total", t.totalSize)
            .put("speed", stats?.speed ?: 0).put("mergePercent", stats?.mergePercent ?: -1)
            .put("seeds", stats?.seeds ?: -1).put("peers", stats?.peers ?: -1)
            .put("fileDeleted", t.fileDeleted)
            .put("error", t.errorMsg).put("savePath", t.savePath)
            .put("shareUrl", t.shareUrl)
    })

    suspend fun taskAction(body: JSONObject) {
        val id = body.getLong("id")
        val task = db.downloadTaskDao().get(id) ?: error("任务不存在")
        when (body.getString("action")) {
            "pause" -> {
                require(task.status in listOf(0, 1)) { "只有运行中任务可以暂停" }
                downloads.pause(id)
            }
            "resume" -> {
                require(task.status in listOf(2, 4)) { "只有暂停或失败任务可以继续" }
                downloads.start(id)
            }
            "remove" -> downloads.remove(id, false)
            "delete-file" -> downloads.deleteCompletedFile(id)
            else -> error("未知任务操作")
        }
    }

    /** 已完成任务的落盘文件（网页「下载」按钮供浏览器拉取）；未完成/已删除/文件不存在返回 null */
    suspend fun completedTaskFile(id: Long): Pair<File, String>? {
        val task = db.downloadTaskDao().get(id) ?: return null
        if (task.status != DownloadTaskEntity.STATUS_COMPLETED || task.fileDeleted) return null
        val file = runCatching { File(task.savePath).canonicalFile }.getOrNull() ?: return null
        return if (file.isFile) file to task.fileName else null
    }

    private suspend fun githubResolve(text: String): JSONObject {
        return when (val target = GitHubLinkParser.parse(text)) {
            is GitHubLinkType.DirectFile -> JSONObject().put("directUrl", target.url).put("filename", target.fileName)
            is GitHubLinkType.Repository -> githubBrowser.resolve(target)
            is GitHubLinkType.Account -> {
                val repos = mutableListOf<GitHubRepo>()
                val organization = github.getUserType(target.owner) == "Organization"
                for (page in 1..100) {
                    val batch = (if (organization) github.getOrgRepos(target.owner, page) else github.getUserRepos(target.owner, page))
                        ?: error("无法读取 GitHub 账号仓库")
                    repos.addAll(batch)
                    if (batch.size < 100) break
                }
                JSONObject().put("title", target.owner).put("repositories", JSONArray(repos.map {
                    JSONObject().put("name", it.fullName).put("url", "https://github.com/${it.fullName}")
                }))
            }
            null -> error("未识别到分享链接")
        }
    }

    private fun fileJson(f: ShareFile) = JSONObject()
        .put("fid", f.fid).put("name", f.fname).put("size", f.fsize).put("directory", f.isdir).put("modified", f.modifyTime)
}
