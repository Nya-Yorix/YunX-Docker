import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    application
}
version = file("version.txt").readText().trim()
require(version.toString().matches(Regex("""\d+\.\d+\.\d+"""))) { "Invalid server/version.txt" }

// ---- 版本号：根目录 version.txt（与 desktop 同源），生成 APP_VERSION 供 DownloadDebugLog 使用 ----
val versionLines = rootProject.file("version.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
val appVersion = versionLines.firstOrNull().orEmpty()
require(appVersion.matches(Regex("""\d+(\.\d+)*"""))) { "version.txt 内容非法: '$appVersion'" }
val appIsPreview = versionLines.drop(1).firstOrNull()?.let { line ->
    when (line.substringAfter('=', "").trim()) {
        "1" -> true
        "0" -> false
        else -> line.lowercase() in setOf("preview", "beta")
    }
} ?: false

val generateAppVersion by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/appVersion/kotlin")
    val version = appVersion
    val isPreview = appIsPreview.toString()
    outputs.dir(outDir)
    inputs.property("version", version)
    inputs.property("isPreview", isPreview)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("com/yunx/app")
        pkgDir.mkdirs()
        pkgDir.resolve("AppVersion.kt").writeText(
            """
            |package com.yunx.app
            |
            |/** 由根目录 version.txt 生成，请勿手改 */
            |internal const val APP_VERSION = "$version"
            |
            |/** 是否为预览版（version.txt 第二行 Beta=1） */
            |internal const val APP_IS_PREVIEW = $isPreview
            |""".trimMargin() + "\n"
        )
    }
}

// Compile the existing non-Compose core directly, so protocol fixes serve both editions.
kotlin.sourceSets["main"].kotlin.apply {
    srcDir("../desktop/src/main/kotlin")
    srcDir(generateAppVersion)
    include("com/yunx/server/**")
    include("com/yunx/app/AppContext.kt")
    include("com/yunx/app/AppVersion.kt")
    include("com/yunx/app/data/db/**", "com/yunx/app/data/security/**")
    include("com/yunx/app/data/backup/AuthCrypto.kt")
    include("com/yunx/app/data/network/**", "com/yunx/app/data/download/**")
    // 隐藏 WebView（JCEF/CEF）路线在无桌面环境不可用：排除 WebView 及引用它的解析器与注册表，
    // 纯 HTTP 解析器（B 站等）保留，server 侧自建注册表（见 VideoService）。
    exclude("com/yunx/app/data/network/video/HeadlessWebView.kt")
    exclude("com/yunx/app/data/network/video/DouyinParser.kt")
    exclude("com/yunx/app/data/network/video/VideoParsers.kt")
    include("com/yunx/app/data/repository/*ResolveRepository.kt")
    include("com/yunx/app/data/repository/TransferSpaceGuard.kt")
    include("com/yunx/app/data/repository/ILanzouAccountRepository.kt")
    include("com/yunx/app/data/gopeed/**", "com/yunx/app/data/prefs/**")
    include("com/yunx/app/util/Log.kt", "com/yunx/app/util/LogRedactor.kt")
    include("com/yunx/app/util/DiagnosticLog.kt", "com/yunx/app/util/DesktopActions.kt")
    include("com/yunx/app/util/WindowsToastNotifier.kt", "com/yunx/app/util/WindowsKeepAwake.kt")
    include("com/yunx/app/util/WindowsDownloadEvents.kt")
    include("com/yunx/app/util/WindowsTitleBar.kt", "com/yunx/app/util/WindowsFolderPicker.kt")
    include("com/yunx/app/util/WindowsFilePicker.kt")
}
kotlin.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
kotlin.sourceSets["test"].kotlin.apply {
    srcDir("../desktop/src/test/kotlin")
    include("com/yunx/server/**")
    include("com/yunx/app/data/download/DownloadPathPolicyTest.kt")
    include("com/yunx/app/data/download/HttpRangePolicyTest.kt")
    include("com/yunx/app/data/download/HlsRequestPolicyTest.kt")
    include("com/yunx/app/data/gopeed/**")
    include("com/yunx/app/data/network/ShareLinkParserTest.kt")
    include("com/yunx/app/data/network/BaiduFileManagementTest.kt")
    include("com/yunx/app/util/LogRedactorTest.kt")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.json:json:20240303")
    // DASH 合轨（DashMuxer 的 mp4parser 兜底路径）；isoparser 由 muxer 传递依赖
    implementation("org.mp4parser:muxer:1.9.56") {
        exclude(group = "junit", module = "junit")
    }
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
application {
    mainClass.set("com.yunx.server.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
        "-Djava.util.prefs.userRoot=/data/preferences",
        "-XX:MaxRAMPercentage=60.0"
    )
}
tasks.test {
    environment("YUNX_DESKTOP_DATA_DIR", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
}
tasks.processResources {
    from("../desktop/src/main/resources/icon.png") { into("web") }
}
