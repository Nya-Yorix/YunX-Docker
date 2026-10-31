package com.yunx.server

import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.CredentialCheck
import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GuangYaApi
import com.yunx.app.data.network.GuangYaDevice
import com.yunx.app.data.network.ILanzouApi
import com.yunx.app.data.network.ILanzouConstants
import com.yunx.app.data.network.LanzouApi
import com.yunx.app.data.network.LanzouConstants
import com.yunx.app.data.network.Pan115Api
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.TokenCheck
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import org.json.JSONObject

/**
 * 网盘平台操作面（[CloudDriveOps]）+ 注册表（[CloudDrives]）。
 *
 * 把 ServerService 的 accountInfo / cloudFiles / cloudDownload 三条消费链上的
 * per-platform when 臂收进每平台一个 adapter。以下这些全是**平台知识**，收进各 adapter 实现：
 * - 凭证字段怎么读（cookie / accessToken+deviceId+captchaToken / GuangYaDevice / uuid）
 * - 迅雷 refreshToken 怎么续（refreshTokenProvider 注入）
 * - 目录分页是什么形态（页码 / 偏移 / 游标 / 全量）
 * - 个人云盘根目录 fid 的约定（百度/139 "/"、迅雷 ""、蓝奏 "-1"、其余 "0"）
 * - 取链走哪个端点（个人云盘 / cloudGetDownloadLink / locateDownload …）
 *
 * 调用方只认 [CloudDriveOps] 一个 interface；[CloudDrives.forPlatform] 对 SharePlatform
 * **穷举且无 else** —— 新增平台漏写 adapter 直接编译失败（原来是运行时才炸）。
 *
 * 凭证来源以 lambda 在装配时闭包注入（server 端读加密 Credentials 存储；桌面版未来接入时
 * 换成 Room DAO 读取即可，接口不动）。
 */
interface CloudDriveOps {
    /** 个人云盘根目录 fid 约定（对齐桌面版） */
    val rootDir: String

    /** 账号校验（三态）；顺手带回昵称（拿不到为 null，accountInfo 回退账号 label） */
    suspend fun credentialCheck(): CredentialCheckResult

    /** 个人云盘目录列表；各平台分页形态（页码/偏移/游标/全量）在实现内部适配 */
    suspend fun listCloudDir(dir: String, page: Int, cursor: String?): CloudDirPage

    /** 个人云盘文件的下载直链；取不到返回 null（调用方统一报「未取得下载地址」） */
    suspend fun personalDownloadLink(file: ShareFile): DownloadLink?
}

/** 三态校验结果 + 昵称（来自同一次装配的 API 实例） */
data class CredentialCheckResult(val check: CredentialCheck, val nickname: String? = null)

/** 目录列表一页：文件 + 是否还有下一页 + 游标（游标型平台用）+ 平台专属提示位 */
data class CloudDirPage(
    val files: List<ShareFile>,
    val hasMore: Boolean = false,
    val cursor: String = "",
    /** 迅雷个人目录 500 条上限提示（cloudFiles 响应的 limited 字段） */
    val limited: Boolean = false
)

/** 平台 adapter 注册表：负责每平台的 API 装配（含凭证回写）与 [CloudDriveOps] 分发 */
class CloudDrives(private val credentials: Credentials) {

    // ---------- 夸克 / UC 装配（原 ServerService.quark()/uc()：cookieSink 回写加密凭证库） ----------

    fun quark(): QuarkApi {
        var expected = credentials.credential("QUARK")
        return QuarkApi().apply { cookieSink = { merged, _ -> credentials.updateCookie("QUARK", expected, merged); expected = merged } }
    }

    fun uc(): UCApi {
        var expected = credentials.credential("UC")
        return UCApi().apply { cookieSink = { merged, _ -> credentials.updateCookie("UC", expected, merged); expected = merged } }
    }

    /** 迅雷装配：401 自动刷新一次（refresh 结果不落库——落库语义在 repo() 的续期闭包里） */
    private fun xunlei(): XunleiApi {
        val api = XunleiApi()
        api.refreshTokenProvider = { deviceId ->
            val account = credentials.get(SharePlatform.XUNLEI.name)
            runCatching { api.refreshToken(account.optString("refreshToken"), deviceId, account.optString("authType")) }.getOrNull()
        }
        return api
    }

    private fun credential(p: SharePlatform): String = credentials.credential(p.name)
    private fun account(p: SharePlatform): JSONObject = credentials.get(p.name)

