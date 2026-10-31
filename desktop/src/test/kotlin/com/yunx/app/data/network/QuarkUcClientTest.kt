package com.yunx.app.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

/**
 * QuarkUcClient（夸克/UC 共用客户端）行为测试：全部走 MockWebServer，
 * 用 URL 重写拦截器把硬编码的绝对端点指到本地服务（同 BaiduFileManagementTest 样板）。
 */
class QuarkUcClientTest {

    /** 把 api 里硬编码的绝对 URL 重写到 MockWebServer（保留 path 与 query） */
    private fun mockClient(server: MockWebServer): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request().url
            val replacement = server.url(original.encodedPath)
                .newBuilder().encodedQuery(original.encodedQuery).build()
            chain.proceed(chain.request().newBuilder().url(replacement).build())
        }.build()

    private fun serverWith(vararg responses: MockResponse): MockWebServer =
        MockWebServer().apply {
            responses.forEach { enqueue(it) }
            start()
        }

    private fun envelope(body: String, vararg headers: String): MockResponse =
        MockResponse().setBody(body).apply { headers.forEach { addHeader(it) } }

    private fun filesJson(vararg ids: String): String {
        val list = ids.joinToString(",") { id ->
            """{"fid":"$id","file_name":"n$id","size":123,"dir":false,"pdir_fid":"0","updated_at":"t"}"""
        }
        return """{"status":200,"code":0,"message":"ok","data":{"list":[$list]}}"""
    }

    @Test
    fun envelopeUnwrapsDataAndMapsListFiles() = runBlocking {
        val server = serverWith(envelope(filesJson("f0", "f1")))
        try {
            val api = QuarkApi(clientProvider = { mockClient(server) })
            val files = api.listCloudFiles("0", "c", page = 1, size = 50)
            assertEquals(listOf("f0", "f1"), files?.map { it.fid })
            assertEquals("nf0", files?.first()?.fname)
        } finally { server.shutdown() }
    }

    @Test
    fun envelopePassesServerMessageThroughOnError() = runBlocking {
        val server = serverWith(envelope("""{"status":41008,"message":"提取码错误"}"""))
        try {
            val api = QuarkApi(clientProvider = { mockClient(server) })
            val e = assertFailsWith<PanApiException> {
                api.getShareToken("share", "pwd", "c")
            }
            assertEquals("提取码错误", e.message)
        } finally { server.shutdown() }
    }

    @Test
    fun envelopeReportsHttpCodeOnNonJsonBody() = runBlocking {
        val server = serverWith(envelope("<html>blocked</html>"))
        try {
            val api = QuarkApi(clientProvider = { mockClient(server) })
            val e = assertFailsWith<PanApiException> {
                api.listCloudFiles("0", "c")
            }
            assertTrue(e.message!!.contains("响应解析失败（HTTP 200）"))
        } finally { server.shutdown() }
    }

    @Test
    fun quotaCheckClassifiesInvalidByPlatformCodeTable() = runBlocking {
        // code=14001：UC 的失效码表内有（14001），夸克表内没有 —— 同一响应两种判定
        // （两个请求共用同一 MockWebServer，需入队两份响应，否则 QueueDispatcher 阻塞）
        val server = serverWith(
            envelope("""{"status":400,"code":14001,"message":"token"}"""),
            envelope("""{"status":400,"code":14001,"message":"token"}""")
        )
        try {
            val client = mockClient(server)
            val quark = QuarkApi(clientProvider = { client }).quotaCheck("c")
            assertTrue(quark is CredentialCheck.Unknown, "夸克 14001 应为 Unknown，实为 $quark")
            val uc = UCApi(clientProvider = { client }).quotaCheck("c")
            assertTrue(uc is CredentialCheck.Invalid, "UC 14001 应为 Invalid，实为 $uc")
            assertEquals("UC 登录态已失效（code=14001）", (uc as CredentialCheck.Invalid).message)
        } finally { server.shutdown() }
    }

    @Test
    fun quotaCheckDistinguishesInvalidFromTransient() = runBlocking {
        val server = serverWith(
            envelope("""{"status":400,"code":31001,"message":"need login"}"""),
            envelope("""{"status":500,"code":99999,"message":"server busy"}"""),
            envelope("""{"status":200,"data":{"use_capacity":1,"total_capacity":2}}""")
        )
        try {
            val api = QuarkApi(clientProvider = { mockClient(server) })
            assertTrue(api.quotaCheck("c") is CredentialCheck.Invalid)
            assertTrue(api.quotaCheck("c") is CredentialCheck.Unknown)
            val valid = api.quotaCheck("c") as CredentialCheck.Valid
            assertEquals(1L, valid.quota?.used)
            assertEquals(2L, valid.quota?.total)
        } finally { server.shutdown() }
    }

    @Test
    fun setCookiePuusIsMergedAndSunkBack() = runBlocking {
        val server = serverWith(
            envelope(filesJson("f0"), "Set-Cookie: __puus=NEWPUUS; Path=/")
        )
        try {
            val api = QuarkApi(clientProvider = { mockClient(server) })
            var sunk: Pair<String, String>? = null
            api.cookieSink = { merged, requestCookie -> sunk = merged to requestCookie }
            api.listCloudFiles("0", "__pus=X; __puus=OLD")
            val merged = sunk?.first
            assertNotNull(merged)
            assertTrue(merged.contains("__puus=NEWPUUS"))
            assertTrue(merged.contains("__pus=X"))
            assertEquals("__pus=X; __puus=OLD", sunk?.second)
        } finally { server.shutdown() }
    }

    @Test
    fun listAllCloudFilesPaginatesDedupesAndStopsOnNoNewItems() = runBlocking {
        val server = serverWith(
            envelope(filesJson("f0", "f1")),          // 第 1 页：满页 → 继续
            envelope(filesJson("f1", "f2")),          // 第 2 页：1 重复 + 1 新增 → 继续
            envelope(filesJson("f0", "f2"))           // 第 3 页：全重复 → 停止
        )
        try {
            val api = QuarkApi(clientProvider = { mockClient(server) })
            val all = api.listAllCloudFiles("0", "c", pageSize = 2, maxPages = 5)
            assertEquals(listOf("f0", "f1", "f2"), all?.map { it.fid })
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun guestLinkCapturesPugsFromResponse() = runBlocking {
        val server = serverWith(
            envelope(
                """{"status":200,"code":0,"data":[{"fid":"f0","file_name":"a.mp4","download_url":"https://dl/x","size":9}]}""",
                "Set-Cookie: __pugs=PUGS123; Path=/"
            )
        )
        try {
            val api = QuarkApi(clientProvider = { mockClient(server) })
            val link = api.getGuestShareDownloadLink("f0", "tok", "share", "stoken")
            assertEquals("__pugs=PUGS123", link?.guestCookie)
            assertEquals("__pugs=PUGS123", api.guestPugs)
            assertEquals("https://dl/x", link?.downloadUrl)
        } finally { server.shutdown() }
    }

    @Test
    fun guestLinkErrorCodesUsePlatformMessageTables() = runBlocking {
        val server = serverWith(
            envelope("""{"status":4,"code":23018,"message":"denied"}"""),
            envelope("""{"status":4,"code":14001,"message":"x"}""")
        )
        try {
            val client = mockClient(server)
            val qe = assertFailsWith<PanApiException> {
                QuarkApi(clientProvider = { client }).getGuestShareDownloadLink("f", "t", "s", "st")
            }
            assertEquals(23018, qe.code)
            assertTrue(qe.message!!.contains("夸克实测约 50MB"))
            val ue = assertFailsWith<PanApiException> {
                UCApi(clientProvider = { client }).getGuestShareDownloadLink("f", "t", "s", "st")
            }
            assertEquals("分享已失效或提取码有误，请重新解析", ue.message)
        } finally { server.shutdown() }
    }

    @Test
    fun guestRequestsUsePerPlatformUaAndBodyFields() = runBlocking {
        val server = serverWith(
            envelope(filesJson("f0")),
            envelope(filesJson("f0"))
        )
        try {
            val client = mockClient(server)
            QuarkApi(clientProvider = { client }).getGuestShareDownloadLink("f0", "t", "s", "st")
            UCApi(clientProvider = { client }).getGuestShareDownloadLink("f0", "t", "s", "st")
            val quarkReq = server.takeRequest()
            assertEquals(QuarkConstants.API_USER_AGENT, quarkReq.getHeader("User-Agent"))
            assertNull(quarkReq.getHeader("Sec-Ch-Ua"))
            val quarkBody = JSONObject(quarkReq.body.readUtf8())
            assertTrue(quarkBody.has("speedup_session"), "夸克游客 body 应带 speedup_session 占位")

            val ucReq = server.takeRequest()
            assertEquals(UCConstants.GUEST_UA, ucReq.getHeader("User-Agent"))
            assertEquals(UCConstants.GUEST_SEC_CH_UA, ucReq.getHeader("Sec-Ch-Ua"))
        } finally { server.shutdown() }
    }
}
