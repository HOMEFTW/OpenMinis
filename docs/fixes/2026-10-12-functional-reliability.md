# 功能缺陷修复与验收

基线：main / dbc541d7cfe83736299390dfb8588a9acaea3aa2，Android 1.16（28）。
依据：2026-10-12 功能审查的 F01–F22 及 R01。用户已授权全部修复。

## 分工与依赖

主代理独占 ChatViewModel、ProviderRepository/Factory、思考档位接口、会话并发/活跃状态、定时任务交接、更新检查和仪器测试接口修复。
数据子代理负责备份、会话资源复制、分享和通信清理；工具子代理负责文件/MCP/浏览器/终端及 Anthropic/Gemini 非流式取消。
子代理不修改 ChatViewModel；需要接入点时提供具体 API 和代码位置，由主代理集成。
各代理只修改本任务文件；不提交/推送、不变更版本、不改用户已有 .codegraph/。

## 验收清单

| 编号 | 目标 | 负责 |
| --- | --- | --- |
| F01 | 更新已有会话不级联删除本地消息/压缩标记，取消恢复不破坏原数据 | 数据 |
| F02 | 消息备份保留更新时间，旧格式/旧消息合并保留较新本地正文 | 数据 |
| F03 | 备份/恢复包含历史图片、文本和分支 media 引用 | 数据 |
| F04 | 非法 blob 摘要、包外源或链接被拒绝 | 数据 |
| F05 | 会话副本资源独立，删除源不损坏副本 | 数据 |
| F06 | 草稿迁移失败保留未迁移文件，并可重试 | 主代理 |
| F07 | MCP 非幂等工具结果丢失不透明重发 | 工具 |
| F08 | 同文件编辑/写入有一致协调，成功修改不互相覆盖 | 工具 |
| F09 | 并发准入/取消/交接不泄漏、不超额，保持 FIFO | 主代理 |
| F10 | 并发活跃状态不丢更新，保活/完成决策一致 | 主代理 |
| F11 | 下一请求使用最新 Key/端点/协议/超时/overrides，保留在途快照 | 主代理 |
| F12 | 历史/切模型/组默认/回退/定时请求遵守条目思考上限 | 主代理 |
| F13 | Anthropic/Gemini 非流式请求取消关闭 HTTP Call | 工具 |
| F14 | 文件窗口读取内存有界，包括 tail 与超长行 | 工具 |
| F15 | 浏览器过期回调不能完成新的 JS/fetch 请求 | 工具 |
| F16 | 终端兼容回退进入可重启状态，不被旧进程覆盖 | 工具 |
| F17 | MCP 正确组装 SSE 事件，按 request ID 选择结果 | 工具 |
| F18 | 编辑分支历史附件路径可用且不依赖源资源生命周期 | 数据/主代理集成 |
| F19 | 分享持久化到消费/草稿确认，进程重启可恢复未消费内容 | 数据 |
| F20 | 已删除会话的遗留终态通信记录不堵塞邮箱 | 数据 |
| F21 | 更新/下载使用 HOMEFTW/OpenMinis | 主代理 |
| F22 | 使用实际版本比较器选择最高候选 | 主代理 |
| R01 | 定时任务交接/排队保活令牌覆盖到运行接管或明确失败 | 主代理 |
| 测试设施 | 仪器测试适配逐命令挂载、会话所有权及取消接口，编译 Android 测试包 | 主代理 |

## 实施及验证顺序

1. 复用审查复现，补入能验证实际行为的针对性回归测试。
2. 三个文件边界并行实施；有共享接入点时由主代理统一集成。
3. 审阅集成差异，核对上表全部编号和失败/取消/重启边界。
4. 使用 scripts/build_android_windows.ps1 -RunAllTests -BuildAndroidTests 完成全套 JVM 测试、APK 和仪器测试包编译。
5. 运行 MCP Python 回归与适当的受限内存/并发探针；Android 设备可用时执行对应仪器测试，否则如实说明未运行。

## 实际结果

截至 2026-10-12，F01–F22、R01 及旧仪器测试接口修复均已实现并完成本地集成验证。两个子代理已交接并停止写入。修复阶段基于 1.16 / versionCode 28，本次发行升级为 1.16.1 / versionCode 29；用户原有 `.codegraph/` 保留，未纳入提交。

### 修改范围

