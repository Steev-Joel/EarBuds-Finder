package com.example.earbudfinder

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.math.roundToInt

data class Dev(val address: String, val name: String?, val rssi: Double, val seen: Long)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private fun scanPerm() =
    if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION

@SuppressLint("MissingPermission")
fun saveSpot(ctx: Context) {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { lm.isProviderEnabled(it) } ?: return
    val store = { l: Location? ->
        if (l != null) ctx.getSharedPreferences("spot", 0).edit()
            .putString("v", "${l.latitude},${l.longitude},${System.currentTimeMillis()}").apply()
    }
    if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ctx)) { store(it) }
    else store(lm.getLastKnownLocation(provider))
}

@Composable
fun SpotCard(now: Long) {
    val ctx = LocalContext.current
    val parts = remember(now / 5000) { ctx.getSharedPreferences("spot", 0).getString("v", null)?.split(",") }
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("Last spot near them", fontWeight = FontWeight.SemiBold)
            if (parts == null || parts.size < 3) {
                Text("Nothing saved yet. A spot is saved automatically while the signal is strong.", fontSize = 14.sp)
            } else {
                val ago = ((System.currentTimeMillis() - parts[2].toLong()) / 60000).coerceAtLeast(0)
                Text("Saved $ago min ago", fontSize = 14.sp)
                TextButton(onClick = {
                    val q = "${parts[0]},${parts[1]}"
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$q?q=$q(Earbuds)")))
                }) { Text("Open in Maps") }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun App() {
    val ctx = LocalContext.current
    val needed = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasScan() = ContextCompat.checkSelfPermission(ctx, scanPerm()) == PackageManager.PERMISSION_GRANTED

    var ok by remember { mutableStateOf(hasScan()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = hasScan() }
    LaunchedEffect(Unit) { if (!ok) ask.launch(needed) }

    val adapter = remember { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    var btOn by remember { mutableStateOf(adapter?.isEnabled == true) }
    val devices = remember { mutableStateMapOf<String, Dev>() }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var hideUnnamed by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
            btOn = adapter?.isEnabled == true
            devices.entries.filter { now - it.value.seen > 20000 && it.key != selected }
                .map { it.key }.forEach { devices.remove(it) }
        }
    }

    DisposableEffect(ok && btOn) {
        val scanner = if (ok && btOn) adapter?.bluetoothLeScanner else null
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val a = r.device.address
                val old = devices[a]
                val name = r.scanRecord?.deviceName ?: old?.name
                val rssi = if (old == null) r.rssi.toDouble() else old.rssi * 0.7 + r.rssi * 0.3
                devices[a] = Dev(a, name, rssi, System.currentTimeMillis())
            }
        }
        scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        onDispose { scanner?.stopScan(cb) }
    }

    BackHandler(selected != null) { selected = null }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        when {
            !ok -> {
                Text("Earbud Finder", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Text("Allow Bluetooth scanning and location so the app can hear your earbuds and remember where you were.")
                Button(onClick = { ask.launch(needed) }, Modifier.padding(top = 12.dp)) { Text("Allow permissions") }
            }
            !btOn -> {
                Text("Earbud Finder", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Text("Bluetooth is off. Turn it on and the search starts automatically.")
            }
            selected != null -> Track(selected?.let { devices[it] }, now, onBack = { selected = null })
            else -> {
                Text("Earbud Finder", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text("Take your earbuds out of the case, then tap them in the list. Closest devices come first.", fontSize = 14.sp)
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it }, singleLine = true,
                    label = { Text("Filter by name (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(hideUnnamed, { hideUnnamed = it })
                    Text("Hide unnamed devices")
                }
                val list = devices.values
                    .filter { (!hideUnnamed || it.name != null) && (filter.isBlank() || (it.name ?: "").contains(filter, true)) }
                    .sortedByDescending { it.rssi }
                LazyColumn(Modifier.weight(1f)) {
                    if (list.isEmpty()) item { Text("Searching…", Modifier.padding(vertical = 16.dp)) }
                    items(list, key = { it.address }) { d ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = d.address }.padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(d.name ?: "Unnamed ${d.address.takeLast(5)}", fontWeight = FontWeight.SemiBold)
                            Text("${d.rssi.roundToInt()} dBm")
                        }
                        HorizontalDivider()
                    }
                }
                SpotCard(now)
            }
        }
    }
}

@Composable
fun Track(dev: Dev?, now: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var sound by remember { mutableStateOf(true) }
    val lost = dev == null || now - dev.seen > 8000
    val pct = if (dev == null || lost) 0f else ((dev.rssi + 95) / 55).coerceIn(0.0, 1.0).toFloat()
    val pctNow by rememberUpdatedState(pct)
    val lostNow by rememberUpdatedState(lost)
    val soundNow by rememberUpdatedState(sound)
    val anim by animateFloatAsState(pct, label = "proximity")

    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(Unit) {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        try {
            while (true) {
                val live = soundNow && !lostNow
                if (live) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                delay(if (live) (1100 - pctNow * 980).toLong() else 400L)
            }
        } finally { tone.release() }
    }
    LaunchedEffect(Unit) {
        while (true) { if (pctNow > 0.3f && !lostNow) saveSpot(ctx); delay(20000) }
    }

    val d = if (dev == null) 0.0 else 10.0.pow((-59 - dev.rssi) / 25.0)
    val label = when {
        lost -> "Signal lost"
        d < 1 -> "Very close"
        d < 3 -> "Nearby"
        d < 8 -> "In the room"
        else -> "Far away"
    }
    val detail = if (dev == null || lost) "They may be in the case, out of range, or connected to another device."
    else "About %.1f m · %d dBm".format(d, dev.rssi.roundToInt())
    val ring = MaterialTheme.colorScheme.outlineVariant
    val fg = MaterialTheme.colorScheme.onSurface

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(dev?.name ?: "Unnamed device", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))
        Canvas(Modifier.size(280.dp)) {
            val r = size.minDimension / 2
            listOf(1f, .75f, .5f, .25f).forEach { drawCircle(ring, r * it, center, style = Stroke(3f)) }
            drawCircle(Color.hsv(215f - anim * 185f, .75f, .9f), r * (0.06f + 0.94f * anim), center)
            drawCircle(fg, 8f, center)
        }
        Spacer(Modifier.height(16.dp))
        Text(label, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Text(detail, fontSize = 14.sp)
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(sound, { sound = it })
            Text("  Beeps", fontSize = 16.sp)
        }
        Spacer(Modifier.weight(1f))
        SpotCard(now)
    }
}
