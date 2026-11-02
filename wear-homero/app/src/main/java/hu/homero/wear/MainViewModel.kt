package hu.homero.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.tiles.TileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val devices: List<SensorDevice> = emptyList(),
    val updatedAt: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val configured: Boolean = false,
    val accessId: String = "",
    val hasSecret: Boolean = false,
    val region: Region = Region.EU,
    val favoriteId: String? = null,
    val tileIds: Set<String> = emptySet(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        reloadLocal()
        if (prefs.isConfigured) refresh()
    }

    private fun reloadLocal() {
        val snap = DeviceRepository.cached(getApplication())
        _state.update {
            it.copy(
                devices = snap.devices,
                updatedAt = snap.time,
                configured = prefs.isConfigured,
                accessId = prefs.accessId,
                hasSecret = prefs.accessSecret.isNotBlank(),
                region = prefs.region,
                favoriteId = prefs.favoriteId,
                tileIds = prefs.tileIds,
            )
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val list = DeviceRepository.refresh(getApplication())
                _state.update {
                    it.copy(
                        devices = list,
                        updatedAt = System.currentTimeMillis(),
                        loading = false,
                        error = if (list.isEmpty()) "Nincs eszköz. Össze van kapcsolva a Smart Life fiók, és jó a régió?" else null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Beállítások mentése (a képernyőről vagy adb-ről). A null értékek változatlanok maradnak. */
    fun applyConfig(accessId: String?, secret: String?, region: String?) {
        var changed = false
        if (!accessId.isNullOrBlank() && accessId.trim() != prefs.accessId) { prefs.accessId = accessId; changed = true }
        if (!secret.isNullOrBlank() && secret.trim() != prefs.accessSecret) { prefs.accessSecret = secret; changed = true }
        if (!region.isNullOrBlank()) {
            val r = Region.fromKey(region)
            if (r != prefs.region) { prefs.region = r; changed = true }
        }
        if (changed) {
            prefs.resetSession()
            _state.update { it.copy(devices = emptyList(), updatedAt = 0, error = null) }
        }
        reloadLocal()
        if (changed && prefs.isConfigured) refresh()
    }

    fun cycleRegion() = applyConfig(null, null, prefs.region.next().name)

    fun toggleFavorite(id: String) {
        prefs.favoriteId = if (prefs.favoriteId == id) null else id
        reloadLocal()
        try {
            androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(
                getApplication(),
                android.content.ComponentName(getApplication(), HomeroComplicationService::class.java)
            ).requestUpdateAll()
        } catch (_: Exception) {}
    }

    fun toggleTile(id: String) {
        val s = prefs.tileIds.toMutableSet()
        if (!s.remove(id)) s.add(id)
        prefs.tileIds = s
        reloadLocal()
        try { TileService.getUpdater(getApplication()).requestUpdate(HomeroTileService::class.java) } catch (_: Exception) {}
    }
}
