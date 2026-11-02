package com.openminis.app.forge

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** ModelTrace's MIT algorithm, ported without changing features, calibration or reference labels.
 * Source revisions and full notices ship in forge/information-sources.md and information-notices.txt.
 */
internal class ForgeInformationFingerprint(private val bank: JSONObject) {
    val modelIds: List<String> = bank.getJSONArray("models").objects().map { it.getString("id") }
    val builtAt: String = bank.getString("built_at")

    fun analyze(outputs: JSONArray): JSONObject {
        require(outputs.length() in 1..3) { "请提供 1 至 3 组数字回答" }
        val scores = mutableListOf<DoubleArray>()
        val counts = mutableListOf<DoubleArray>()
        val diagnostics = JSONArray()
        for (index in 0 until outputs.length()) {
            val item = outputs.optJSONObject(index) ?: throw IllegalArgumentException("数字样本格式无效")
            val text = item.opt("text") as? String ?: throw IllegalArgumentException("数字样本格式无效")
            val requested = item.opt("expected_count") as? Number ?: throw IllegalArgumentException("数字样本格式无效")
            require(text.length <= 50_000 && requested.toDouble().isFinite() &&
                requested.toDouble() == requested.toInt().toDouble() && requested.toInt() in 80..500) { "数字样本格式无效" }
            val numbers = parseNumbers(text)
            val minimum = max(80, ceil(requested.toDouble() * .55).toInt())
            val accepted = numbers.size >= minimum
            diagnostics.put(JSONObject().put("index", index).put("parsed_numbers", numbers.size)
                .put("minimum_numbers", minimum).put("accepted", accepted))
            if (accepted) {
                val frequency = frequency(numbers)
                counts += frequency
                scores += score(numbers, frequency)
            }
        }
        require(scores.isNotEmpty()) { "没有可用回答：请粘贴完整数字序列；拒答或严重截断的回答不会计入。" }
        val combined = DoubleArray(modelIds.size) { i -> scores.sumOf { it[i] } / scores.size }
        val calibrationKey = min(scores.size, 3).toString()
        val calibration = bank.getJSONObject("calibration").getJSONObject(calibrationKey)
        val beta = calibration.getDouble("beta")
        val logits = combined.map { it * beta }
        val weights = logits.map { exp(it - logits.max()) }
        val weightTotal = weights.sum()
        val pooled = DoubleArray(355) { i -> counts.sumOf { it[i] } }
        val models = bank.getJSONArray("models").objects()
        val results = models.mapIndexed { i, model ->
            JSONObject().put("model", model.getString("id"))
                .put("display_name", model.getString("display_name"))
                .put("probability", weights[i] / weightTotal).put("score", combined[i])
                .put("profile_similarity", similarity(pooled, model.getJSONArray("counts").doubles()))
                .put("family", model.optString("family", "models"))
                .put("family_name", model.optString("family_name", model.optString("family", "models")))
        }.sortedByDescending { it.getDouble("probability") }
        val families = models.map { it.optString("family", "models") }.distinct().map { family ->
            val members = results.filter { it.getString("family") == family }
            JSONObject().put("family", family).put("display_name", members.first().getString("family_name"))
                .put("probability", members.sumOf { it.getDouble("probability") })
        }
        results.forEach { result ->
            result.put("conditional_probability", result.getDouble("probability") /
                families.first { it.getString("family") == result.getString("family") }.getDouble("probability"))
        }
        val winner = families.maxBy { it.getDouble("probability") }
        return JSONObject().put("prediction", results.first().getString("model"))
            .put("prediction_name", results.first().getString("display_name"))
            .put("probability", results.first().getDouble("probability")).put("used_outputs", scores.size)
            .put("results", JSONArray(results)).put("diagnostics", diagnostics)
            .put("calibration", JSONObject().put("queries", calibrationKey).put("beta", beta)
                .put("cv_accuracy", calibration.get("cv_accuracy")))
            .put("family_prediction", winner.getString("family"))
            .put("family_prediction_name", winner.getString("display_name"))
            .put("family_probability", winner.getDouble("probability"))
            .put("family_probabilities", JSONArray(families)).put("method", "统一全局稳健数字指纹")
    }

    fun assess(expected: String, analysis: JSONObject?): JSONObject {
        val results = analysis?.optJSONArray("results")?.objects().orEmpty()
        val model = normalize(expected)
        val best = results.firstOrNull()
        if (best == null) return JSONObject().put("verdict", if (model.isNotEmpty() && model !in modelIds) "UNLISTED" else "NO_SAMPLE").put("warning", false)
        if (model.isEmpty()) return JSONObject().put("verdict", "UNKNOWN").put("warning", false)
        val declared = results.firstOrNull { normalize(it.getString("model")) == model }
            ?: return JSONObject().put("verdict", "UNLISTED").put("warning", false)
        val probability = best.getDouble("probability")
        val pExpected = declared.getDouble("probability")
        val margin = best.getDouble("score") - declared.getDouble("score")
        val matches = normalize(best.getString("model")) == model
        val strong = probability + 1e-9 >= .8 && pExpected <= .2 + 1e-9 && margin + 1e-9 >= .5 &&
            (analysis?.optInt("used_outputs") ?: 0) >= 2
        return JSONObject().put("verdict", if (matches) "MATCH" else if (strong) "MISMATCH" else "SUSPICIOUS")
            .put("warning", !matches).put("confidence", if (if (matches) probability >= .8 else strong) "high" else "low")
            .put("pExpected", pExpected).put("margin", margin)
    }

