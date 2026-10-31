package com.dreamkeeper.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 「这家 Key 到底能用哪些模型」——按各家 OpenAI 兼容的 `GET /models` 探测。
 *
 * 为什么要有它：静态清单会过期（供应商上月线了下月就 404），而「Key 能不能用」本身
 * 只能由服务端回答。探测失败不代表 Key 坏——很多兼容端点不实现 `/models`，
 * 所以这里的失败一律降级为「用内置清单」，绝不当成鉴权错误。
 *
 * 约束：
 * - 单次请求、无重试（探测是用户主动点的，重试只会让按钮转更久）；
 * - 10s 硬超时，不进 generationScope/transcriptionScope；
 * - 不写库，只把结果交给调用方存进偏好；
 * - 日志不含 Key。
 */
interface ModelProbe {
    suspend fun list(provider: ProviderId, apiKey: String): ModelDiscovery.Result
}

class ModelDiscovery(
    private val client: OkHttpClient = defaultClient(),
    private val connectTimeoutMs: Long = 10_000,
) : ModelProbe {

    /** 探测结果：成功给模型 id 列表；失败给一句人话，调用方保留内置清单。 */
    data class Result(val models: List<String>, val error: String? = null) {
        val ok: Boolean get() = error == null && models.isNotEmpty()
    }

    override suspend fun list(provider: ProviderId, apiKey: String): ModelDiscovery.Result =
        list(provider, apiKey, baseUrlOverride = null)

    suspend fun list(provider: ProviderId, apiKey: String, baseUrlOverride: String?): ModelDiscovery.Result {
        if (apiKey.isBlank()) return Result(emptyList(), "还没有配置 Key")
        val spec = ProviderRegistry.of(provider)
        val url = (baseUrlOverride ?: spec.baseUrl).trimEnd('/') + "/models"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        val http = client.newBuilder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(connectTimeoutMs * 2, TimeUnit.MILLISECONDS)
            .build()
        val call = http.newCall(request)
        val response = suspendCancellableCoroutine<Response?> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        } ?: return Result(emptyList(), "网络没通，连不上 ${provider.displayName}")

        response.use { r ->
            if (!r.isSuccessful) {
                return Result(emptyList(), "${provider.displayName} 不支持模型列表（HTTP ${r.code}），已用内置清单")
            }
            val body = r.body?.string().orEmpty()
            val ids = parse(body)
            if (ids.isEmpty()) return Result(emptyList(), "返回里没有认得出模型 id，已用内置清单")
            return Result(ids)
        }
    }

    /**
     * 兼容四种常见形状：
     * - OpenAI：`{"data":[{"id":"..."}]}`
     * - 部分网关：`{"data":[{"model":"..."}]}`
     * - 智谱风格：`{"data":[{"name":"..."}]}`
     * - 简化版：`{"models":["..."]}`
     */
    internal fun parse(body: String): List<String> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        root.optJSONArray("data")?.let { arr -> collect(arr, out) }
        root.optJSONArray("models")?.let { arr -> collect(arr, out) }
        return out.toList().sorted()
    }

    private fun collect(arr: JSONArray, out: MutableSet<String>) {
        for (i in 0 until arr.length()) {
            when (val item = arr.opt(i)) {
                is JSONObject -> {
                    val id = item.optString("id").ifBlank {
                        item.optString("model").ifBlank { item.optString("name") }
                    }
                    if (id.isNotBlank()) out += id
                }
                is String -> if (item.isNotBlank()) out += item
            }
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
