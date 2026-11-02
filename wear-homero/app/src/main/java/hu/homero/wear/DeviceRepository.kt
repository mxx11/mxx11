package hu.homero.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import java.text.Collator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

data class SensorDevice(
    val id: String,
    val name: String,
    val category: String,
    val online: Boolean,
    val temperature: Double?,
    val humidity: Double?,
    val battery: Int?,
    val batteryState: String?,
) {
    val isSensor: Boolean get() = temperature != null || humidity != null

    /** "Nappali hőmérő" → "Nappali" (csempéhez és számlaphoz). */
    val shortName: String
        get() = name.replace(Regex("(?i)\\s*(hőmérő|homero|thermometer|temperature|sensor)\\s*"), " ")
            .trim().ifEmpty { name }

    val batteryLow: Boolean
        get() = (battery != null && battery <= 15) || batteryState == "low"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("category", category); put("online", online)
        temperature?.let { put("temperature", it) }
        humidity?.let { put("humidity", it) }
        battery?.let { put("battery", it) }
        batteryState?.let { put("batteryState", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = SensorDevice(
            id = o.getString("id"),
            name = o.optString("name"),
            category = o.optString("category"),
            online = o.optBoolean("online", true),
            temperature = if (o.has("temperature")) o.getDouble("temperature") else null,
            humidity = if (o.has("humidity")) o.getDouble("humidity") else null,
            battery = if (o.has("battery")) o.getInt("battery") else null,
            batteryState = if (o.has("batteryState")) o.getString("batteryState") else null,
        )
    }
}

data class Snapshot(val devices: List<SensorDevice>, val time: Long)

fun formatTemp(t: Double?): String = t?.let { String.format(Locale.US, "%.1f°", it) } ?: "–"
fun formatHum(h: Double?): String = h?.let { "${Math.round(it)}%" } ?: ""
fun formatTime(ms: Long): String =
    if (ms <= 0) "–" else java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(ms))

object DeviceRepository {
    private val TEMP_CODES = listOf("va_temperature", "temp_current", "temp_indoor", "temperature", "temp_value")
    private val HUM_CODES = listOf("va_humidity", "humidity_value", "humidity_indoor", "humidity", "hum_value")
    private val BATT_CODES = listOf("battery_percentage", "va_battery")

    private val mutex = Mutex()

    fun cached(context: Context): Snapshot {
        val s = Prefs(context).cacheJson ?: return Snapshot(emptyList(), 0)
        return try {
            val o = JSONObject(s)
            val arr = o.getJSONArray("devices")
            Snapshot((0 until arr.length()).map { SensorDevice.fromJson(arr.getJSONObject(it)) }, o.getLong("time"))
        } catch (e: Exception) {
            Snapshot(emptyList(), 0)
        }
    }

    /** Lekéri a felhőből az eszközöket, elmenti, és frissíti a csempét/komplikációt. */
    suspend fun refresh(
        context: Context,
        notifyTile: Boolean = true,
        notifyComplication: Boolean = true,
    ): List<SensorDevice> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = Prefs(context)
            if (!prefs.isConfigured) throw TuyaException(-1, "Add meg az Access ID-t és a Secretet a Beállításokban")
            val client = TuyaClient(prefs)
            val raw = try {
                client.listDevices()
            } catch (e: UnknownHostException) {
                throw IOException("Nincs internetkapcsolat")
            }
            val collator = Collator.getInstance(Locale("hu", "HU"))
            val devices = raw.map { parse(it, prefs, client) }
                .sortedWith(compareBy<SensorDevice> { !it.isSensor }.thenComparator { a, b -> collator.compare(a.name, b.name) })

            val json = JSONObject()
                .put("time", System.currentTimeMillis())
                .put("devices", JSONArray().apply { devices.forEach { put(it.toJson()) } })
            prefs.cacheJson = json.toString()

            if (notifyTile) {
                try { TileService.getUpdater(context).requestUpdate(HomeroTileService::class.java) } catch (_: Exception) {}
            }
            if (notifyComplication) {
                try {
                    ComplicationDataSourceUpdateRequester.create(
                        context, ComponentName(context, HomeroComplicationService::class.java)
                    ).requestUpdateAll()
                } catch (_: Exception) {}
            }
            devices
        }
    }

    private fun parse(d: JSONObject, prefs: Prefs, client: TuyaClient): SensorDevice {
        val id = d.getString("id")
        val values = mutableMapOf<String, Any>()
        val st = d.optJSONArray("status") ?: JSONArray()
        for (i in 0 until st.length()) {
            val o = st.optJSONObject(i) ?: continue
            if (o.has("code") && o.has("value")) values[o.getString("code")] = o.get("value")
        }
        val tempCode = TEMP_CODES.firstOrNull { values[it] is Number }
        val humCode = HUM_CODES.firstOrNull { values[it] is Number }

        var scales: Map<String, Int> = emptyMap()
        if (tempCode != null || humCode != null) {
            scales = prefs.scales(id) ?: try {
                client.specScales(id).also { prefs.saveScales(id, it) }
            } catch (e: TuyaException) {
                emptyMap<String, Int>().also { prefs.saveScales(id, it) }
            } catch (e: Exception) {
                emptyMap()
            }
        }

        fun scaled(code: String?, heuristicLimit: Double): Double? {
            if (code == null) return null
            val raw = (values[code] as Number).toDouble()
            val scale = scales[code]
            return if (scale != null) raw / 10.0.pow(scale)
            else if (abs(raw) >= heuristicLimit) raw / 10.0 else raw
        }

        val battCode = BATT_CODES.firstOrNull { values[it] is Number }
        return SensorDevice(
            id = id,
            name = d.optString("name", id),
            category = d.optString("category", ""),
            online = d.optBoolean("online", true),
            temperature = scaled(tempCode, 100.0),
            humidity = scaled(humCode, 101.0),
            battery = battCode?.let { (values[it] as Number).toInt() },
            batteryState = values["battery_state"] as? String,
        )
    }
}
