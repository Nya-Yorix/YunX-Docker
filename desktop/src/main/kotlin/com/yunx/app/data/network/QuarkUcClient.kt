package com.yunx.app.data.network

import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.QuotaInfo
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import com.yunx.app.data.network.model.ShareToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * __puus/__pus 会话 Cookie 工具（夸克/UC 共用，对应 AList pkg/cookie + quark_uc requestWithCookie）。
 * __puus 约 3 小时过期，是下载直链签名校验的关键字段（AlistGo/alist#830）。
 *
 * （原 QuarkCookieUtil / UCCookieUtil 为两份逐字节相同的实现，收敛于此。）
 */
object PuusCookieUtil {
    private val TRACKED = setOf("__puus", "__pus")

    /** 把响应 Set-Cookie 列表里的最新 __puus/__pus 合并回原 Cookie 串 */
    fun mergeFromSetCookies(original: String, setCookies: List<String>): String {
        var cookie = original
        for (sc in setCookies) {
            val kv = sc.substringBefore(';').trim()
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            val name = kv.substring(0, eq)
            if (name in TRACKED) cookie = setOrReplace(cookie, name, kv.substring(eq + 1))
        }
        return cookie
    }

    /** 去掉 __puus，用于触发服务端重新下发（AList refreshPuus） */
    fun withoutPuus(cookie: String): String =
        cookie.split(";").map { it.trim() }
            .filter { !it.startsWith("__puus=") }
            .joinToString("; ")

    private fun setOrReplace(cookie: String, name: String, value: String): String {
        val parts = cookie.split(";").map { it.trim() }.toMutableList()
        val idx = parts.indexOfFirst { it.startsWith("$name=") }
        val kv = "$name=$value"
        if (idx >= 0) parts[idx] = kv else parts.add(kv)
        return parts.joinToString("; ")
    }
}

/**
 * 夸克/UC 共用的端点与 UA 配置。字段名对齐 QuarkConstants / UCConstants 同名常量，
 * 由 [QuarkEndpoints] / [UCEndpoints] 绑定 —— 加字段时先在对应 Constants 里补常量。
 */
interface QuarkUcEndpoints {
    /** 登录态通用请求 UA（夸克=API_USER_AGENT，UC=USER_AGENT） */
    val apiUserAgent: String

    /** 云盘文件/容量请求 UA（夸克同上，UC=CLOUD_UA） */
    val cloudUserAgent: String

    /** 游客（未登录）取链 UA（夸克同上，UC=GUEST_UA：游客链路风控更严） */
    val guestUserAgent: String

    /** 游客取链附加请求头（UC 需 Sec-Ch-Ua） */
    val guestHeaders: Map<String, String>

    /** Web 基准域名（Origin 与 Referer 用；pan.quark.cn / drive.uc.cn） */
    val webOrigin: String

    /** 下载直链防盗链 Referer */
    val downloadReferer: String

    val accountInfoUrl: String
    val shareTokenUrl: String
    val fileUrl: String
    val saveUrl: String
    val taskUrl: String
    val deleteUrl: String
    val renameUrl: String
    val moveUrl: String
    val shareCreateUrl: String
    val shareInfoUrl: String
    val downloadUrl: String
    val configUrl: String

    /** 容量接口（/1/clouddrive/member，原内联 URL 上提） */
    val quotaUrl: String

    /** 容量接口附加请求头（UC 带 Origin/Referer，夸克不带） */
    val quotaHeaders: Map<String, String>

    /** quotaCheck 判定「登录态失效」的服务端错误码（UC 另有 14001/41020 令牌失效） */
    val invalidCredentialCodes: Set<Int>
}