    private fun score(numbers: List<Int>, counts: DoubleArray): DoubleArray {
        val robust = bank.getJSONObject("robust")
        val artifact = robust.getJSONObject("hellinger")
        val total = counts.sum() + .5 * 355
        val feature = DoubleArray(355) { sqrt((counts[it] + .5) / total) }
        val marginal = standardize(centroidScores(unit(subtractBasis(transform(feature, artifact), artifact)), artifact))
        val ordered = robust.optJSONObject("ordered_blocks") ?: return marginal
        val weight = ordered.optDouble("weight", 0.0)
        if (weight == 0.0) return marginal
        val orderedFeature = transform(orderedFeature(numbers), ordered)
        val unprojected = unit(orderedFeature)
        val environments = ordered.getJSONArray("environment_centroids")
        val environmentScores = (0 until environments.length()).map { env ->
            environments.getJSONArray(env).arrays().map { dot(unprojected, it) }
        }
        val template = standardize(DoubleArray(modelIds.size) { i -> environmentScores.maxOf { it[i] } })
        val nuisance = standardize(centroidScores(unit(subtractBasis(orderedFeature, ordered)), ordered))
        val merged = standardize(DoubleArray(modelIds.size) { .5 * template[it] + .5 * nuisance[it] })
        return DoubleArray(modelIds.size) { (1 - weight) * marginal[it] + weight * merged[it] }
    }

    private fun transform(feature: DoubleArray, artifact: JSONObject): DoubleArray {
        val mean = artifact.getJSONArray("feature_mean").doubles()
        val scale = artifact.getJSONArray("feature_scale").doubles()
        return DoubleArray(feature.size) { (feature[it] - mean[it]) / scale[it] }
    }

    private fun subtractBasis(values: DoubleArray, artifact: JSONObject): DoubleArray = values.copyOf().also { out ->
        artifact.optJSONArray("nuisance_basis")?.arrays()?.forEach { vector ->
            val projection = dot(out, vector)
            out.indices.forEach { out[it] -= projection * vector[it] }
        }
    }

    private fun centroidScores(values: DoubleArray, artifact: JSONObject) = artifact.getJSONArray("centroids").arrays()
        .map { dot(values, it) }.toDoubleArray()

    private fun orderedFeature(numbers: List<Int>): DoubleArray {
        val pieces = mutableListOf<Double>()
        val base = numbers.size / 4
        val remainder = numbers.size % 4
        var start = 0
        repeat(4) { i ->
            val size = base + if (i < remainder) 1 else 0
            val bins = DoubleArray(16) { .5 }
            numbers.subList(start, start + size).forEach { value -> bins[min(15, floor((value - 1) / 355.0 * 16).toInt())]++ }
            val total = bins.sum()
            pieces += bins.map { sqrt(it / total) }
            start += size
        }
        val digits = DoubleArray(10) { .5 }
        numbers.forEach { digits[it % 10]++ }
        val total = digits.sum()
        pieces += digits.map { sqrt(it / total) }
        return pieces.toDoubleArray()
    }

    private fun similarity(left: DoubleArray, right: DoubleArray): Double {
        val leftTotal = left.sum()
        val rightTotal = right.sum() + .5 * 355
        val p = left.map { it / leftTotal }
        val q = right.map { (it + .5) / rightTotal }
        val mid = p.indices.map { (p[it] + q[it]) / 2 }
        fun divergence(values: List<Double>) = values.indices.sumOf { i -> if (values[i] == 0.0) 0.0 else values[i] * ln(values[i] / mid[i]) }
        return 1 - sqrt((divergence(p) + divergence(q)) / 2 / ln(2.0))
    }

    companion object {
        const val TTL_MS = 24 * 60 * 60 * 1000L
        fun normalize(value: String) = value.trim().lowercase(java.util.Locale.ROOT).substringAfterLast('/')
            .replace('_', '-').replace(' ', '-').replace(Regex("^gpt(?=\\d)"), "gpt-")
        fun stale(createdAt: Long, now: Long) = now - createdAt > TTL_MS || createdAt > now + 60_000

        fun parseNumbers(text: String): List<Int> {
            var current = mutableListOf<Int>()
            var best: List<Int> = emptyList()
            var previousEnd = 0
            Regex("\\d+").findAll(text).forEach { match ->
                if (current.isNotEmpty() && text.substring(previousEnd, match.range.first).codePoints().anyMatch { Character.isLetter(it) }) {
                    if (current.size > best.size) best = current
                    current = mutableListOf()
                }
                match.value.toIntOrNull()?.takeIf { it in 1..355 }?.let(current::add)
                previousEnd = match.range.last + 1
            }
            return if (current.size > best.size) current else best
        }

        private fun frequency(numbers: List<Int>) = DoubleArray(355).also { out -> numbers.forEach { out[it - 1]++ } }
        private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
        private fun unit(values: DoubleArray): DoubleArray = sqrt(dot(values, values)).coerceAtLeast(1e-12).let { scale -> values.map { it / scale }.toDoubleArray() }
        private fun standardize(values: DoubleArray): DoubleArray {
            val center = values.average()
            val scale = sqrt(values.map { (it - center).pow(2) }.average()).coerceAtLeast(1e-12)
            return values.map { (it - center) / scale }.toDoubleArray()
        }
        private fun JSONArray.doubles() = DoubleArray(length()) { getDouble(it) }
        private fun JSONArray.arrays() = (0 until length()).map { getJSONArray(it).doubles() }
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.nullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
