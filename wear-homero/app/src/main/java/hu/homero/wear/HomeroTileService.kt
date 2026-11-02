package hu.homero.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeout

/** Csempe: a kiválasztott (vagy az első 6) hőmérő értékei. Az aljára koppintva frissít. */
class HomeroTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        scope.future {
            val prefs = Prefs(this@HomeroTileService)
            val force = requestParams.currentState.lastClickableId == ID_REFRESH
            var snap = DeviceRepository.cached(this@HomeroTileService)
            var error = false
            if (prefs.isConfigured && (force || System.currentTimeMillis() - snap.time > STALE_MS)) {
                try {
                    withTimeout(25_000) { DeviceRepository.refresh(applicationContext, notifyTile = false) }
                    snap = DeviceRepository.cached(this@HomeroTileService)
                } catch (e: Exception) {
                    error = true
                }
            }

            val sensors = snap.devices.filter { it.isSensor }
            val chosen = prefs.tileIds
            val shown = (if (chosen.isEmpty()) sensors else sensors.filter { it.id in chosen }).take(MAX_LINES)

            TileBuilders.Tile.Builder()
                .setResourcesVersion(RES_VERSION)
                .setFreshnessIntervalMillis(STALE_MS)
                .setTileTimeline(
                    TimelineBuilders.Timeline.fromLayoutElement(
                        layout(shown, snap.time, error, prefs.isConfigured)
                    )
                )
                .build()
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = scope.future {
        ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun layout(
        devices: List<SensorDevice>,
        time: Long,
        error: Boolean,
        configured: Boolean,
    ): LayoutElementBuilders.LayoutElement {
        val col = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        col.addContent(text("Hőmérők", 14f, 0xFF8AB4F8.toInt(), bold = true))
        col.addContent(spacer(4f))

        when {
            !configured -> col.addContent(text("Nyisd meg az appot", 13f, WHITE))
            devices.isEmpty() -> col.addContent(text("Nincs adat", 13f, WHITE))
            else -> devices.forEach { d ->
                val hum = if (d.humidity != null) "  " + formatHum(d.humidity) else ""
                col.addContent(text("${d.shortName.take(11)}  ${formatTemp(d.temperature)}$hum", 13f, colorFor(d)))
            }
        }

        col.addContent(spacer(4f))
        val footer = (if (error) "⚠ " else "↻ ") + formatTime(time)
        col.addContent(
            LayoutElementBuilders.Box.Builder()
                .setModifiers(clickable(ID_REFRESH, ActionBuilders.LoadAction.Builder().build()))
                .addContent(text(footer, 12f, if (error) 0xFFFF8A80.toInt() else GREY))
                .build()
        )

        val openApp = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .build()
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(clickable(ID_OPEN, openApp))
            .addContent(col.build())
            .build()
    }

    private fun colorFor(d: SensorDevice): Int {
        val t = d.temperature ?: return WHITE
        return when {
            !d.online -> GREY
            t < 18 -> 0xFF8AB4F8.toInt()
            t > 27 -> 0xFFFF7043.toInt()
            t > 24 -> 0xFFFFB74D.toInt()
            else -> WHITE
        }
    }

    private fun clickable(id: String, action: ActionBuilders.Action) =
        ModifiersBuilders.Modifiers.Builder()
            .setClickable(ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(action).build())
            .build()

    private fun spacer(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(
                        if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD
                        else LayoutElementBuilders.FONT_WEIGHT_NORMAL
                    )
                    .build()
            )
            .build()

    companion object {
        private const val RES_VERSION = "1"
        private const val ID_REFRESH = "refresh"
        private const val ID_OPEN = "open"
        private const val STALE_MS = 15 * 60 * 1000L
        private const val MAX_LINES = 6
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val GREY = 0xFF9AA0A6.toInt()
    }
}
