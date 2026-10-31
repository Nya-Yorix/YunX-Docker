package com.yunx.app.data.network

/**
 * 网盘 API 业务异常：携带服务端返回的 message 与 code 字段，
 * 用于把具体错误原因（如「提取码错误」「分享已失效」「file not found」）透传给 UI；
 * code 供上层识别特定错误（如 21001 触发兜底重转）。
 *
 * 虽名为「夸克」，它早已是全平台事实标准（UC / 迅雷同样抛出；CredentialClassifier 按消息归类，
 * 无按类型 catch 之处）。新代码请用别名 [PanApiException]，原名保留以兼容存量与上游 graft。
 */
class QuarkApiException(message: String, val code: Int = 0) : Exception(message)

/** [QuarkApiException] 的正名：网盘 API 业务异常（同一个类，仅改名可读） */
typealias PanApiException = QuarkApiException