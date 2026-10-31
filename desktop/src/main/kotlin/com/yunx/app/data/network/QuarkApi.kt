package com.yunx.app.data.network

import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 夸克 API 封装（OkHttp）：账号验证 + 分享解析 + 下载直链。
 *
 * 请求骨架 / 信封拆包 / __puus 会话合并 / 游客 __pugs / 任务轮询 / 翻页合并等
 * 与 UC 同构的部分全部在基类 [QuarkUcClient]（deep module）；本类只保留夸克特有流程：
 * - GET 版分享文件列表（sharepage/detail，UC 是 POST v2/detail）
 * - 免转存分享取链（getShareDownloadLinkWithoutSave，把分享凭证直接交给 download 接口）
 */
class QuarkApi(
    clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) : QuarkUcClient(clientProvider) {

    override val endpoints get() = QuarkEndpoints
    override val platformLabel get() = "夸克"

    /** 云盘文件列表 URL：在抓包基础上追加 uc_param_str / fetch_all_file / fetch_risk_file_name */
    override fun cloudFilesUrl(pdirFid: String, page: Int, size: Int): String = buildString {
        append(QuarkConstants.CLOUD_FILE_SORT_URL)
        append("&uc_param_str=")
        append("&pdir_fid=").append(pdirFid)
        append("&_page=").append(page)
        append("&_size=").append(size)
        append("&_fetch_total=1")
        append("&_fetch_sub_dirs=0")
        append("&_sort=file_type:asc,updated_at:desc")
        append("&fetch_all_file=1")
        append("&fetch_risk_file_name=1")
    }

    /** 夸克分享 token 请求需携带 support_visit_limit_private_share */
    override fun applyShareTokenExtra(body: JSONObject) {
        body.put("support_visit_limit_private_share", true)
    }

    /** 夸克游客取链 body 带 speedup_session/token 空占位（token 是社交转存令牌，游客取不到，官方前端同样 catch 之后传空串） */
    override fun applyGuestBodyExtra(body: JSONObject) {
        body.put("speedup_session", "")
        body.put("token", "")
    }

    /** 夸克创建分享请求需携带 support_error_code */
    override fun applyCreateShareExtra(body: JSONObject, passcode: String) {
        body.put("support_error_code", JSONArray().put("41060"))
    }

    /** 游客取链的服务端错误码 → 中文提示（23018=超出游客大小上限、31001=需要登录） */
    override fun guestErrorMessage(code: Int, message: String): String = when (code) {
        23018 -> "该文件超出游客可获取的大小上限（夸克实测约 50MB），请先登录夸克网盘再下载"
        31001 -> "该分享需要登录夸克网盘后才能下载"
        else -> message.ifBlank { "获取下载链接失败" }
    }

    /** 4.2 获取分享文件列表（sharepage/detail）
     *  官方字段：file_name / size / dir(boolean) / share_fid_token，
     *  与 kkdo.md 文档中的 fname/fsize/isdir/fid_token 不同，以抓包为准。
     */
    suspend fun getShareFiles(
        shareId: String,
        stoken: String,
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 100
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        // 参数名必须为 pwd_id（值=分享链接短码），并追加 ver=2 / _page / _size 等固定参数
        val url = buildString {
            append(QuarkConstants.SHARE_DETAIL_URL)
            append("&pwd_id=").append(shareId)
            append("&stoken=").append(URLEncoder.encode(stoken, "UTF-8"))
            append("&pdir_fid=").append(pdirFid)
            append("&ver=2")
            append("&force=0")
            append("&_page=").append(page)
            append("&_size=").append(size)
            append("&_fetch_banner=0")
            append("&_fetch_share=0")
            append("&fetch_relate_conversation=0")
            append("&_fetch_total=1")
            append("&_sort=file_type:asc,file_name:asc")
        }
        // 该接口需携带 Origin / Referer，否则可能返回 400
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", QuarkConstants.API_USER_AGENT)
            .header("Origin", "https://pan.quark.cn")
            .header("Referer", "https://pan.quark.cn/")
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
                            fname = item.optString("file_name"),
                            fsize = item.optLong("size"),
                            isdir = item.optBoolean("dir", false),
                            pdirFid = item.optString("pdir_fid"),
                            fidToken = item.optString("share_fid_token"),
                            modifyTime = item.optString("updated_at")
                        )
                    )
                }
            }
        }
    }

    /**
     * 6.1c 免转存分享取链（登录态）
     *
     * 不把分享文件转存到自己的网盘，直接把**分享凭证**交给 download 接口换直链：
     * body 与游客取链同构（`fids` / `fids_token` / `pwd_id` / `stoken` + 两个空占位），
     * 区别只在 Cookie 用账号态 —— 因此不受游客态「约 50MB」限制，也不会在用户网盘里留临时目录。
     *
     * `fidToken` = 分享列表返回的 `share_fid_token`（[ShareFile.fidToken]）、
     * `shareId` = 分享短码（pwd_id）、`stoken` = 分享访问凭证（[ShareSession.stoken]）。
     * 响应 Set-Cookie 的 __puus/__pus 照常合并回会话：直链签名绑定取链时刻的 Cookie
     * （与 [getDownloadLink] 同一要求）。
     *
     * @throws PanApiException 登录态失效（31001）、分享失效、风控频控等
     */
    suspend fun getShareDownloadLinkWithoutSave(
        fid: String,
        fidToken: String,
        shareId: String,
        stoken: String,
        cookie: String
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("fids", JSONArray().put(fid))
            .put("fids_token", JSONArray().put(fidToken))
            .put("pwd_id", shareId)
            .put("stoken", stoken)
            // 与游客取链打的是同一个端点：speedup_session / token 留空即可
            // （token 是社交转存令牌，游客取不到，官方前端同样 catch 之后传空串）
            .put("speedup_session", "")
            .put("token", "")
            .toString()
        val request = postJson(endpoints.downloadUrl, cookie, body)
        val response = client.newCall(request).execute()
        val bodyStr = response.use {
            mergeCookieFromResponse(request, it)
            it.body?.string() ?: throw PanApiException("获取下载链接失败：响应为空")
        }
        val json = runCatching { JSONObject(bodyStr) }.getOrElse {
            // 非 JSON（风控页/HTML 错误页）时带上 HTTP 状态码，便于分辨 401/403 与分享失效
            throw PanApiException("响应解析失败（HTTP ${response.code}）")
        }
        val code = json.optInt("code")
        if (json.optInt("status") != 200 && code != 0) {
            throw PanApiException(noSaveErrorMessage(code, json.optString("message")), code)
        }
        parseDownloadLinkArray(json)
    }

    /** 免转存取链的服务端错误码 → 中文提示（31001=登录态失效；23018=服务端仍按游客大小限制放行） */
    private fun noSaveErrorMessage(code: Int, message: String): String = when (code) {
        31001 -> "夸克登录态已失效，请到「网盘」页重新登录后再试"
        23018 -> "服务端按游客限制拒绝了该文件（夸克约 50MB 上限）"
        else -> message.ifBlank { "获取下载链接失败" }
    }
}
