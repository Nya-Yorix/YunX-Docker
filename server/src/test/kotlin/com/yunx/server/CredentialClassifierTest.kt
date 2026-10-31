package com.yunx.server

import com.yunx.app.data.network.CredentialCheck
import com.yunx.app.data.network.CredentialClassifier
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CredentialClassifierTest {

    private fun auth(message: String): Boolean =
        CredentialClassifier.classify(IllegalStateException(message)) is CredentialCheck.Invalid

    @Test fun knownAuthCodesAreInvalid() {
        // 夸克/UC/百度/139/115/123/光鸭/迅雷/蓝奏优享——假 token 实测与库内常量
        assertTrue(auth("夸克登录态已失效（code=31001）"))
        assertTrue(auth("UC 登录态已失效（code=14001）"))
        assertTrue(auth("百度网盘登录已失效（errno=-6）"))
        assertTrue(auth("获取空间详情失败（code=04000005）"))
        assertTrue(auth("登录已过期，请重新登录115网盘（errno=990001）"))
        assertTrue(auth("cookie token is empty（code=401）"))
        assertTrue(auth("无效token"))
        assertTrue(auth("unauthenticated: 需要登录"))
        assertTrue(auth("帐号认证失败，请重新登录"))
        assertTrue(auth("蓝奏优享登录已过期，请重新登录"))
        assertTrue(auth("请求失败（HTTP 401）"))
        assertTrue(auth("请求失败（HTTP 403）"))
    }

    @Test fun transientFailuresAreUnknown() {
        val cases = listOf(
            IOException("Connection reset"),
            SocketTimeoutException("Read timed out"),
            IllegalStateException("Timed out waiting for 25000 ms"),
            IllegalStateException("上游接口异常（HTTP 502）"),
            IllegalStateException("百度接口返回 errno=8888"),
            IllegalStateException("获取容量信息失败（code=500）"),
        )
        cases.forEach { case ->
            val check = CredentialClassifier.classify(case)
            assertTrue(check is CredentialCheck.Unknown, "应为 Unknown: ${case.message}")
        }
    }

    @Test fun unknownMessagesDoNotBecomeAuth() {
        val check = CredentialClassifier.classify(IllegalStateException("123 接口返回 code=99999 未知错误"))
        assertTrue(check is CredentialCheck.Unknown)
        assertEquals("暂时无法验证", (CredentialClassifier.classify(IllegalStateException("")) as CredentialCheck.Unknown).message)
    }
}
