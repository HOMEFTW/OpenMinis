# Forge 面板启动与 GPT 高速模式修复

## 范围

用户反馈原生 Forge 列表正常、四个插件进入后白屏，并要求高速模式仅适用于 GPT，切换到其他模型时关闭。本次保持 1.17.0（32），交付本地测试 APK，不提交或替换 GitHub Release。

## Forge

- 原实现直接在 Compose `AndroidView.factory` 中调用 `loadUrl`，此时 WebView 尚未挂载或取得有效尺寸。现在等待挂载且宽高均大于零再加载，只启动一次；离开页面时移除等待回调。启用 viewport 配置，与项目现有网页预览对 100vh／百分比高度页面的处理一致。
- 增加原生标题和返回入口、加载指示、启动超时、关键资源失败及渲染进程退出提示；重试重建页面。销毁后的网页不会再收到旧异步请求的回调。
- 前端在主 bundle 之前加载兼容性启动脚本。脚本加载／语法错误、启动超时和 React 渲染失败会显示错误与重试，避免空白页。启动错误不回传异常中的私人内容。
- 插件列表直接说明目前只支持四个内置插件，不支持第三方插件安装。此次没有新增安装器；桌面 Forge 的 Node/Electron 插件不能直接安装到 Android。

没有获得用户手机的 WebView 版本或运行日志，也没有连接手机复现原始白屏。以上修复针对已发现的加载时机缺陷，并补齐失败诊断；不能用桌面浏览器测试替代该手机的最终验收。

## 高速模式

- 仅 GPT 文本模型的 OpenAI Chat／Responses 路由可启用高速模式，Grok／DeepSeek／Claude 等模型不显示高速开关或闪电状态。
- 使用当前模型统一 setter 处理手动选择、恢复、默认模型、配置刷新和模型组回退；重新打开缓存会话时再核对。前台选择非 GPT 时保存 `enabled=false`、`speedTier=standard`，切回 GPT 不自动恢复。
- 后台旧会话的模型刷新不能清除前台 GPT 设置；未解析完成的模型不误清除。
- 删除 xAI Priority 分支；构造 Chat／Responses 请求后再次检查模型与路由资格，非 GPT 自定义参数也不能重新引入 `service_tier`。

## 验证

- 使用 `scripts/build_android_windows.ps1 -TestFilter ... -BuildAndroidTests` 验证 Forge 与高速模式相关类。首次 89 项中 88 项通过；新增布局测试因 Robolectric 的 WebView provider 不更新布局尺寸而失败。改用真实 Android View 挂载／布局回调验证启动门槛后，两项布局测试均通过。合并相关最终结果：**17 套件、89 项 JVM 测试通过，0 失败／错误／跳过**。
- `src/android/forge-ui` 的 `npm test`：**5 项通过**，覆盖就绪、脚本资源失败、语法错误、超时和渲染错误。
- Playwright 使用实际打包 HTML/CSP/JS/CSS、HTTPS 虚拟源、异步原生桥模拟和禁用 DOM 存储，360×740 下四个页面均显示，无横向溢出和脚本错误；模拟 bundle 返回 503 后错误可见，重试恢复。数据为隔离桌面夹具和合成统计，不代表 Android 服务或真实模型验证。
- 最终再次通过统一脚本构建 APK。ZIP CRC、重复条目、v2 签名、16 KiB 原生库／4 字节 ZIP 对齐、Forge 启动脚本和前端包内字节检查通过；沿用已发布 APK 签名。
- 无 Android 设备，未执行真机安装、WebView 触控、真实模型／OAuth／网络服务联调。仪器测试包只编译、未执行。未重跑无关全量测试。

本地 APK：`D:/Code/.tools/openminis-1.17.0-forge-fix/OpenMinis-1.17.0-forge-fast-fix.apk`。

- 版本：1.17.0（32），Android 8.0+，ARM64。
- 大小：79,356,584 字节。
- SHA-256：`a288e66e36d46f4872a04c71fc6e8f3a85e9ca724e5b10ede26af2025d02c449`。
- 原始记录位于 `D:/Code/.tools/openminis-1.17.0-forge-fix/`。

此 APK 与已发布 1.17.0 版本号相同，需手动安装本地修复包。子代理、浏览器测试会话和本轮预览服务已关闭。
