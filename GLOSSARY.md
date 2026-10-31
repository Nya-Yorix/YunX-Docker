# 领域词汇表（GLOSSARY）

本仓库讨论架构与写文档时的用词基准。架构方法论词汇（module / interface / seam / deep / shallow / adapter / locality / leverage）沿用 codebase-design 惯例，此处只收录**本项目的领域与模块名词**。

## 领域概念

| 词汇 | 含义 |
| --- | --- |
| 平台（SharePlatform） | 一个网盘/分享来源：夸克、UC、迅雷、百度、139、123、115、光鸭、蓝奏、蓝奏优享、GitHub。枚举定义于 `ShareLinkParser.kt`，下载侧另有对应字符串常量（`DownloadPlatform`） |
| 分享解析 | 把分享链接解析为可浏览/可下载的文件列表（桌面与网页共用同一浏览层 `ShareResolveRepository`） |
| 个人云盘 / 网盘浏览 | 已登录账号自己的目录树浏览（区别于分享解析），网页端入口 `/api/cloud/files` |
| 转存 | 把分享文件保存进自己网盘（临时转存目录 `tr_*` 供下载后清理，见各 ResolveRepository） |
| 凭证（Credentials） | 平台登录态：cookie / accessToken+deviceId+captchaToken / GuangYaDevice / uuid 等，按平台形态各异；网页端加密存 `server-credentials.enc`，桌面端加密存 Room |
| 三态校验（CredentialCheck） | 凭证校验结果三分：Valid / Invalid（明确登录失败）/ Unknown（网络等暂时性失败，绝不误判为登录失效）。平台失效码集中表见 `CredentialCheck.kt` 的 `AUTH_PATTERNS` |
| 会话 Cookie（__puus/__pus） | 夸克/UC 的会话签名 Cookie，约 3 小时过期，取链直链的关键字段；响应 Set-Cookie 合并回写（cookieSink → 凭证库） |
| 游客令牌（__pugs） | 未登录取链时服务端下发的临时 Cookie，与本次直链同响应绑定；缺了 CDN 直接 412/403 |

## 模块名词（本次架构收敛引入）

| 模块 | 位置 | 职责（一句话） |
| --- | --- | --- |
| 请求骨架 / envelope client（QuarkUcClient） | `desktop/.../data/network/QuarkUcClient.kt` | 夸克/UC 共用的请求执行 + 信封拆包（status/message/data）+ __puus 合并 + __pugs 捕获 + 任务轮询 + 翻页去重；平台差异 = QuarkUcEndpoints + 少量 open 钩子 |
| 平台操作面（CloudDriveOps） | `server/.../CloudDrives.kt` | 每平台一个 adapter 的统一 interface：账号三态校验 / 个人云盘浏览 / 个人云盘取链；凭证来源以 lambda 在装配时闭包注入，接口方法不带凭证参数 |
| 平台注册表（CloudDrives） | `server/.../CloudDrives.kt` | SharePlatform → adapter 的唯一分发点；**对枚举穷举且无 else**，新增平台漏写 adapter 直接编译失败。扩平台 = 在这里加一行 + 一个 adapter 类 |

## 约定

- 新增网盘平台的接入点清单见 `docs/PORTING.md` 与 `AccountDirectory.kt` 头注释；server 侧只需 CloudDrives 一个注册点。
- 错误文案里的平台名（「夸克」/「UC」）与失效码表属平台知识，不要在调用方拼写。
