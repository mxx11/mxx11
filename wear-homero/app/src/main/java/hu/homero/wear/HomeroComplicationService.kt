package hu.homero.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.withTimeout

/** Számlap-komplikáció: a "Számlapon" jelölt hőmérő (vagy az első) értéke. */
class HomeroComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, "Külső", 9.3, 79.0)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs = Prefs(this)
        var snap = DeviceRepository.cached(this)
        if (prefs.isConfigured && System.currentTimeMillis() - snap.time > STALE_MS) {
            try {
                withTimeout(25_000) { DeviceRepository.refresh(applicationContext, notifyComplication = false) }
                snap = DeviceRepository.cached(this)
            } catch (_: Exception) {
            }
        }
        val d = snap.devices.firstOrNull { it.id == prefs.favoriteId && it.isSensor }
            ?: snap.devices.firstOrNull { it.temperature != null }
            ?: return NoDataComplicationData()
        return build(request.complicationType, d.shortName, d.temperature, d.humidity)
    }

    private fun build(type: ComplicationType, name: String, temp: Double?, hum: Double?): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val tempText = temp?.let { "${Math.round(it)}°" } ?: "–"
        val desc = plain("$name ${formatTemp(temp)} ${formatHum(hum)}".trim())
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(plain(tempText), desc)
                    .setTitle(plain(name.take(7)))
                    .setTapAction(tap)
                    .build()

            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(
                    plain("${formatTemp(temp)}  ${if (hum != null) "💧" + formatHum(hum) else ""}".trim()), desc
                )
                    .setTitle(plain(name))
                    .setTapAction(tap)
                    .build()

            else -> null
        }
    }

    private fun plain(s: String) = PlainComplicationText.Builder(s).build()

    companion object {
        private const val STALE_MS = 15 * 60 * 1000L
    }
}
