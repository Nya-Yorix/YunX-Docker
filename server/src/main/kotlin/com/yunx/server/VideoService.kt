package com.yunx.server

import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.download.VideoDownloads
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.video.AcFunParser
import com.yunx.app.data.network.video.BilibiliParser
import com.yunx.app.data.network.video.DetectedVideoLink
import com.yunx.app.data.network.video.KuaishouParser
import com.yunx.app.data.network.video.PixivParser
import com.yunx.app.data.network.video.QualitySelectableParser
import com.yunx.app.data.network.video.VideoHttp
import com.yunx.app.data.network.video.VideoLinkParser
import com.yunx.app.data.network.video.VideoMediaKind
import com.yunx.app.data.network.video.VideoParseResult
import com.yunx.app.data.network.video.VideoParser
import com.yunx.app.data.network.video.VideoPlatform
import com.yunx.app.data.network.video.WeiboParser
import com.yunx.app.data.network.video.XhsParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务端视频解析（实验性）。
 *
 * 只收录纯 HTTP 解析器 —— 依赖隐藏 WebView（JCEF）的抖音解析器不编入 server，无法提供。
 * 因此这里维护自己的注册表（桌面端用的是 `VideoParsers`），平台 key 为 `VIDEO_<平台名>`，
 * 与网页账号页的平台标识一致；Cookie 存在 [Credentials] 里，不回显到页面。
 *
 * 解析结果暂存会话（直链与请求头不下发浏览器），下载时按会话 id + 媒体序号入队；
 * B 站 DASH 分轨默认「视频+音轨」一条任务，由 DownloadManager 负责合轨（DashMuxer）。
 */
class VideoService(private val credentials: Credentials) {

    /** 平台 key（凭证存储键）→ 解析器；key 顺序即解析时的查找顺序 */
    val platforms: Map<String, VideoParser> = linkedMapOf(
        "VIDEO_BILIBILI" to BilibiliParser,
        "VIDEO_KUAISHOU" to KuaishouParser,
        "VIDEO_WEIBO" to WeiboParser,
        "VIDEO_ACFUN" to AcFunParser,
        "VIDEO_XHS" to XhsParser,
        "VIDEO_PIXIV" to PixivParser,
    )

    /**
     * 账号页提供 Cookie 配置的平台。快手/微博/AcFun/小红书匿名即可解析（上游约定），
     * 不提供配置入口；哔哩哔哩登录后取高清，Pixiv 需登录态拿全量。
     * 解析时仍会读取历史保存过的 Cookie（如果有的话），只是不再展示配置入口。
     */
    val configurable: Map<String, VideoParser> = linkedMapOf(
        "VIDEO_BILIBILI" to BilibiliParser,
        "VIDEO_PIXIV" to PixivParser,
    )

    private data class VideoSession(
        val result: VideoParseResult,
        /** 预生成的落盘文件名（与 result.medias 下标一一对应） */
        val names: List<String>,
        val created: Long = System.currentTimeMillis()
    )

    private val sessions = ConcurrentHashMap<String, VideoSession>()

    /** 解析一条视频/图文链接；识别不出或平台不支持时抛错（页面提示） */
    suspend fun parse(body: JSONObject): JSONObject {
        val text = body.getString("link").trim()
        require(text.length <= 2048) { "链接过长" }
        require(sessions.size < 200) { "解析会话过多，请稍后再试" }
        sweep()
        val identified = VideoLinkParser.parse(text) ?: error("未识别出支持的视频链接")
        val link = expandShortLink(identified)
        val key = "VIDEO_" + link.platform.name
        val parser = platforms[key]
            ?: error("${link.platform.displayName} 的解析依赖内嵌浏览器，网页版暂不支持")
        val cookie = credentials.credential(key)
        val qn = body.optInt("qn", 0).takeIf { it > 0 }
        val parsed = if (qn != null && parser is QualitySelectableParser) {
            parser.parseWithQuality(link, cookie, qn)
        } else {
            parser.parse(link, cookie)
        }
        // 与桌面端 VideoParseRepository 一致：所有直链归一化成 https（小红书等平台会返回 http 明文链）
        val result = parsed.copy(
            medias = parsed.medias.map { it.copy(url = VideoHttp.secureUrl(it.url), audioUrl = VideoHttp.secureUrl(it.audioUrl)) }
        )
        val names = result.medias.mapIndexed { index, media -> VideoDownloads.fileNameFor(result, media, index, result.medias.size) }
        val id = UUID.randomUUID().toString()
        sessions[id] = VideoSession(result, names)
        val medias = JSONArray()
        result.medias.forEachIndexed { index, media ->
            medias.put(
                JSONObject()
                    .put("index", index)
                    .put("name", names[index])
                    .put("kind", when (media.kind) {
                        VideoMediaKind.VIDEO -> "video"
                        VideoMediaKind.IMAGE -> "image"
                        VideoMediaKind.AUDIO -> "audio"
                    })
                    .put("quality", media.quality)
                    .put("size", if (media.size > 0) media.size else JSONObject.NULL)
                    .put("dash", media.audioUrl.isNotBlank())
            )
        }
        return JSONObject()
            .put("id", id)
            .put("platform", key)
            .put("title", result.title)
            .put("author", result.author)
            .put("notice", result.notice)
            .put("medias", medias)
    }