/** 夸克端点绑定（[QuarkConstants]） */
object QuarkEndpoints : QuarkUcEndpoints {
    override val apiUserAgent = QuarkConstants.API_USER_AGENT
    override val cloudUserAgent = QuarkConstants.API_USER_AGENT
    override val guestUserAgent = QuarkConstants.API_USER_AGENT
    override val guestHeaders = emptyMap<String, String>()
    override val webOrigin = "https://pan.quark.cn"
    override val downloadReferer = QuarkConstants.DOWNLOAD_REFERER
    override val accountInfoUrl = QuarkConstants.ACCOUNT_INFO_URL
    override val shareTokenUrl = QuarkConstants.SHARE_TOKEN_URL
    override val fileUrl = QuarkConstants.FILE_URL
    override val saveUrl = QuarkConstants.SAVE_URL
    override val taskUrl = QuarkConstants.TASK_URL
    override val deleteUrl = QuarkConstants.DELETE_URL
    override val renameUrl = QuarkConstants.RENAME_URL
    override val moveUrl = QuarkConstants.MOVE_URL
    override val shareCreateUrl = QuarkConstants.SHARE_CREATE_URL
    override val shareInfoUrl = QuarkConstants.SHARE_INFO_URL
    override val downloadUrl = QuarkConstants.DOWNLOAD_URL
    override val configUrl = QuarkConstants.CONFIG_URL
    override val quotaUrl = "${QuarkConstants.API_BASE}/1/clouddrive/member?pr=ucpro&fr=pc&fetch_subscribe=true&_ch=home"
    override val quotaHeaders = emptyMap<String, String>()
    override val invalidCredentialCodes = setOf(31001)
}

/** UC 端点绑定（[UCConstants]） */
object UCEndpoints : QuarkUcEndpoints {
    override val apiUserAgent = UCConstants.USER_AGENT
    override val cloudUserAgent = UCConstants.CLOUD_UA
    override val guestUserAgent = UCConstants.GUEST_UA
    override val guestHeaders = mapOf("Sec-Ch-Ua" to UCConstants.GUEST_SEC_CH_UA)
    override val webOrigin = UCConstants.WEB_ORIGIN
    override val downloadReferer = UCConstants.DOWNLOAD_REFERER
    override val accountInfoUrl = UCConstants.ACCOUNT_INFO_URL
    override val shareTokenUrl = UCConstants.SHARE_TOKEN_URL
    override val fileUrl = UCConstants.FILE_URL
    override val saveUrl = UCConstants.SAVE_URL
    override val taskUrl = UCConstants.TASK_URL
    override val deleteUrl = UCConstants.DELETE_URL
    override val renameUrl = UCConstants.RENAME_URL
    override val moveUrl = UCConstants.MOVE_URL
    override val shareCreateUrl = UCConstants.SHARE_CREATE_URL
    override val shareInfoUrl = UCConstants.SHARE_INFO_URL
    override val downloadUrl = UCConstants.DOWNLOAD_URL
    override val configUrl = UCConstants.CONFIG_URL
    override val quotaUrl = "${UCConstants.API_BASE}/1/clouddrive/member?pr=UCBrowser&fr=pc&fetch_subscribe=true&_ch=home"
    override val quotaHeaders = mapOf(
        "Origin" to UCConstants.WEB_ORIGIN,
        "Referer" to UCConstants.DOWNLOAD_REFERER
    )
    override val invalidCredentialCodes = setOf(31001, 14001, 41020)
}

/**
 * 夸克 / UC 共用 API 客户端（deep module）。
 *
 * 两家网盘 API 同构（AList 同用一个 quark_uc 适配器）：请求骨架、信封拆包（status/message/data）、
 * __puus/__pus 会话 Cookie 合并回写（cookieSink）、游客态 __pugs 捕获（guestPugs）、
 * 异步任务轮询、整目录翻页合并策略全部一致；差异只有端点/UA/失效码与个别 body 字段 ——
 * 以 [QuarkUcEndpoints] + 少量 open 钩子表达。
 *
 * 平台类（[QuarkApi] / [UCApi]）继承本类：共享方法签名原样对外（调用方无感知），
 * 平台特有流程（UC 的 v2/detail 分享列表、转码播放、video_preview；夸克的免转存取链等）留在子类。
 * 全部请求经 [PuusCookieUtil] 维持会话新鲜，异常统一为 [PanApiException]。
 */