    /** 平台 → adapter（穷举无 else：漏平台 = 编译失败） */
    fun forPlatform(p: SharePlatform): CloudDriveOps = when (p) {
        SharePlatform.QUARK -> QuarkDrive(quark()) { credential(p) }
        SharePlatform.UC -> UCDrive(uc()) { credential(p) }
        SharePlatform.XUNLEI -> XunleiDrive(xunlei(), { credential(p) }) { account(p) }
        SharePlatform.BAIDU -> BaiduDrive(BaiduApi()) { credential(p) }
        SharePlatform.C139 -> C139Drive(C139Api()) { credential(p) }
        SharePlatform.PAN123 -> Pan123Drive(Pan123Api()) { credential(p) }
        SharePlatform.PAN115 -> Pan115Drive(Pan115Api()) { credential(p) }
        SharePlatform.GUANGYA -> GuangYaDrive(
            GuangYaApi().apply { deviceIdProvider = { account(p).optString("deviceId") } },
            { credential(p) }
        ) { GuangYaDevice(account(p).optString("deviceId"), account(p).optString("deviceSign")) }
        SharePlatform.ILANZOU -> ILanzouDrive(ILanzouApi(), { credential(p) }) { account(p).getString("uuid") }
        SharePlatform.LANZOU -> LanzouDrive(LanzouApi()) { credential(p) }
        SharePlatform.GITHUB -> GitHubDrive(GitHubApi(tokenProvider = { credentials.get("GITHUB").optString("accessToken") })) { credential(p) }
    }
}

// ---------- 各平台 adapter（端口自 ServerService 原 when 臂，逐条保真） ----------

internal class QuarkDrive(private val api: QuarkApi, private val cookie: () -> String) : CloudDriveOps {
    override val rootDir = "0"
    override suspend fun credentialCheck(): CredentialCheckResult {
        val nickname = runCatching { api.fetchNickname(cookie()) }.getOrNull()
        return CredentialCheckResult(api.quotaCheck(cookie()), nickname)
    }
    override suspend fun listCloudDir(dir: String, page: Int, cursor: String?): CloudDirPage {
        val files = api.listCloudFiles(dir, cookie(), page, 100) ?: error("无法获取文件，请检查凭证")
        return CloudDirPage(files, hasMore = files.size == 100)
    }
    override suspend fun personalDownloadLink(file: ShareFile): DownloadLink? =
        api.getDownloadLink(file.fid, cookie())
}

internal class UCDrive(private val api: UCApi, private val cookie: () -> String) : CloudDriveOps {
    override val rootDir = "0"
    override suspend fun credentialCheck(): CredentialCheckResult {
        val nickname = runCatching { api.fetchNickname(cookie()) }.getOrNull()
        return CredentialCheckResult(api.quotaCheck(cookie()), nickname)
    }
    override suspend fun listCloudDir(dir: String, page: Int, cursor: String?): CloudDirPage {
        val files = api.listCloudFiles(dir, cookie(), page, 100) ?: error("无法获取文件，请检查凭证")
        return CloudDirPage(files, hasMore = files.size == 100)
    }
    override suspend fun personalDownloadLink(file: ShareFile): DownloadLink? =
        api.cloudGetDownloadLink(file.fid, cookie())
}

internal class XunleiDrive(
    private val api: XunleiApi,
    private val accessToken: () -> String,
    private val account: () -> JSONObject
) : CloudDriveOps {
    override val rootDir = ""    // 迅雷个人目录根用空串
    private fun deviceId() = account().optString("deviceId")
    private fun captchaToken() = account().optString("captchaToken")
    override suspend fun credentialCheck(): CredentialCheckResult =
        CredentialCheckResult(api.quotaCheck(accessToken(), deviceId(), captchaToken()))
    override suspend fun listCloudDir(dir: String, page: Int, cursor: String?): CloudDirPage {
        val files = api.getFiles(dir, accessToken(), deviceId(), captchaToken())
            ?: error("无法获取迅雷目录")
        return CloudDirPage(files, limited = files.size >= 500)
    }
    override suspend fun personalDownloadLink(file: ShareFile): DownloadLink? =
        api.getFileDetail(file.fid, accessToken(), deviceId(), captchaToken())
}

internal class BaiduDrive(private val api: BaiduApi, private val cookie: () -> String) : CloudDriveOps {
    override val rootDir = "/"   // 百度根目录用 "/"
    override suspend fun credentialCheck(): CredentialCheckResult {
        val nickname = runCatching { api.fetchNickname(cookie()) }.getOrNull()
        return CredentialCheckResult(api.quotaCheck(cookie()), nickname)
    }
    override suspend fun listCloudDir(dir: String, page: Int, cursor: String?): CloudDirPage =
        CloudDirPage(api.listCloudFiles(dir, cookie()))   // 百度一次返回全量，无分页
    override suspend fun personalDownloadLink(file: ShareFile): DownloadLink? =
        DownloadLink(file.fid, file.fname, api.locateDownload(file.fidToken, cookie()), file.fsize)
}

internal class C139Drive(private val api: C139Api, private val cookie: () -> String) : CloudDriveOps {
    override val rootDir = "/"   // 139 根目录用 "/"
    override suspend fun credentialCheck(): CredentialCheckResult =
        CredentialCheckResult(api.quotaCheck(cookie()))
    override suspend fun listCloudDir(dir: String, page: Int, cursor: String?): CloudDirPage {
        val (files, next) = api.listCloudFiles(dir, cookie(), cursor?.ifBlank { null })
        return CloudDirPage(files, hasMore = next != null, cursor = next.orEmpty())
    }
    override suspend fun personalDownloadLink(file: ShareFile): DownloadLink? =
        api.getDownloadUrl(file.fid, cookie())
}

