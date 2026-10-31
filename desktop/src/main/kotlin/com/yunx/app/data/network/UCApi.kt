package com.yunx.app.data.network

import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.PlayLink
import com.yunx.app.data.network.model.ShareFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UC 网盘 API 封装（OkHttp）：账号验证 + 分享解析 + 下载直链。
 * 与夸克 API 结构一致，仅域名/UA/pr 参数不同 —— 共用部分全部在基类 [QuarkUcClient]（deep module），
 * 本类只保留 UC 特有流程：
 * - POST 版分享文件列表（sharepage/v2/detail，data.detail_info.list）与转存分享列表（transfer_share/detail）
 * - 官方下载流程 getShareDownloadLink（entry=ft，不需要先转存）
 * - 个人云盘直链 cloudGetDownloadLink、视频预览 getVideoPreview、转码播放 getPlayLink
 */
class UCApi(
    clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) : QuarkUcClient(clientProvider) {

    override val endpoints get() = UCEndpoints
    override val platformLabel get() = "UC"

    /** 保持 UC 现状：登录态取链（getDownloadLink）不回写 cookieSink（夸克会回写）。
     *  是否对齐夸克需实测验证，本次结构收敛不改行为。 */
    override val sinkCookiesOnDownloadLink = false

    /** 云盘文件列表 URL：_sort 为 URL 编码形式（抓包原样） */
    override fun cloudFilesUrl(pdirFid: String, page: Int, size: Int): String = buildString {
        append(UCConstants.CLOUD_FILE_SORT_URL)
        append("&pdir_fid=").append(pdirFid)
        append("&_page=").append(page)
        append("&_size=").append(size)
        append("&_fetch_total=1")
        append("&_fetch_sub_dirs=0")
        append("&_sort=file_type%3Aasc%2Cupdated_at%3Adesc")
    }

    /** 游客取链的服务端错误码 → 中文提示（错误码表对齐相应适配器；UC 另有 14001/41020 令牌失效） */
    override fun guestErrorMessage(code: Int, message: String): String = when (code) {
        31001 -> "该分享需要登录 UC 网盘后才能下载"
        23018 -> "该文件超出游客可获取的大小上限，请先登录 UC 网盘再下载"
        14001 -> "分享已失效或提取码有误，请重新解析"
        41020 -> "文件令牌已过期，请重新解析分享"
        else -> message.ifBlank { "获取下载链接失败" }
    }

    /**
     * 获取转存分享文件列表（transfer_share/detail，官方下载流程）。
     * GET + query 携带 stoken → 返回的 share_fid_token 与 stoken 绑定，download 才能通过校验。
     */
    suspend fun getTransferShareFiles(
        shareId: String,
        stoken: String,
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 50
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(UCConstants.TRANSFER_SHARE_DETAIL_URL)
            append("&pwd_id=").append(shareId)
            append("&pdir_fid=").append(pdirFid)
            append("&fetch_file_list=1")
            append("&passcode=")
            append("&_page=").append(page)
            append("&_size=").append(size)
            append("&_fetch_total=1")
            append("&_fetch_task=1")
            append("&_fetch_share=1")
            append("&_sort=")
            append("&stoken=").append(URLEncoder.encode(stoken, "UTF-8"))
        }
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .header("Origin", "https://fast.uc.cn")
            .header("Referer", "https://fast.uc.cn/")
            .get()
            .build()
        parseData(request) { data ->
            // 兼容 data.list 或 data.detail_info.list 两种结构
            val array = data.optJSONArray("list")
                ?: data.optJSONObject("detail_info")?.optJSONArray("list")
                ?: JSONArray()
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
     * 获取分享文件列表（sharepage/v2/detail，UC 官方为 POST + JSON body）。
     * 官方抓包：body 携带 pwd_id/passcode/page/size/fetch_banner 等，不携带 stoken；
     * 进入子目录时 body 追加 pdir_fid。
     */
    suspend fun getShareFiles(
        shareId: String,
        pwd: String?,
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 50
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("pwd_id", shareId)
            .put("passcode", pwd ?: "")
            .put("force", 0)
            .put("page", page)
            .put("size", size)
            .put("fetch_banner", 1)
            .put("fetch_share", 1)
            .put("fetch_total", 1)
            .put("sort", "file_type:asc,file_name:asc")
            .put("banner_platform", "other")
            .put("web_platform", "windows")
            .put("fetch_error_background", 1)
        // 子目录时追加 pdir_fid（根目录官方不传）
        if (pdirFid.isNotBlank() && pdirFid != UCConstants.DEFAULT_PDIR_FID) {
            body.put("pdir_fid", pdirFid)
        }
        val request = Request.Builder()
            .url("${UCConstants.SHARE_DETAIL_URL}&ve=2.5.20")
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .header("Origin", "https://drive.uc.cn")
            .header("Referer", "https://drive.uc.cn/")
            .header("Content-Type", "application/json;charset=UTF-8")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        parseData(request) { data ->
            // UC v2/detail：文件列表在 data.detail_info.list（不是 data.list）
            val detailInfo = data.optJSONObject("detail_info")
            val array = detailInfo?.optJSONArray("list") ?: JSONArray()
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
     * UC 官方下载流程（抓包）：不需要先转存！
     * POST file/download?entry=ft&fr=pc&pr=UCBrowser
     * body: {"fids":[分享fid],"pwd_id":短码,"stoken":token接口返回,"fids_token":[分享fid_token]}
     */
    suspend fun getShareDownloadLink(
        fid: String,
        fidToken: String,
        stoken: String,
        pwdId: String,
        cookie: String
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("fids", JSONArray().put(fid))
            .put("pwd_id", pwdId)
            .put("stoken", stoken)
            .put("fids_token", JSONArray().put(fidToken))
            .toString()
        val request = postJson(UCConstants.DOWNLOAD_URL, cookie, body)
        val response = client.newCall(request).execute()
        val bodyStr = response.use {
            it.body?.string() ?: throw PanApiException("获取下载链接失败：响应为空")
        }
        val json = runCatching { JSONObject(bodyStr) }.getOrElse {
            throw PanApiException("响应解析失败")
        }
        if (json.optInt("status") != 200) {
            throw PanApiException(json.optString("message").ifBlank { "获取下载链接失败" })
        }
        val item = json.optJSONArray("data")?.optJSONObject(0)
            ?: throw PanApiException("未返回下载链接")
        DownloadLink(
            fid = item.optString("fid"),
            filename = item.optString("file_name").ifEmpty { item.optString("filename") },
            downloadUrl = item.optString("download_url"),
            size = item.optLong("size")
        )
    }

    /** 云盘下载直链（抓包：个人云盘文件用 ?pr=UCBrowser&fr=pc&sys=win32&ve=1.6.1，非 entry=ft 分享通道） */
    suspend fun cloudGetDownloadLink(fid: String, cookie: String): DownloadLink? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("fids", JSONArray().put(fid)).toString()
        val request = Request.Builder()
            .url(UCConstants.CLOUD_DOWNLOAD_URL)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.CLOUD_UA)
            .header("Origin", "https://drive.uc.cn")
            .header("Referer", "https://drive.uc.cn/")
            .header("Content-Type", "application/json;charset=UTF-8")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        val bodyStr = response.use {
            it.body?.string() ?: throw PanApiException("获取下载链接失败：响应为空")
        }
        val json = runCatching { JSONObject(bodyStr) }.getOrElse {
            throw PanApiException("响应解析失败")
        }
        if (json.optInt("status") != 200 && json.optInt("code") != 0) {
            throw PanApiException(
                json.optString("message").ifBlank { "获取下载链接失败" },
                json.optInt("code")
            )
        }
        val array = json.optJSONArray("data") ?: throw PanApiException("响应缺少 data")
        if (array.length() == 0) throw PanApiException("未返回下载链接")
        val item = array.optJSONObject(0) ?: throw PanApiException("未返回下载链接")
        DownloadLink(
            fid = item.optString("fid"),
            filename = item.optString("file_name").ifEmpty { item.optString("filename") },
            downloadUrl = item.optString("download_url"),
            size = item.optLong("size")
        )
    }

    /**
     * 分享视频预览（原画直链，绕过非会员视频下载被换成宣传片的问题）。
     * GET share/sharepage/video_preview？pwd_id/stoken/fid/fid_token →
     * data.play_info.url（原画 OSS 直链，走播放回调 checkplay 不换片）+ size（原画大小，可校验）。
     * 仅对分享态视频有意义；链接约 3 小时有效（x-ttl=10800）。
     */
    suspend fun getVideoPreview(
        pwdId: String,
        stoken: String,
        fid: String,
        fidToken: String,
        cookie: String
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(UCConstants.VIDEO_PREVIEW_URL)
            append("?pr=UCBrowser&fr=h5")
            append("&pwd_id=").append(URLEncoder.encode(pwdId, "UTF-8"))
            append("&stoken=").append(URLEncoder.encode(stoken, "UTF-8"))
            append("&fid=").append(URLEncoder.encode(fid, "UTF-8"))
            append("&fid_token=").append(URLEncoder.encode(fidToken, "UTF-8"))
        }
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .header("Origin", UCConstants.WEB_ORIGIN)
            .header("Referer", UCConstants.DOWNLOAD_REFERER)
            .header("Content-Type", "application/json")
            .get()
            .build()
        runCatching {
            val resp = client.newCall(request).execute()
            val json = JSONObject(resp.use { it.body?.string() } ?: "{}")
            if (json.optInt("status") != 200 && json.optInt("code") != 0) return@runCatching null
            val data = json.optJSONObject("data") ?: return@runCatching null
            val playInfo = data.optJSONObject("play_info") ?: return@runCatching null
            val directUrl = playInfo.optString("url").takeIf { it.isNotBlank() } ?: return@runCatching null
            DownloadLink(
                fid = fid,
                filename = "",
                downloadUrl = directUrl,
                size = playInfo.optLong("size")
            )
        }.getOrNull()
    }

    /**
     * UC 转码播放流（绕过非会员视频下载被换成宣传片的问题）。
     * POST file/v2/play/project → data.video_list[].video_info.url（m3u8/fmp4）。
     * 仅对视频有意义；返回首个非空播放地址 + 其清晰度。
     * 先试带 pr/fr 的主路径；失败则用裸路径重试（Alist getTranscodingLink 方式，对 UC 也可通）。
     */
    suspend fun getPlayLink(fid: String, cookie: String): PlayLink? = withContext(Dispatchers.IO) {
        playProject(UCConstants.PLAY_URL, fid, cookie)
            ?: playProject("${UCConstants.API_BASE}/1/clouddrive/file/v2/play/project", fid, cookie)
    }

    private fun playProject(url: String, fid: String, cookie: String): PlayLink? {
        val body = JSONObject()
            .put("fid", fid)
            .put("resolutions", "low,normal,high,super,2k,4k")
            .put("supports", "fmp4_av,m3u8,dolby_vision")
            .toString()
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("Origin", UCConstants.WEB_ORIGIN)
            .header("Referer", UCConstants.DOWNLOAD_REFERER)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return runCatching {
            val resp = client.newCall(request).execute()
            val json = JSONObject(resp.use { it.body?.string() } ?: "{}")
            if (json.optInt("status") != 200 && json.optInt("code") != 0) return@runCatching null
            val list = json.optJSONObject("data")?.optJSONArray("video_list") ?: return@runCatching null
            for (i in 0 until list.length()) {
                val info = list.optJSONObject(i)?.optJSONObject("video_info") ?: continue
                val u = info.optString("url").takeIf { it.isNotBlank() } ?: continue
                return@runCatching PlayLink(
                    url = u,
                    resolution = info.optString("resolution"),
                    format = info.optString("format"),
                    isHls = u.contains(".m3u8") || info.optString("format").contains("m3u8", true)
                )
            }
            null
        }.getOrNull()
    }
}