    /** 按会话 + 媒体序号入队下载；DASH 分轨一条任务（音轨随任务落库由 DownloadManager 合轨） */
    suspend fun download(body: JSONObject, downloads: DownloadManager): JSONObject {
        val session = sessions[body.getString("id")] ?: error("解析会话已失效，请重新解析")
        val raw = body.optJSONArray("indexes") ?: error("请选择要下载的媒体")
        require(raw.length() in 1..50) { "一次最多下载 50 个媒体" }
        val threads = threadsOf(body)
        val indexes = (0 until raw.length()).map { raw.getInt(it) }
        require(indexes.all { it in session.result.medias.indices }) { "媒体序号无效，请重新解析" }
        require(indexes.toSet().size == indexes.size) { "媒体序号重复" }
        val platform = DownloadPlatform.forVideoPlatform(session.result.platform)
        val ids = mutableListOf<Long>()
        for (index in indexes) {
            val media = session.result.medias[index]
            if (media.url.isBlank()) continue
            ids.add(
                downloads.enqueue(
                    url = media.url,
                    fileName = session.names[index],
                    headers = media.headers,
                    size = media.size,
                    platform = platform,
                    mergeAudioUrl = media.audioUrl,
                    mergeAudioHeaders = media.audioHeaders,
                    motionUrl = media.motionUrl,
                    threads = threads
                )
            )
        }
        require(ids.isNotEmpty()) { "所选媒体没有可下载的地址" }
        return JSONObject().put("ids", JSONArray(ids)).put("queued", ids.size)
    }

    /** 账号页「刷新昵称」：用解析器自己的校验逻辑问一次平台；凭证无效抛错 */
    suspend fun accountInfo(key: String): JSONObject {
        val parser = configurable.getValue(key)
        val cookie = credentials.credential(key)
        require(cookie.isNotBlank()) { "请先添加账号" }
        val nickname = parser.validateCookie(cookie) ?: error("Cookie 无效或已过期")
        return JSONObject()
            .put("nickname", nickname.ifBlank { parser.platform.displayName + " 账号" })
            .put("checkedAt", System.currentTimeMillis())
            .put("available", true)
            .put("quota", JSONObject.NULL)
    }

    /** 保存/清除视频平台 Cookie（credentials 为空对象 = 移除账号，与网盘账号同一套语义） */
    fun saveCookie(key: String, input: JSONObject) {
        require(platforms.containsKey(key)) { "未知平台" }
        // 免登录平台不接受新的 Cookie 配置；但保留清除入口（历史保存过的凭证可以移除）
        require(input.length() == 0 || configurable.containsKey(key)) { "该平台免登录，无需配置凭证" }
        require(input.keySet().all { it == "cookie" }) { "视频平台仅支持 Cookie" }
        require(input.keySet().all { input.get(it) is String && input.getString(it).length <= 65536 }) { "凭证格式错误" }
        val value = input.optString("cookie").trim()
        require(input.length() == 0 || value.isNotEmpty()) { "Cookie 不能为空" }
        credentials.save(key, if (value.isEmpty()) JSONObject() else JSONObject().put("cookie", value))
    }

    /** 任务级线程数覆盖：0 = 跟随全局设置（与网盘下载同语义） */
    fun threadsOf(body: JSONObject): Int {
        val threads = if (body.has("threads")) body.getInt("threads") else 0
        require(threads in 0..128) { "线程数超出范围" }
        return threads
    }

    /** 短链展开（b23.tv / xhslink.com 等）：跟随重定向后重新识别，最多 5 跳 */
    private suspend fun expandShortLink(link: DetectedVideoLink): DetectedVideoLink {
        var current = link
        repeat(5) {
            if (!current.isShortLink) return current
            val userAgent = if (current.platform == VideoPlatform.KUAISHOU) VideoHttp.UA_IPHONE else VideoHttp.UA_CHROME
            val next = VideoHttp.expandRedirect(current.url, mapOf("User-Agent" to userAgent), { HttpClients.apiClient() })
                ?: return current
            val reidentified = VideoLinkParser.parse(next) ?: return current.copy(url = next)
            current = reidentified
        }
        return current
    }

    /** 会话有效期 2 小时；新解析时顺带清理过期会话 */
    private fun sweep() {
        val cutoff = System.currentTimeMillis() - 2 * 60 * 60 * 1000
        sessions.entries.removeIf { it.value.created < cutoff }
    }
}
