package com.yunx.server

import com.yunx.app.AppContext
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.CredentialCheck
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * CloudDrives（平台操作面注册表）测试：
 * - 注册表对 SharePlatform 穷举覆盖 + 根目录约定
 * - QuarkDrive 经 MockWebServer 的校验/浏览行为（URL 重写拦截器，同 QuarkUcClientTest 样板）
 */
class CloudDrivesTest {

    private fun newStore(): Credentials =
        Credentials(File(Files.createTempDirectory("yunx-drives-test").toFile(), "accounts.enc"))

    @Test
    fun registryCoversEveryPlatformWithRootDirConventions() {
        AppContext.init()
        val drives = CloudDrives(newStore())
        // 穷举无 else：每个平台都必须拿到 adapter（新平台漏注册 = 编译失败，这里兜底运行时确认）
        SharePlatform.entries.forEach { p -> assertNotNull(drives.forPlatform(p), "$p 缺少 adapter") }
        assertEquals("0", drives.forPlatform(SharePlatform.QUARK).rootDir)
        assertEquals("/", drives.forPlatform(SharePlatform.BAIDU).rootDir)
        assertEquals("/", drives.forPlatform(SharePlatform.C139).rootDir)
        assertEquals("", drives.forPlatform(SharePlatform.XUNLEI).rootDir)
        assertEquals("-1", drives.forPlatform(SharePlatform.LANZOU).rootDir)
    }

    private fun mockClient(server: MockWebServer): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request().url
            val replacement = server.url(original.encodedPath)
                .newBuilder().encodedQuery(original.encodedQuery).build()
            chain.proceed(chain.request().newBuilder().url(replacement).build())
        }.build()

    @Test
    fun quarkDriveCredentialCheckReturnsNicknameAndQuota() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"success":true,"data":{"nickname":"测试昵称"}}"""))
            enqueue(MockResponse().setBody("""{"status":200,"data":{"use_capacity":1,"total_capacity":2}}"""))
            start()
        }
        try {
            val drive = QuarkDrive(QuarkApi(clientProvider = { mockClient(server) })) { "cookie-a" }
            val result = drive.credentialCheck()
            assertEquals("测试昵称", result.nickname)
            val valid = result.check as CredentialCheck.Valid
            assertEquals(1L, valid.quota?.used)
            assertEquals(2L, valid.quota?.total)
        } finally { server.shutdown() }
    }

    @Test
    fun quarkDriveListCloudDirMarksHasMoreAtFullPage() = runBlocking {
        fun listJson(n: Int) = """{"status":200,"data":{"list":[${
            (0 until n).joinToString(",") { i ->
                """{"fid":"f$i","file_name":"n$i","size":1,"dir":false,"pdir_fid":"0","updated_at":"t"}"""
            }
        }]}}"""
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody(listJson(2)))
            enqueue(MockResponse().setBody(listJson(100)))
            start()
        }
        try {
            val drive = QuarkDrive(QuarkApi(clientProvider = { mockClient(server) })) { "cookie-a" }
            val first = drive.listCloudDir("0", 1, null)
            assertEquals(2, first.files.size)
            assertFalse(first.hasMore)
            val full = drive.listCloudDir("0", 2, null)
            assertEquals(100, full.files.size)
            assertTrue(full.hasMore)
        } finally { server.shutdown() }
    }
}
