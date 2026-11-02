package com.openminis.app.forge

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.openminis.app.MinisApp
import com.openminis.app.util.launchSafely
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgeScreen(sessionId: String? = null, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as MinisApp
    val host = remember(app) { app.forgeHost }
    var selected by remember(sessionId) { mutableStateOf<ForgeHost.Plugin?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val plugin = selected
    BackHandler(enabled = plugin == null, onBack = onBack)
    if (plugin != null) {
        key(plugin.id, sessionId) { ForgeWebPanel(host, plugin, sessionId) { selected = null } }
        return
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Forge 插件") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(if (sessionId == null) "在设备上运行，数据保存在本机" else "当前会话的画布、统计、技能与待办", style = MaterialTheme.typography.bodyMedium) }
            items(ForgeHost.plugins.size) { index ->
                val p = ForgeHost.plugins[index]
                val enabled = remember(p.id, revision) { host.enabled(p.id) }
                Card(Modifier.fillMaxWidth().clickable(enabled = enabled) { selected = p }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        val icon = when (p.page) { "canvas" -> Icons.Outlined.AccountTree; "information-stats" -> Icons.Outlined.Analytics; "todos" -> Icons.Outlined.Checklist; else -> Icons.Outlined.Extension }
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(p.title, style = MaterialTheme.typography.titleMedium); Text(p.description, style = MaterialTheme.typography.bodySmall) }
                        Switch(enabled, onCheckedChange = { host.setEnabled(p.id, it); revision++ })
                    }
                }
            }
            item { Text("技能默认停用，可在 Codeg 技能包中选择。关闭插件不会删除已保存的数据。", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ForgeWebPanel(host: ForgeHost, plugin: ForgeHost.Plugin, sessionId: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alive = remember { AtomicBoolean(true) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> choosing?.complete(uri); choosing = null }
    fun emit(event: JSONObject) { if (alive.get()) web?.evaluateJavascript("window.forgeAndroidReply && window.forgeAndroidReply(${JSONObject().put("event", event)});", null) }
    suspend fun attachment(args: JSONObject): Any? {
        check(choosing == null) { "文件选择器已打开" }
        val kind = args.getString("kind"); require(kind in setOf("image", "file"))
        val pending = CompletableDeferred<Uri?>(); choosing = pending
        val uri = try {
            if (!picker.launchSafely(context, if (kind == "image") arrayOf("image/*") else arrayOf("*/*"))) return null
            pending.await()
        } finally { if (choosing === pending) choosing = null }
        if (uri == null) return null
        return withContext(Dispatchers.IO) {
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "附件" } ?: "附件"
            requireNotNull(context.contentResolver.openInputStream(uri)).use { host.importAttachment(it, name, kind) }
        }
    }
    suspend fun sdk(args: JSONObject): Any? {
        val method = args.getString("method"); val data = args.optJSONObject("args") ?: JSONObject()
        ForgeApiAccess.denial(host.manifest(plugin.id), method, data.optString("surface").takeIf { it.isNotBlank() })?.let { error(it) }
        return when (method) {
            "panels.open", "panels.close" -> {
                require(data.getString("id") == "main") { "Unknown panel" }
                if (method == "panels.close") onClose()
                true
            }
            "browser.open" -> {
                val uri = Uri.parse(data.getString("url")); require(uri.scheme in setOf("https", "http") && uri.host != null)
                context.startActivity(Intent(Intent.ACTION_VIEW, uri)); JSONObject().put("opened", uri.toString())
            }
            "ui.register", "ui.unregister" -> {
                val surface = data.getString("surface"); require(surface in setOf("sidebar", "toolbar", "composer"))
                val item = if (method == "ui.register") data.getJSONObject("item") else JSONObject().put("id", data.getString("id"))
                ForgeStorage.id(item.getString("id"))
                if (method == "ui.register") {
                    require(item.optString("title").length in 1..100)
                    require(item.optString("panel") == "main" || host.manifest(plugin.id).optJSONObject("contributes")?.optJSONArray("commands")?.let { commands -> (0 until commands.length()).any { commands.getJSONObject(it).optString("id") == item.optString("command") } } == true)
                }
                emit(JSONObject().put("type", "ui-contribution").put("remove", method == "ui.unregister").put("surface", surface).put("item", item)); true
            }
            else -> host.sdk(plugin, method, data, sessionId).also { result ->
                if (method.startsWith("output.")) emit(JSONObject().put("type", "sdk-output").put("value", result))
            }
        }
    }
    BackHandler { emit(JSONObject().put("type", "close-request")) }
    LaunchedEffect(host, plugin.id) {
        host.events.collect { event ->
            if (event.optString("type") == "plugin-state" && event.optString("pluginId") == plugin.id && !event.optBoolean("enabled")) onClose()
            else if (event.optString("pluginId").let { it.isEmpty() || it == plugin.id }) emit(event)
        }
    }
    DisposableEffect(Unit) { onDispose { alive.set(false); choosing?.cancel(); web?.apply { stopLoading(); destroy() }; web = null } }
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        Column(Modifier.fillMaxSize().padding(24.dp)) { Text("请更新 Android System WebView 后使用 Forge 插件"); TextButton(onClick = onClose) { Text("返回") } }
        return
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        error?.let { message -> Row { Text(message, Modifier.weight(1f)); TextButton(onClick = onClose) { Text("返回") } } }
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = {
            val loader = WebViewAssetLoader.Builder().setDomain("forge.openminis.local")
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
            WebView(context).apply {
                web = this
                settings.javaScriptEnabled = true; settings.domStorageEnabled = false
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.toString() != "https://forge.openminis.local/assets/forge/ui/index.html"
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: android.webkit.WebResourceError) { if (request.isForMainFrame) error = failure.description.toString() }
                }
                WebViewCompat.addWebMessageListener(this, "ForgeAndroid", setOf("https://forge.openminis.local")) { _, message, origin, mainFrame, _ ->
                    if (!mainFrame || origin.toString() != "https://forge.openminis.local" || !alive.get()) return@addWebMessageListener
                    val raw = message.data ?: return@addWebMessageListener
                    if (raw.length > 13 * 1024 * 1024) return@addWebMessageListener
                    val request = runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
                    val id = request.optLong("id", -1); if (id < 0) return@addWebMessageListener
                    scope.launch {
                        val response = JSONObject().put("id", id)
                        try {
                            check(host.enabled(plugin.id)) { "此 Forge 插件已停用" }
                            val method = request.getString("method"); val args = request.optJSONObject("args") ?: JSONObject()
                            val result = when (method) {
                                "close" -> { onClose(); true }
                                "sdk" -> sdk(args)
                                "attach" -> { require(plugin.page == "canvas"); attachment(args) }
                                "reveal" -> {
                                    require(plugin.page == "canvas")
                                    val doc = withContext(Dispatchers.IO) { host.canvas.read(args.getString("canvasId")) }
                                    val nodes = doc.getJSONArray("nodes")
                                    val node = (0 until nodes.length()).map { nodes.getJSONObject(it) }.first { it.getString("id") == args.getString("nodeId") }
                                    val file = host.attachmentFile(node.getJSONObject("data").getString("filePath"))
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); true
                                }
                                else -> host.request(plugin, method, args, sessionId)
                            }
                            response.put("result", result ?: JSONObject.NULL)
                        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (failure: Exception) { response.put("error", failure.message ?: "操作失败") }
                        if (alive.get()) evaluateJavascript("window.forgeAndroidReply && window.forgeAndroidReply($response);", null)
                    }
                }
                loadUrl("https://forge.openminis.local/assets/forge/ui/index.html")
            }
        })
    }
}
