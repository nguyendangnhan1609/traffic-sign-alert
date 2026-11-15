package vn.trafficalert.app.cloud

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import vn.trafficalert.core.BBox
import vn.trafficalert.core.CloudEscalationPolicy
import vn.trafficalert.core.CloudSignResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Gọi Claude API để đọc ảnh cắt của biển phức tạp. */
class ClaudeClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Trả về null nếu có bất kỳ lỗi nào (mất mạng, sai khoá, phản hồi không đúng định dạng). */
    suspend fun readSign(jpeg: ByteArray, apiKey: String, model: String): CloudSignResult? =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("model", model)
                    .put("max_tokens", 200)
                    .put("messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", JSONArray()
                            .put(JSONObject()
                                .put("type", "image")
                                .put("source", JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", "image/jpeg")
                                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
                            .put(JSONObject().put("type", "text").put("text", CloudEscalationPolicy.PROMPT)))
                    ))
                val request = Request.Builder()
                    .url(API_URL)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                http.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "API trả mã ${resp.code}")
                        return@withContext null
                    }
                    parseResponse(text)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Gọi API lỗi: ${t.javaClass.simpleName}")
                null
            }
        }

    companion object {
        private const val TAG = "ClaudeClient"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private val JSON = "application/json".toMediaType()

        fun parseResponse(raw: String): CloudSignResult? {
            val content = JSONObject(raw).optJSONArray("content") ?: return null
            var text: String? = null
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") { text = block.optString("text"); break }
            }
            return parseResult(text ?: return null)
        }

        /** Lấy đối tượng JSON trong câu trả lời, chấp nhận chữ thừa xung quanh. */
        fun parseResult(text: String): CloudSignResult? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = try { JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { return null }
            val limit = if (obj.isNull("speed_limit_kmh")) null else obj.optInt("speed_limit_kmh", -1).takeIf { it in 5..150 }
            return CloudSignResult(
                isTrafficSign = obj.optBoolean("is_traffic_sign", false),
                speech = obj.optString("speech", "").trim(),
                speedLimitKmh = limit,
                confidence = obj.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
            )
        }

        /**
         * Cắt vùng biển từ khung hình, nới rộng 30% hai bên và phía trên, 120% phía dưới để lấy cả biển phụ.
         * Trả về JPEG, cạnh dài nhất tối đa 512 px.
         */
        fun cropSign(frame: Bitmap, box: BBox): ByteArray {
            val w = box.width * frame.width
            val h = box.height * frame.height
            val rect = Rect(
                (box.left * frame.width - 0.3f * w).toInt().coerceIn(0, frame.width - 1),
                (box.top * frame.height - 0.3f * h).toInt().coerceIn(0, frame.height - 1),
                (box.right * frame.width + 0.3f * w).toInt().coerceIn(1, frame.width),
                (box.bottom * frame.height + 1.2f * h).toInt().coerceIn(1, frame.height),
            )
            val cw = (rect.right - rect.left).coerceAtLeast(1)
            val ch = (rect.bottom - rect.top).coerceAtLeast(1)
            var crop = Bitmap.createBitmap(frame, rect.left, rect.top, cw, ch)
            val longest = maxOf(cw, ch)
            if (longest > 512) {
                val s = 512f / longest
                crop = Bitmap.createScaledBitmap(crop, (cw * s).toInt().coerceAtLeast(1), (ch * s).toInt().coerceAtLeast(1), true)
            }
            return ByteArrayOutputStream().use { out ->
                crop.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        }
    }
}