- 数据恢复采用会话 Upsert 和聊天数据库合并事务，取消/异常回滚；备份保存精确消息更新时间，旧包及过期消息不覆盖较新本地消息。
- 新备份包含嵌套历史媒体引用；拒绝非法 blob 摘要、包外源和链接。会话副本、编辑分支独立复制工作区、附件、offload 和媒体，源删除后副本仍可用。
- 草稿迁移失败保留源文件并支持重试；分享记录在草稿同步保存成功后才确认消费；回收双方已删除会话的终态通信记录。
- 并发准入、取消及交接串行协调，保证 FIFO、最多五个运行名额及异常释放；活跃状态和定时任务持久队列保活覆盖排队到终态。
- Provider 使用每请求配置快照，后续请求刷新 Key、端点及配置；发送入口与 UI 执行条目思考上限，无法满足的档位组合在发送前明确报错。
- MCP 副作用结果丢失返回 `OUTCOME_UNKNOWN`，避免透明重发；SSE 按事件组装并按请求 ID 筛选。文件编辑和写入共用路径锁，文件 head/tail 读取限制内存使用，包括超长单行。
- 浏览器请求与 PTY 生命周期按 token 隔离，迟到回调不影响后续请求；Anthropic/Gemini 非流式 HTTP 取消关闭 Call。
- 更新检查及下载使用 HOMEFTW/OpenMinis，并按实际版本比较器选择最高版本。仪器测试更新为逐命令挂载、工作区所有权、并发隔离及会话取消断言。

集成时同步更新三类旧测试：备份兼容性测试允许新增 `updatedAt` 并通过真实 Mapper 验证往返；删除绑定旧取消补偿源码字符串的失效断言，以真实 Room 仪器测试覆盖 Upsert 和取消/异常回滚；分支媒体 fixture 使用实际应用目录结构，保留删除源后副本仍可读取的断言。Room 仪器测试已编译，尚未在设备执行。

### 验证结果

正式构建与全套 JVM 测试使用项目脚本：

```powershell
.\scripts\build_android_windows.ps1 -RunAllTests -BuildAndroidTests
```

- 306 个测试套件、2480 项：2478 通过、0 失败、0 错误、2 跳过。跳过项是 SessionStorageTest 的 POSIX 权限测试，当前 Windows 文件系统不提供该属性视图。
- 应用 APK 和 Android 仪器测试 APK 均编译成功。
- 日志：`D:/Code/.tools/openminis-functional-fixes-build-20261012-verified.log`。

最后仅调整仪器测试对 rootfs 归档及 native lib 的资源检测，随后执行同一脚本的 `-BuildAndroidTests`，再次 `BUILD SUCCESSFUL`（21 秒）。生产代码未变化，复用上述全套 JVM 结果。

- 日志：`D:/Code/.tools/openminis-functional-fixes-instrumentation-20261012.log`。
- MCP Python 回归 32 项全部通过，使用本地 fixture/mock，未访问真实模型服务。日志：`D:/Code/.tools/openminis-functional-provider-probes-20261012/fix-python-results.log`。
- `-Xmx64m` 下读取约 96 MiB 多行及单行文件的 head/tail 均成功。日志：`D:/Code/.tools/openminis-functional-provider-probes-20261012/fix-bounded-heap-results.log`。
- 数据离线定向测试 34/34、备份测试 60/60、工具定向 JUnit 21/21 通过；这些组有交集，不与全套结果累加。
- `git -c core.safecrlf=false diff --check` 通过。

升级为 1.16.1 后，再次执行 `scripts/build_android_windows.ps1 -RunAllTests -BuildAndroidTests`：`BUILD SUCCESSFUL`（2 分 28 秒），306 个套件、2480 项测试，2478 通过、0 失败、0 错误、2 跳过；应用及完整仪器测试包均编译成功。发布构建日志：`D:/Code/.tools/openminis-1.16.1-build-20261012.log`。升级只改变版本配置和文档，复用上述 MCP 与受限堆探针结果。

### 最终产物

应用 APK：`D:/Code/OpenMinis/src/android/app/build/outputs/apk/debug/app-debug.apk`。

- 67,382,333 字节；包名 `com.openminis.app`；1.16.1（29）；minSdk 26、targetSdk 35；ARM64。
- SHA256：`0d9be825204335d020482f893998b4f6eea4ebaf3894ddd71a7cb6e6acc036b2`。
- APK v2 签名、ZIP CRC、20 个 DEX 的校验和/SHA1、ZIP alignment（`-c -P 16 4`）均通过；包内包含 libproot.so 和 rootfs 归档，MCP 源码与工作区逐字节一致。
- 签名证书 SHA256 与上一版相同：`e3536fc5f03bd72e1bc8073b306659cb4edc6b401fb4152048c403c347c08273`；44 个品牌/启动图标资源与已发布 1.16 修复包逐字节一致。

仪器测试 APK：`D:/Code/OpenMinis/src/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。

- 582,150 字节；SHA256：`6a4c90c95e59965659746a972e6f1e864b6c90418a8c0f3dc773d1761941043a`。
- APK v2 签名、ZIP CRC、9 个 DEX 的校验和/SHA1 均通过。

### 验证边界

当前没有连接 ADB 设备，未执行真机安装或仪器测试；真实 Room 运行、WebView、JNI/PTY、进程重启后的分享流程及 Doze 保活仍需设备验证。未调用真实模型 API，离线测试不作为这些运行行为已经通过的证明。

聊天数据库合并事务可回滚，但文件恢复不承诺数据库与文件系统的全局原子性。旧备份原本未包含的唯一媒体字节无法凭引用重建。文件窗口读取内存有界，扫描仍为 O(file size)，也不提供外部进程并发写入时的原子文件快照。
