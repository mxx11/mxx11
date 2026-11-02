package hu.homero.wear

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleConfigIntent(intent)
        setContent { HomeroApp(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleConfigIntent(intent)
    }

    /**
     * Számítógépről, adb-vel is beállítható:
     * adb shell am start -n hu.homero.wear/.MainActivity --es access_id XXX --es access_secret YYY --es region EU
     */
    private fun handleConfigIntent(i: Intent?) {
        if (i == null) return
        val id = i.getStringExtra("access_id")
        val secret = i.getStringExtra("access_secret")
        val region = i.getStringExtra("region")
        if (id != null || secret != null || region != null) vm.applyConfig(id, secret, region)
    }
}

private val Cold = Color(0xFF8AB4F8)
private val Warm = Color(0xFFFFB74D)
private val Hot = Color(0xFFFF7043)

private fun tempColor(t: Double?): Color = when {
    t == null -> Color.White
    t < 18 -> Cold
    t > 27 -> Hot
    t > 24 -> Warm
    else -> Color.White
}

@Composable
fun HomeroApp(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val nav = rememberSwipeDismissableNavController()

    MaterialTheme {
        SwipeDismissableNavHost(navController = nav, startDestination = "list") {
            composable("list") {
                DeviceListScreen(
                    state = state,
                    onRefresh = vm::refresh,
                    onOpen = { selectedId = it; nav.navigate("detail") },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("detail") {
                val d = state.devices.firstOrNull { it.id == selectedId }
                if (d != null) {
                    DetailScreen(
                        d = d,
                        isFavorite = state.favoriteId == d.id,
                        onTile = d.id in state.tileIds,
                        onToggleFavorite = { vm.toggleFavorite(d.id) },
                        onToggleTile = { vm.toggleTile(d.id) },
                    )
                }
            }
            composable("settings") {
                SettingsScreen(state = state, vm = vm, onDone = { nav.popBackStack() })
            }
        }
    }
}

@Composable
private fun ListScreen(content: ScalingLazyListScope.() -> Unit) {
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun DeviceListScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val sensors = state.devices.filter { it.isSensor }
    val others = state.devices.filter { !it.isSensor }

    ListScreen {
        item { ListHeader { Text("Hőmérők") } }

        if (!state.configured) {
            item {
                Chip(
                    onClick = onSettings,
                    label = { Text("Beállítás") },
                    secondaryLabel = { Text("Tuya kulcsok megadása") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        state.error?.let { err ->
            item {
                Text(
                    err,
                    color = MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        if (state.loading && state.devices.isEmpty()) {
            item { CircularProgressIndicator() }
        }

        items(sensors, key = { it.id }) { d -> SensorCard(d) { onOpen(d.id) } }

        if (others.isNotEmpty()) {
            item { ListHeader { Text("Egyéb eszközök") } }
            items(others, key = { it.id }) { d ->
                Chip(
                    onClick = { onOpen(d.id) },
                    label = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(if (d.online) "online" else "offline") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.configured) {
            item {
                Chip(
                    onClick = onRefresh,
                    label = { Text(if (state.loading) "Frissítés…" else "Frissítés") },
                    secondaryLabel = { Text("Utoljára: ${formatTime(state.updatedAt)}") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Chip(
                onClick = onSettings,
                label = { Text("Beállítások") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SensorCard(d: SensorDevice, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            d.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatTemp(d.temperature), style = MaterialTheme.typography.title2, color = tempColor(d.temperature))
            Spacer(Modifier.width(10.dp))
            if (d.humidity != null) {
                Text("💧" + formatHum(d.humidity), style = MaterialTheme.typography.body2)
            }
        }
        val notes = buildList {
            if (!d.online) add("offline")
            if (d.batteryLow) add("🔋 gyenge elem")
        }
        if (notes.isNotEmpty()) {
            Text(notes.joinToString(" · "), style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.error)
        }
    }
}

@Composable
private fun DetailScreen(
    d: SensorDevice,
    isFavorite: Boolean,
    onTile: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleTile: () -> Unit,
) {
    ListScreen {
        item {
            Text(
                d.name,
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        if (d.temperature != null) {
            item { Text(formatTemp(d.temperature), style = MaterialTheme.typography.display2, color = tempColor(d.temperature)) }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (d.humidity != null) Text("Páratartalom: ${formatHum(d.humidity)}")
                when {
                    d.battery != null -> Text("Elem: ${d.battery}%")
                    d.batteryState != null -> Text(
                        "Elem: " + when (d.batteryState) {
                            "low" -> "gyenge"; "middle" -> "közepes"; "high" -> "jó"; else -> d.batteryState
                        }
                    )
                }
                Text(
                    if (d.online) "online" else "offline",
                    color = if (d.online) Color(0xFF81C995) else MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption1,
                )
            }
        }
        if (d.isSensor) {
            item {
                ToggleChip(
                    checked = onTile,
                    onCheckedChange = { onToggleTile() },
                    label = { Text("Csempén") },
                    toggleControl = { Switch(checked = onTile) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                ToggleChip(
                    checked = isFavorite,
                    onCheckedChange = { onToggleFavorite() },
                    label = { Text("Számlapon") },
                    secondaryLabel = { Text("komplikáció") },
                    toggleControl = { Switch(checked = isFavorite) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private const val INPUT_KEY = "input"

@Composable
private fun SettingsScreen(state: UiState, vm: MainViewModel, onDone: () -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data ?: return@rememberLauncherForActivityResult
        val text = RemoteInput.getResultsFromIntent(data)?.getCharSequence(INPUT_KEY)?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            when (editing) {
                "id" -> vm.applyConfig(text, null, null)
                "secret" -> vm.applyConfig(null, text, null)
            }
        }
        editing = null
    }

    fun ask(field: String, label: String) {
        editing = field
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(
            intent, listOf(RemoteInput.Builder(INPUT_KEY).setLabel(label).build())
        )
        launcher.launch(intent)
    }

    ListScreen {
        item { ListHeader { Text("Beállítások") } }
        item {
            Chip(
                onClick = { ask("id", "Access ID") },
                label = { Text("Access ID") },
                secondaryLabel = {
                    Text(
                        if (state.accessId.isBlank()) "nincs megadva" else state.accessId.take(6) + "…",
                        maxLines = 1,
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = { ask("secret", "Access Secret") },
                label = { Text("Access Secret") },
                secondaryLabel = { Text(if (state.hasSecret) "••••••• (megadva)" else "nincs megadva") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = { vm.cycleRegion() },
                label = { Text("Régió") },
                secondaryLabel = { Text(state.region.label) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = { vm.refresh(); onDone() },
                label = { Text("Kész – frissítés") },
                colors = ChipDefaults.primaryChipColors(),
                enabled = state.configured,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                "A kulcsokat a platform.tuya.com projekted Overview oldalán találod.",
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}
