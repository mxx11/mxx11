package hu.homero.wear

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TuyaException(val code: Int, message: String) : Exception(message)

/**
 * Minimális Tuya Cloud (OpenAPI) kliens.
 * Aláírás: HMAC-SHA256(client_id + access_token + t + stringToSign, secret), nagybetűs hex.
 * stringToSign = METHOD \n SHA256(body) \n (headerek, üres) \n URL(rendezett query-vel)
 */
class TuyaClient(private val prefs: Prefs) {

    private val base = prefs.region.host

    fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject {
        return try {
            call(path, query, ensureToken())
        } catch (e: TuyaException) {
            if (e.code in TOKEN_ERRORS) {
                prefs.clearToken()
                call(path, query, ensureToken())
            } else {
                throw e
            }
        }
    }

    private fun ensureToken(): String {
        val cached = prefs.token
        if (cached != null && System.currentTimeMillis() < prefs.tokenExpiresAt - 60_000) return cached
        val r = call("/v1.0/token", mapOf("grant_type" to "1"), null).getJSONObject("result")
        val token = r.getString("access_token")
        prefs.token = token
        prefs.tokenExpiresAt = System.currentTimeMillis() + r.optLong("expire_time", 3600) * 1000
        return token
    }

    private fun call(path: String, query: Map<String, String>, token: String?): JSONObject {
        val url = if (query.isEmpty()) path
        else path + "?" + query.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val t = System.currentTimeMillis().toString()
        val stringToSign = "GET\n$EMPTY_BODY_SHA256\n\n$url"
        val sign = hmacSha256(prefs.accessSecret, prefs.accessId + (token ?: "") + t + stringToSign)

        val builder = Request.Builder()
            .url(base + url)
            .get()
            .header("client_id", prefs.accessId)
            .header("sign", sign)
            .header("t", t)
            .header("sign_method", "HMAC-SHA256")
        if (token != null) builder.header("access_token", token)

        http.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("Üres válasz (HTTP ${resp.code})")
            val json = try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw IOException("Váratlan válasz (HTTP ${resp.code})")
            }
            if (!json.optBoolean("success", false)) {
                val code = json.optInt("code", -1)
                throw TuyaException(code, friendlyError(code, json.optString("msg")))
            }
            return json
        }
    }

    /**
     * Az összekapcsolt Smart Life fiók összes eszköze, "status" tömbbel együtt.
     * Ha az elsődleges végpont nem elérhető, a v2 listára és kötegelt státuszlekérésre vált.
     */
    fun listDevices(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        try {
            var lastRowKey = ""
            var guard = 0
            do {
                val q = if (lastRowKey.isEmpty()) emptyMap() else mapOf("last_row_key" to lastRowKey)
                val r = get("/v1.0/iot-01/associated-users/devices", q).getJSONObject("result")
                val arr = r.optJSONArray("devices") ?: JSONArray()
                for (i in 0 until arr.length()) out += arr.getJSONObject(i)
                val hasMore = r.optBoolean("has_more", false)
                lastRowKey = r.optString("last_row_key", "")
            } while (hasMore && lastRowKey.isNotEmpty() && ++guard < 50)
        } catch (e: TuyaException) {
            if (e.code in FATAL_ERRORS) throw e
            out.clear()
            out += listDevicesV2()
        }

        val missing = out.filter { it.optJSONArray("status") == null }.map { it.getString("id") }
        if (missing.isNotEmpty()) {
            val statuses = batchStatus(missing)
            out.forEach { d -> statuses[d.getString("id")]?.let { d.put("status", it) } }
        }
        out.forEach { d ->
            if (!d.has("online") && d.has("isOnline")) d.put("online", d.optBoolean("isOnline"))
        }
        return out
    }

    private fun listDevicesV2(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var lastId = ""
        var guard = 0
        while (guard++ < 50) {
            val q = mutableMapOf("page_size" to "20")
            if (lastId.isNotEmpty()) q["last_id"] = lastId
            val res = get("/v2.0/cloud/thing/device", q)
            val arr = res.optJSONArray("result")
                ?: res.optJSONObject("result")?.optJSONArray("list")
                ?: JSONArray()
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            if (arr.length() < 20) break
            lastId = arr.getJSONObject(arr.length() - 1).getString("id")
        }
        return out
    }

    private fun batchStatus(ids: List<String>): Map<String, JSONArray> {
        val map = mutableMapOf<String, JSONArray>()
        ids.chunked(20).forEach { chunk ->
            val arr = get("/v1.0/iot-03/devices/status", mapOf("device_ids" to chunk.joinToString(",")))
                .optJSONArray("result") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                o.optJSONArray("status")?.let { map[o.getString("id")] = it }
            }
        }
        return map
    }

    /** Adatpontonkénti skála (pl. va_temperature scale=1 → 225 = 22,5 °C). */
    fun specScales(deviceId: String): Map<String, Int> {
        val r = get("/v1.0/iot-03/devices/$deviceId/specification").optJSONObject("result")
            ?: return emptyMap()
        val arr = r.optJSONArray("status") ?: return emptyMap()
        val map = mutableMapOf<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val values = o.optString("values", "")
            if (values.startsWith("{")) {
                try {
                    val v = JSONObject(values)
                    if (v.has("scale")) map[o.getString("code")] = v.getInt("scale")
                } catch (_: JSONException) {
                }
            }
        }
        return map
    }

    companion object {
        private const val EMPTY_BODY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        private val TOKEN_ERRORS = setOf(1010, 1011, 1012)
        private val FATAL_ERRORS = setOf(1004, 1005, 1010, 1011, 1012, 2009, 28841002)

        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        private fun hmacSha256(secret: String, data: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
        }

        private fun friendlyError(code: Int, msg: String): String = when (code) {
            1004 -> "Hibás aláírás – ellenőrizd az Access Secretet"
            1005, 2009 -> "Érvénytelen Access ID (client_id) vagy rossz régió"
            1106 -> "Nincs jogosultság – össze van kapcsolva a Smart Life fiók a projekttel?"
            28841002 -> "Lejárt a Tuya próbaidőszak – hosszabbítsd meg a platform.tuya.com-on"
            28841101 -> "Nincs előfizetve az API (IoT Core) a Tuya projektben"
            else -> "Tuya hiba $code: $msg"
        }
    }
}