abstract class QuarkUcClient(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    protected abstract val endpoints: QuarkUcEndpoints

    /** 错误文案里的平台名（「夸克」/「UC」） */
    protected abstract val platformLabel: String

    /** 云盘文件列表完整 URL（两家查询参数不同：夸克多 uc_param_str/fetch_all_file 等，UC 的 _sort 为 URL 编码） */
    protected abstract fun cloudFilesUrl(pdirFid: String, page: Int, size: Int): String

    /**
     * 登录态取链（getDownloadLink）是否把响应 Set-Cookie 合并回写 cookieSink。
     * 夸克现状 true（直链签名绑定取链时刻 Cookie）；UC 现状 false —— 先按原行为收敛，
     * 是否对齐夸克另行实测验证，不在本次结构重构里改行为。
     */
    protected open val sinkCookiesOnDownloadLink: Boolean get() = true

    /** 分享 token 请求体的平台附加字段（夸克 support_visit_limit_private_share / UC share_for_transfer） */
    protected open fun applyShareTokenExtra(body: JSONObject) {}

    /** 游客取链请求体的平台附加字段（夸克补 speedup_session/token 空占位） */
    protected open fun applyGuestBodyExtra(body: JSONObject) {}

    /** 创建分享请求体的平台附加字段（夸克 support_error_code / UC public_search） */
    protected open fun applyCreateShareExtra(body: JSONObject, passcode: String) {}

    /** 游客取链的服务端错误码 → 中文提示（平台码表不同，见各子类） */
    protected abstract fun guestErrorMessage(code: Int, message: String): String

    /** 每次请求动态获取全局客户端（忽略 SSL 开关切换即时生效） */
    protected val client: OkHttpClient get() = clientProvider()

    /**
     * Cookie 回写接收器（推荐由 QuarkAccountRepository / UCAccountRepository 注入并落库）：
     * 每次响应把 Set-Cookie 合并后的最新 Cookie 回调，保持 __puus/__pus 始终新鲜。
     *
     * ★ 回调同时带上「本次请求用的那份 Cookie」：多账号下载（实验性）会用**别的账号**取链，
     *   只有按请求 Cookie 找到对应那一行回写，才不会把别的账号的 __puus 混进主账号那一行。
     */
    var cookieSink: ((merged: String, requestCookie: String) -> Unit)? = null

    /**
     * 游客态令牌 `__pugs`（未登录访问分享时由响应 Set-Cookie 下发，约 3 小时有效）。
     * 游客直链（dl-guest-* / OSS）必须回带该令牌，缺了 CDN 直接 412/403；进程内复用，
     * 登录态路径完全不受影响（该字段只在 [getGuestShareDownloadLink] 里读写）。
     */
    @Volatile
    var guestPugs: String = ""
        private set

    protected val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ---------- 账号 ----------

    suspend fun fetchNickname(cookie: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoints.accountInfoUrl)
            .header("Cookie", cookie)
            .header("User-Agent", endpoints.apiUserAgent)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)
                // 该接口无 status 字段，成功标志为 success:true / code:"OK"
                if (json.optBoolean("success", false)) {
                    json.optJSONObject("data")
                        ?.optString("nickname")
                        ?.takeIf { it.isNotBlank() }
                } else null
            }
        }.getOrNull()
    }

    // ---------- 分享解析 ----------

    /** 获取分享 Token（请求体携带 pwd_id/passcode；平台差异走 [applyShareTokenExtra]） */
    suspend fun getShareToken(shareId: String, pwd: String?, cookie: String): ShareToken? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("pwd_id", shareId)
            .put("passcode", pwd ?: "")
        applyShareTokenExtra(body)
        val request = postJson(endpoints.shareTokenUrl, cookie, body.toString())
        parseData(request) { data ->
            ShareToken(
                stoken = data.optString("stoken"),
                title = data.optString("title"),
                firstFid = data.optString("first_fid")
            )
        }
    }

    // ---------- 个人网盘 / 转存 ----------

    /** 个人网盘文件列表（用于查找/确认临时目录）。
     *  注意：个人网盘列表字段为 file_name / size / dir(boolean)，
     *  与分享列表的 fname / fsize / isdir(int) 不同，需做兼容映射。
     */
    suspend fun getFileList(
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 100
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val url = "${endpoints.fileUrl}&pdir_fid=$pdirFid&page=$page&size=$size"
        val request = get(url, cookie)
        parseData(request) { data ->
            val array = data.optJSONArray("list") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        ShareFile(
                            fid = item.optString("fid"),
                            fname = item.optString("file_name").ifEmpty { item.optString("fname") },
                            fsize = if (item.has("size")) item.optLong("size") else item.optLong("fsize"),
                            isdir = item.optBoolean("dir", false) || item.optInt("isdir") == 1,
                            pdirFid = item.optString("pdir_fid"),
                            fidToken = item.optString("fid_token"),
                            modifyTime = item.optString("modify_time")
                        )
                    )
                }
            }
        }
    }

    /** 云盘文件列表（网盘页浏览；抓包 /1/clouddrive/file/sort，pdir_fid=0 根目录）
     *  响应 data.list[]，字段：fid / file_name / size / dir(boolean) / pdir_fid / updated_at。
     */
    suspend fun listCloudFiles(
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 50
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(cloudFilesUrl(pdirFid, page, size))
            .header("Cookie", cookie)
            .header("User-Agent", endpoints.cloudUserAgent)
            .header("Origin", endpoints.webOrigin)
            .header("Referer", "${endpoints.webOrigin}/")
            .get()
            .build()
        parseData(request) { data ->
            val array = data.optJSONArray("list") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        ShareFile(
                            fid = item.optString("fid"),
                            fname = item.optString("file_name").ifEmpty { item.optString("fname") },
                            fsize = item.optLong("size"),
                            isdir = item.optBoolean("dir", false),
                            pdirFid = item.optString("pdir_fid"),
                            fidToken = "",
                            modifyTime = item.optString("updated_at")
                        )
                    )
                }
            }
        }
    }

    /**
     * 拉取**整个目录**的云盘文件列表（自动翻页）。
     *
     * 服务端单页最多返回 50 项（`_size` 传更大也会被截断），因此「只打一次 listCloudFiles」
     * 的调用方在大目录里只能看到/下载前 50 项。这里按页循环直到取完，并做两重保险：
     * - 按 fid 去重：服务端若忽略页码重复返回同一页，不会把列表撑大；
     * - 整页无新增项即停止：翻到尽头（或接口异常返回重复数据）时不会死循环。
     */
    suspend fun listAllCloudFiles(
        pdirFid: String,
        cookie: String,
        /** 单页条数；云盘接口实测上限 50 */
        pageSize: Int = 50,
        /** 安全上限：防止异常响应导致无限翻页（200 页 × 50 = 1 万项） */
        maxPages: Int = 200
    ): List<ShareFile>? {
        val first = listCloudFiles(pdirFid, cookie, page = 1, size = pageSize) ?: return null
        val merged = LinkedHashMap<String, ShareFile>()
        fun merge(batch: List<ShareFile>): Int {
            var added = 0
            for (file in batch) {
                // fid 理论上非空；异常数据退化为「名字 + 父目录」做键，避免被误去重
                val key = file.fid.ifBlank { "${file.pdirFid}/${file.fname}" }
                if (merged.putIfAbsent(key, file) == null) added++
            }
            return added
        }
        merge(first)
        var page = 2
        while (first.size >= pageSize && page <= maxPages) {
            val batch = listCloudFiles(pdirFid, cookie, page = page, size = pageSize) ?: break
            if (batch.isEmpty()) break
            if (merge(batch) == 0) break
            page++
        }
        return merged.values.toList()
    }

    /** 创建目录（个人网盘），返回新目录 fid */
    suspend fun createFolder(name: String, parentFid: String, cookie: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("pdir_fid", parentFid)
                .put("file_name", name)
                .put("dir_path", "")
                .put("dir_init_lock", false)
                .toString()
            val request = postJson(endpoints.fileUrl, cookie, body)
            parseData(request) { data -> data.optString("fid") }
        }

    /** 转存分享文件到个人网盘目录，返回异步任务 id（可能为空）
     *  注意：pwd_id 必须为分享链接短码（非空），并携带 pdir_fid/scene，
     *  否则接口返回 400 Bad Parameter: [pwd_id为空]。
     */
    suspend fun saveShareFile(
        shareId: String,
        stoken: String,
        pdirFid: String,
        fid: String,
        fidToken: String,
        toPdirFid: String,
        cookie: String
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("pwd_id", shareId)
            .put("stoken", stoken)
            .put("pdir_fid", pdirFid)
            .put("to_pdir_fid", toPdirFid)
            .put("fid_list", JSONArray().put(fid))
            .put("fid_token_list", JSONArray().put(fidToken))
            .put("scene", "link")
            .toString()
        val request = postJson(endpoints.saveUrl, cookie, body)
        parseData(request) { data -> data.optString("task_id").takeIf { it.isNotBlank() } }
    }

    /** 轮询异步转存任务，直到完成或超时（最多 10 次 × 1s）
     *  官方轮询响应：data.status == 2（完成）且带 finished_at；
     *  转存后的新 fid 在 data.save_as.save_as_top_fids[0]（download 必须用它）。
     *  @return 转存后的新 fid；null 表示超时/失败。
     */
    suspend fun pollTask(taskId: String, cookie: String): String? = withContext(Dispatchers.IO) {
        val url = "${endpoints.taskUrl}&task_id=${URLEncoder.encode(taskId, "UTF-8")}&retry_index=0"
        for (i in 0 until 10) {
            val savedFid = runCatching {
                client.newCall(get(url, cookie)).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optInt("status") != 200) return@use null
                    val data = json.optJSONObject("data") ?: return@use null
                    // 完成：finished_at > 0 或 status/task_status == 2
                    val finished = data.optLong("finished_at") > 0 ||
                        data.optInt("status") == 2 ||
                        data.optInt("task_status") == 2
                    if (!finished) return@use null
                    data.optJSONObject("save_as")
                        ?.optJSONArray("save_as_top_fids")
                        ?.optString(0)
                        ?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            if (savedFid != null) return@withContext savedFid
            delay(1000)
        }
        null
    }

    // ---------- 网盘空间详情 ----------

    /** 容量请求（/1/clouddrive/member）：UA 与附加头按平台配置（UC 带 Origin/Referer） */
    private fun quotaRequest(cookie: String): Request =
        Request.Builder()
            .url(endpoints.quotaUrl)
            .header("Cookie", cookie)
            .header("User-Agent", endpoints.cloudUserAgent)
            .apply { endpoints.quotaHeaders.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()

    /** 网盘空间详情（/1/clouddrive/member：total_capacity / use_capacity） */
    suspend fun getQuota(cookie: String): QuotaInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.newCall(quotaRequest(cookie)).execute()
            val body = response.use { it.body?.string() ?: return@runCatching null }
            val data = JSONObject(body).optJSONObject("data") ?: return@runCatching null
            QuotaInfo(
                used = data.optLong("use_capacity"),
                total = data.optLong("total_capacity")
            )
        }.getOrNull()
    }

    /**
     * 校验型容量查询（accountInfo 用）：不吞错，区分「登录态失效」与「暂时性失败」。
     * member 响应 {status, code, message, data}——失效码表见 [QuarkUcEndpoints.invalidCredentialCodes]
     * （夸克 code=31001 为登录态失效（假 token 实测）；UC 另有 14001/41020 令牌失效）。
     */
    suspend fun quotaCheck(cookie: String): CredentialCheck = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(quotaRequest(cookie)).execute()
            val body = response.use { it.body?.string().orEmpty() }
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return@withContext CredentialCheck.Unknown("$platformLabel 响应格式异常")
            val status = json.optInt("status", -1)
            val code = json.optInt("code", 0)
            val message = json.optString("message")
            if (status != 200) {
                return@withContext if (code in endpoints.invalidCredentialCodes || message.contains("登录"))
                    CredentialCheck.Invalid("$platformLabel 登录态已失效（code=$code）")
                else CredentialCheck.Unknown("$platformLabel 接口返回 status=$status code=$code $message")
            }
            val data = json.optJSONObject("data")
                ?: return@withContext CredentialCheck.Unknown("$platformLabel 响应缺少数据")
            CredentialCheck.Valid(null, QuotaInfo(data.optLong("use_capacity"), data.optLong("total_capacity")))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CredentialClassifier.classify(e)
        }
    }

    // ---------- 下载直链 ----------

    /**
     * 刷新会话 Cookie（对应 AList quark_uc refreshPuus，修复 AlistGo/alist#830）：
     * 剥离 __puus 后请求任意接口（/config），服务端会在 Set-Cookie 中重新下发 __puus/__pus。
     * @return 合并了最新 __puus/__pus 的 Cookie；失败返回 null（调用方应回退原 Cookie）。
     */
    suspend fun refreshSession(cookie: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoints.configUrl)
            .header("Cookie", PuusCookieUtil.withoutPuus(cookie))
            .header("User-Agent", endpoints.apiUserAgent)
            .header("Referer", endpoints.downloadReferer)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val merged = PuusCookieUtil.mergeFromSetCookies(cookie, resp.headers("Set-Cookie"))
                if (merged != cookie) cookieSink?.invoke(merged, cookie)
                merged
            }
        }.getOrNull()
    }

    /** 获取下载直链（个人网盘文件） */
    suspend fun getDownloadLink(fid: String, cookie: String): DownloadLink? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("fids", JSONArray().put(fid)).toString()
        val request = postJson(endpoints.downloadUrl, cookie, body)
        val response = client.newCall(request).execute()
        val bodyStr = response.use {
            if (sinkCookiesOnDownloadLink) mergeCookieFromResponse(request, it)
            it.body?.string() ?: throw PanApiException("获取下载链接失败：响应为空")
        }
        val json = runCatching { JSONObject(bodyStr) }.getOrElse {
            throw PanApiException("响应解析失败")
        }
        if (json.optInt("status") != 200 && json.optInt("code") != 0) {
            // 失败响应无 status 字段（默认0），用 code 识别（如 21001 file not found）
            throw PanApiException(
                json.optString("message").ifBlank { "获取下载链接失败" },
                json.optInt("code")
            )
        }
        parseDownloadLinkArray(json)
    }

    /**
     * 游客分享取链（未登录直链，夸克实测仅约 50MB 以内的小文件可用；UC 不做大小限制）。
     *
     * 与登录态取链打的是同一个 download 端点，差别只在请求语义：body 带上分享参数
     * （pwd_id / stoken / fids_token），不需要任何登录 Cookie（平台附加字段走 [applyGuestBodyExtra]）。
     *
     * 响应 Set-Cookie 会下发游客态 `__pugs`：它就是后续下载直链要带的 Cookie
     * （夸克缺失则 CDN 412；UC 缺它 OSS 直接 403 RequestDeniedByCallback，且与本次直链同响应绑定），
     * 这里捕获后写进 [DownloadLink.guestCookie]，同时缓存到 [guestPugs] 供同批次的后续文件复用。
     *
     * @throws PanApiException 频控/需登录（31001）、超出游客大小上限（23018）、令牌失效等
     */
    suspend fun getGuestShareDownloadLink(
        fid: String,
        fidToken: String,
        shareId: String,
        stoken: String
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("fids", JSONArray().put(fid))
            .put("pwd_id", shareId)
            .put("stoken", stoken)
            .put("fids_token", JSONArray().put(fidToken))
        applyGuestBodyExtra(body)
        val request = postJsonGuest(endpoints.downloadUrl, guestPugs, body.toString())
        val response = client.newCall(request).execute()
        // 先取本次响应的 __pugs（与本次直链同响应绑定，不能用别的响应/上一次的值）
        val responsePugs = pugsFromSetCookies(response.headers("Set-Cookie"))
        val bodyStr = response.use {
            it.body?.string() ?: throw PanApiException("获取下载链接失败：响应为空")
        }
        if (responsePugs != null) guestPugs = responsePugs
        val json = runCatching { JSONObject(bodyStr) }.getOrElse {
            // 非 JSON（风控页/HTML 错误页）时带上 HTTP 状态码，便于分辨 401/403 与分享失效
            throw PanApiException("响应解析失败（HTTP ${response.code}）")
        }
        val code = json.optInt("code")
        if (json.optInt("status") != 200 && code != 0) {
            throw PanApiException(guestErrorMessage(code, json.optString("message")), code)
        }
        parseDownloadLinkArray(json, guestCookie = responsePugs ?: guestPugs)
    }

    /** 从响应 Set-Cookie 里取出游客态令牌，返回可直接当 Cookie 头用的 `__pugs=值`；没有则 null */
    private fun pugsFromSetCookies(setCookies: List<String>): String? =
        setCookies.asSequence()
            .map { it.substringBefore(';').trim() }
            .firstOrNull { it.startsWith("__pugs=") && it.length > "__pugs=".length }

    /** 游客请求构造：Cookie 为空时干脆不发该头（首次取链手上没有任何令牌） */
    protected fun postJsonGuest(url: String, cookie: String, body: String): Request =
        Request.Builder()
            .url(url)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .header("User-Agent", endpoints.guestUserAgent)
            .apply { endpoints.guestHeaders.forEach { (k, v) -> header(k, v) } }
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()

    /** 下载直链响应统一拆包：data 数组取首项 → DownloadLink */
    protected fun parseDownloadLinkArray(json: JSONObject, guestCookie: String = ""): DownloadLink {
        val array = json.optJSONArray("data") ?: throw PanApiException("响应缺少 data")
        if (array.length() == 0) throw PanApiException("未返回下载链接")
        val item = array.optJSONObject(0) ?: throw PanApiException("未返回下载链接")
        return DownloadLink(
            fid = item.optString("fid"),
            filename = item.optString("file_name").ifEmpty { item.optString("filename") },
            downloadUrl = item.optString("download_url"),
            size = item.optLong("size"),
            guestCookie = guestCookie
        )
    }

    // ---------- 云盘文件管理 ----------

    /** 删除文件（取链成功后清理临时转存；对齐抓包：action_type=2 + filelist + exclude_fids）
     *  返回异步 task_id（删除为异步任务，无需轮询；失败返回 null）。
     */
    suspend fun deleteFile(fid: String, cookie: String): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action_type", 2)
            .put("filelist", JSONArray().put(fid))
            .put("exclude_fids", JSONArray())
            .toString()
        val request = postJson(endpoints.deleteUrl, cookie, body)
        parseData(request) { data -> data.optString("task_id").takeIf { it.isNotBlank() } }
    }

    /** 重命名文件（云盘功能抓包：POST file/rename） */
    suspend fun renameFile(fid: String, newName: String, cookie: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("fid", fid)
                .put("file_name", newName)
                .toString()
            val request = postJson(endpoints.renameUrl, cookie, body)
            runCatching {
                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    json.optInt("status") == 200
                }
            }.getOrDefault(false)
        }

    /** 移动文件（云盘功能抓包：action_type=1 + to_pdir_fid + filelist）；返回 task_id */
    suspend fun moveFile(fid: String, toPdirFid: String, cookie: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("action_type", 1)
                .put("to_pdir_fid", toPdirFid)
                .put("filelist", JSONArray().put(fid))
                .put("exclude_fids", JSONArray())
                .toString()
            val request = postJson(endpoints.moveUrl, cookie, body)
            parseData(request) { data -> data.optString("task_id").takeIf { it.isNotBlank() } }
        }

    /**
     * 创建分享（云盘功能抓包：POST /1/clouddrive/share）。
     * 注意：分享创建是**异步任务**——响应只有 data.task_id，必须轮询 /1/clouddrive/task 直到完成拿到 share_id。
     * @param urlType 1=链接无提取码 2=链接+提取码
     * @param expiredType 1=永久 2=一天 3=七天 4=三十天
     * @return 分享 share_id
     */
    suspend fun createShare(
        fidList: List<String>,
        title: String,
        urlType: Int,
        passcode: String,
        expiredType: Int,
        cookie: String
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("fid_list", JSONArray().apply { fidList.forEach { put(it) } })
            .put("title", title.ifBlank { "分享文件" })
            .put("url_type", urlType)
            .put("expired_type", expiredType)
            .apply { if (passcode.isNotBlank()) put("passcode", passcode) }
        applyCreateShareExtra(body, passcode)
        val request = postJson(endpoints.shareCreateUrl, cookie, body.toString())
        // 1) 创建分享 → task_id（异步，须轮询等待完成）
        val taskId = parseData(request) { data ->
            data.optString("task_id").takeIf { it.isNotBlank() }
        } ?: return@withContext null
        // 2) 轮询 task 直到完成，取 share_id（官方响应 status=2 + share_id）
        pollShareTask(taskId, cookie)
    }

    /** 轮询分享创建任务（GET /1/clouddrive/task），返回 share_id；超时返回 null */
    private suspend fun pollShareTask(taskId: String, cookie: String): String? =
        withContext(Dispatchers.IO) {
            val url = "${endpoints.taskUrl}&task_id=${URLEncoder.encode(taskId, "UTF-8")}&retry_index=0"
            for (i in 0 until 15) {
                val shareId = runCatching {
                    client.newCall(get(url, cookie)).execute().use { resp ->
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        if (json.optInt("status") != 200) return@use null
                        val data = json.optJSONObject("data") ?: return@use null
                        val finished = data.optLong("finished_at") > 0 || data.optInt("status") == 2
                        if (!finished) return@use null
                        data.optString("share_id").takeIf { it.isNotBlank() }
                    }
                }.getOrNull()
                if (shareId != null) return@withContext shareId
                delay(1000)
            }
            null
        }

    /** 查询分享信息（云盘功能抓包：POST share/password body={share_id} → 链接/提取码/标题） */
    suspend fun getShareInfo(shareId: String, cookie: String): ShareInfo? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("share_id", shareId).toString()
        val request = postJson(endpoints.shareInfoUrl, cookie, body)
        parseData(request) { data ->
            ShareInfo(
                shareUrl = data.optString("share_url"),
                passcode = data.optString("passcode"),
                pwdId = data.optString("pwd_id"),
                title = data.optString("title"),
                expiredType = data.optInt("expired_type")
            )
        }
    }

    // ---------- 请求构造与响应解析（平台特有流程也会用到，故为 protected） ----------

    protected fun get(url: String, cookie: String): Request =
        Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", endpoints.apiUserAgent)
            .get()
            .build()

    protected fun postJson(url: String, cookie: String, body: String): Request =
        Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", endpoints.apiUserAgent)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()

    /**
     * 信封拆包：执行请求 → 合并响应 Set-Cookie → 解析 JSON → 校验 status==200 → 透传 data 给 parser。
     * 服务端 message（如「提取码错误」「分享已失效」）原样透传给上层。
     */
    protected fun <T> parseData(request: Request, parser: (JSONObject) -> T): T {
        val response = client.newCall(request).execute()
        val body = response.use {
            mergeCookieFromResponse(request, it)
            it.body?.string() ?: throw PanApiException("请求失败：响应为空")
        }
        val json = runCatching { JSONObject(body) }.getOrElse {
            // 服务端返回非 JSON（多半是 HTML 错误页/风控页）时带上 HTTP 状态码，
            // 便于分辨「未登录被拒（401/403）」与「分享已失效」
            throw PanApiException("响应解析失败（HTTP ${response.code}）")
        }
        if (json.optInt("status") != 200) {
            throw PanApiException(json.optString("message").ifBlank { "请求失败" })
        }
        return parser(json.optJSONObject("data") ?: throw PanApiException("响应缺少 data"))
    }

    /** 从响应 Set-Cookie 合并 __puus/__pus 回原 Cookie 并回调 cookieSink（保持会话新鲜，对齐 AList requestWithCookie） */
    protected fun mergeCookieFromResponse(request: Request, response: okhttp3.Response) {
        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isEmpty()) return
        val original = request.header("Cookie").orEmpty()
        if (original.isBlank()) return
        val merged = PuusCookieUtil.mergeFromSetCookies(original, setCookies)
        if (merged != original) cookieSink?.invoke(merged, original)
    }
}