internal class Pan123Drive(private val api: Pan123Api, private val token: () -> String) : CloudDriveOps {
    override val rootDir = "0"
    override suspend fun credentialCheck(): CredentialCheckResult {
        val nickname = runCatching { api.fetchNickname(token()) }.getOrNull()
        return CredentialCheckResult(api.quotaCheck(token()), nickname)
    }
    override suspend fun listCloudDir(dir: String, page: Int, cursor: String?): CloudDirPage {
        val (files, next) = api.listCloudFiles(dir, token(), cursor?.ifBlank { "0" } ?: "0")
        return CloudDirPage(files, hasMore = next?.isNotBlank() == true, cursor = next.orEmpty())
    }
    override suspend fun personalDownloadLink(file: ShareFile): DownloadLink? =
        api.getDownloadLink(file, token())
}

internal class Pan115Drive(private val api: Pan115Api, private val cookie: () -> String) : CloudDriveOps {
    override val rootDir = "0"
    override suspend fun credentialCheck(): CredentialCheckResult {
        val nickname = runCatching { api.fetchNickname(cookie()) }.getOrNull()
        return CredentialCheckResult(api.quotaCheck(cookie()), nickname)
    }
    override suspend fun listCloudDir(dir: String, page: Int, cursor: String?): CloudDirPage {
        val result = api.listFiles(dir, cookie(), (page - 1) * 100, 100)
        return CloudDirPage(result.files, hasMore = page * 100 < result.total)
    }
    override suspend fun personalDownloadLink(file: ShareFile): DownloadLink? =
        api.getDownloadLink(file, cookie())
}

internal class GuangYaDrive(
    private val api: GuangYaApi,
    private val accessToken: () -> String,
    private val device: () -> GuangYaDevice
) : CloudDriveOps {
    override val rootDir = "0"
    override suspend fun credentialCheck(): CredentialCheckResult =
        CredentialCheckResult(api.quotaCheck(accessToken(), device()))
    override suspend fun listCloudDir(dir: String, page: Int, cursor: String?): CloudDirPage =
        CloudDirPage(api.listCloudFiles(accessToken(), dir))   // 全量返回，无分页
    override suspend fun personalDownloadLink(file: ShareFile): DownloadLink? =
        api.getDownloadLink(accessToken(), file)
}

internal class ILanzouDrive(
    private val api: ILanzouApi,
    private val appToken: () -> String,
    private val uuid: () -> String
) : CloudDriveOps {
    override val rootDir = "0"
    override suspend fun credentialCheck(): CredentialCheckResult =
        CredentialCheckResult(api.quotaCheck(appToken(), uuid()))
    override suspend fun listCloudDir(dir: String, page: Int, cursor: String?): CloudDirPage =
        CloudDirPage(api.listFiles(appToken(), uuid(), dir))   // 全量返回，无分页
    override suspend fun personalDownloadLink(file: ShareFile): DownloadLink? {
        val uuid = uuid()
        val info = api.fetchAccountInfo(appToken(), uuid) ?: error("暂时无法验证")
        return api.getDownloadLink(appToken(), uuid, file.fid.removePrefix(ILanzouConstants.FILE_PREFIX), info.userId)
    }
}

internal class LanzouDrive(private val api: LanzouApi, private val cookie: () -> String) : CloudDriveOps {
    override val rootDir = "-1"  // 蓝奏云根目录用 "-1"
    private suspend fun loginParams() = api.fetchLoginParams(cookie()) ?: error("凭证无效")
    override suspend fun credentialCheck(): CredentialCheckResult =
        CredentialCheckResult(api.quotaCheck(cookie()))
    override suspend fun listCloudDir(dir: String, page: Int, cursor: String?): CloudDirPage {
        val params = loginParams()
        return CloudDirPage(api.listCloudFiles(cookie(), params.uid, params.vei, dir))   // 全量返回，无分页
    }
    override suspend fun personalDownloadLink(file: ShareFile): DownloadLink? {
        val params = loginParams()
        return api.getPersonalDownloadLink(cookie(), params.uid, params.vei, file.fid.removePrefix(LanzouConstants.FILE_PREFIX), file.fname)
    }
}

internal class GitHubDrive(private val api: GitHubApi, private val token: () -> String) : CloudDriveOps {
    override val rootDir = "0"   // 不参与个人云盘浏览（前端走 GitHub 仓库入口）
    override suspend fun credentialCheck(): CredentialCheckResult = when (val check = api.validateToken(token())) {
        is TokenCheck.Valid -> CredentialCheckResult(CredentialCheck.Valid(check.login, null), check.login)
        TokenCheck.Invalid -> CredentialCheckResult(CredentialCheck.Invalid("凭证无效"))
        TokenCheck.Unknown -> CredentialCheckResult(CredentialCheck.Unknown("暂时无法验证"))
    }
    override suspend fun listCloudDir(dir: String, page: Int, cursor: String?): CloudDirPage =
        error("请使用 GitHub 仓库入口")
    override suspend fun personalDownloadLink(file: ShareFile): DownloadLink? =
        error("请使用 GitHub 仓库入口")
}
