package hu.homero.wear

import android.content.Context
import org.json.JSONObject

/** Tuya adatközpontok. Magyar Smart Life fiók esetén általában Közép-Európa. */
enum class Region(val label: String, val host: String) {
    EU("Közép-Európa", "https://openapi.tuyaeu.com"),
    EU_WEST("Nyugat-Európa", "https://openapi-weaz.tuyaeu.com"),
    US("USA (nyugat)", "https://openapi.tuyaus.com"),
    US_EAST("USA (kelet)", "https://openapi-ueaz.tuyaus.com"),
    CN("Kína", "https://openapi.tuyacn.com"),
    IN("India", "https://openapi.tuyain.com");

    fun next(): Region = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromKey(key: String?): Region =
            entries.firstOrNull { it.name.equals(key?.trim(), ignoreCase = true) } ?: EU
    }
}

/** Egyszerű kulcs-érték tároló a beállításoknak és a gyorsítótárnak. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("homero", Context.MODE_PRIVATE)

    var accessId: String
        get() = sp.getString("access_id", "") ?: ""
        set(v) = sp.edit().putString("access_id", v.trim()).apply()

    var accessSecret: String
        get() = sp.getString("access_secret", "") ?: ""
        set(v) = sp.edit().putString("access_secret", v.trim()).apply()

    var region: Region
        get() = Region.fromKey(sp.getString("region", null))
        set(v) = sp.edit().putString("region", v.name).apply()

    val isConfigured: Boolean
        get() = accessId.isNotBlank() && accessSecret.isNotBlank()

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()

    var tokenExpiresAt: Long
        get() = sp.getLong("token_exp", 0L)
        set(v) = sp.edit().putLong("token_exp", v).apply()

    fun clearToken() {
        sp.edit().remove("token").remove("token_exp").apply()
    }

    /** A számlapon (komplikáció) megjelenő eszköz. */
    var favoriteId: String?
        get() = sp.getString("favorite_id", null)
        set(v) = sp.edit().putString("favorite_id", v).apply()

    /** A csempén megjelenő eszközök (üres = az első néhány hőmérő). */
    var tileIds: Set<String>
        get() = (sp.getStringSet("tile_ids", emptySet()) ?: emptySet()).toSet()
        set(v) = sp.edit().putStringSet("tile_ids", v.toSet()).apply()

    var cacheJson: String?
        get() = sp.getString("cache_json", null)
        set(v) = sp.edit().putString("cache_json", v).apply()

    /** Az eszközök adatpont-skálái (pl. 1 = tizedek), eszközönként. */
    fun scales(deviceId: String): Map<String, Int>? {
        val s = sp.getString("scale_$deviceId", null) ?: return null
        return try {
            val o = JSONObject(s)
            o.keys().asSequence().associateWith { o.getInt(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun saveScales(deviceId: String, map: Map<String, Int>) {
        sp.edit().putString("scale_$deviceId", JSONObject(map as Map<*, *>).toString()).apply()
    }

    /** Kulcs vagy régió változásakor minden régi adatot eldobunk. */
    fun resetSession() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("scale_") }.forEach { e.remove(it) }
        e.remove("token").remove("token_exp").remove("cache_json").apply()
    }
}
