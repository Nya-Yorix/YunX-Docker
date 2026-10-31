package com.yunx.app.data.network

import com.yunx.app.data.network.model.QuotaInfo
import java.io.IOException

/**
 * 账号凭证校验的三态结果（仿 [GitHubApi.TokenCheck]）。
 *
 * accountInfo 这类「校验型调用」用它代替 getQuota 的 runCatching 吞错：
 * 只有上游**明确否定**（未登录/凭证失效码）才判 [Invalid]；
 * 网络异常、限流、5xx、无法解析一律 [Unknown]，绝不能把网络抖动当成登录失败。
 */
sealed interface CredentialCheck {
    data class Valid(val nickname: String?, val quota: QuotaInfo?) : CredentialCheck
    data class Invalid(val message: String) : CredentialCheck
    data class Unknown(val message: String) : CredentialCheck
}

/** accountInfo 判定凭证失效时抛出；WebServer 据此在错误响应里带 kind=auth，前端红字「登录失败」 */
class CredentialExpiredException(message: String) : IllegalStateException(message)

/**
 * 把校验型调用抛出的异常归类为 Invalid（凭证失效）/ Unknown（暂时性失败）。
 *
 * 码表来源：各平台假 token 实测 + 库内已知常量——
 * - 夸克/UC：`code=31001` 登录态失效（UC 另有 14001/41020 令牌失效）【实测✓】
 * - 百度：`errno=-6` 未登录/身份验证失败（8888 为限流，已有自动重试，归 Unknown）【实测✓】
 * - 139：`code=04000005` 认证失败；Cookie 缺 Authorization 也属凭证问题【实测✓】
 * - 115：`errno=990001` 登录已过期【实测✓】
 * - 123：token 失效 → `cookie token is empty（code=401）`【实测✓】
 * - 光鸭：token 失效 → `无效token`【实测✓】
 * - 迅雷：401 / `unauthenticated`，刷新失败文案「帐号认证失败，请重新登录」【实测✓】
 * - 蓝奏优享：HTTP 401/403、code -1/-2（ILanzouLoginExpired 固定文案）【实测✓】
 * - check* 系列抛错时把码拼进消息（"…（code=xxx）"/"…（errno=xxx）"），据此匹配
 */
object CredentialClassifier {

    /** 消息中出现即判「凭证失效」的码值与关键词 */
    private val AUTH_PATTERNS = listOf(
        "31001", "14001", "41020",       // 夸克 / UC
        "errno=-6",                       // 百度未登录
        "04000005",                       // 139 认证失败
        "990001",                         // 115 登录已过期
        "cookie token is empty", "code=401", // 123 token 失效（实测）；401 语义通用
        "无效token", "无效 token",         // 光鸭 token 失效（实测）
        "unauthenticated", "unauthorized", // 迅雷 / 通用 OAuth
        "登录已过期", "登录态已失效", "登录态无效", "重新登录", "凭证无效", "Cookie 无效", "身份验证失败", "认证失败",
    )

    /** 明确的鉴权类 HTTP 状态（避免把 500/502 之类误判成登录失败） */
    private val HTTP_AUTH = Regex("HTTP 40[13](\\D|$)")

    /** 上游 5xx / 限流 → 一律暂时性 */
    private val TRANSIENT_HTTP = Regex("HTTP 5\\d\\d")

    fun classify(e: Throwable): CredentialCheck {
        val message = e.message.orEmpty()
        // 网络/超时类（IOException 含 SocketTimeout）永远是暂时性
        if (e is IOException || message.contains("timeout", ignoreCase = true) || message.contains("Timed out")) {
            return CredentialCheck.Unknown(message.ifBlank { "网络异常" })
        }
        if (TRANSIENT_HTTP.containsMatchIn(message) || message.contains("8888")) {
            return CredentialCheck.Unknown(message.ifBlank { "上游暂时不可用" })
        }
        val auth = AUTH_PATTERNS.any { message.contains(it, ignoreCase = true) } || HTTP_AUTH.containsMatchIn(message)
        return if (auth) CredentialCheck.Invalid(message.ifBlank { "登录失败" })
        else CredentialCheck.Unknown(message.ifBlank { "暂时无法验证" })
    }
}
