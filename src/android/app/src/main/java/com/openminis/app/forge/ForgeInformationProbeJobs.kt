package com.openminis.app.forge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One explicit active probe across sessions. Results are pinned to the original context, never the visible chat. */
internal class ForgeInformationProbeJobs(private val scope: CoroutineScope) {
    private class Probe(val context: ForgeInformationContext) {
        var status = "running"
        var completed = 0
        var error = ""
        var job: Job? = null
    }
    private val probes = linkedMapOf<String, Probe>()

    @Synchronized fun start(
        context: ForgeInformationContext,
        stillCurrent: () -> Boolean,
        run: suspend ((Int) -> Unit) -> JSONArray,
        save: (JSONArray) -> Unit,
    ) {
        require(probes.values.none { it.status == "running" }) { "已有模型检测在运行，请稍候" }
        val probe = Probe(context)
        probes[context.sessionId] = probe
        while (probes.size > 32) probes.remove(probes.keys.first())
        probe.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                check(stillCurrent()) { "当前模型设置已变化，已停止检测" }
                val outputs = run { count -> synchronized(this@ForgeInformationProbeJobs) {
                    if (probe.status == "running") probe.completed = count.coerceIn(0, 3)
                } }
                coroutineContext.ensureActive()
                synchronized(this@ForgeInformationProbeJobs) {
                    // Cancel and save share this lock. A cancelled or superseded probe cannot commit a result.
                    if (probe.status != "running") throw CancellationException("检测已取消")
                    check(stillCurrent()) { "当前模型设置已变化，未保存此检测" }
                    save(outputs)
                    probe.status = "completed"
                }
            } catch (failure: CancellationException) {
                synchronized(this@ForgeInformationProbeJobs) { probe.status = "cancelled" }
            } catch (_: Exception) {
                // Provider error bodies may contain a URL or request metadata; do not persist/expose them.
                synchronized(this@ForgeInformationProbeJobs) {
                    if (probe.status == "running") { probe.status = "failed"; probe.error = "请求失败、设置已变化或回答无效；请检查提供商后重试" }
                }
            }
        }
        probe.job!!.start()
    }

    @Synchronized fun cancel(sessionId: String) {
        probes[sessionId]?.takeIf { it.status == "running" }?.let { it.status = "cancelled"; it.job?.cancel() }
    }

    @Synchronized fun snapshot(context: ForgeInformationContext): JSONObject? = probes[context.sessionId]
        ?.takeIf { it.context == context }?.let { JSONObject().put("status", it.status).put("completed", it.completed).put("total", 3).put("error", it.error) }

    @Synchronized fun stop() { probes.keys.toList().forEach(::cancel) }
}
