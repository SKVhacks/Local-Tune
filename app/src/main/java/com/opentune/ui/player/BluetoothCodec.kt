package com.opentune.ui.player

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.coroutines.resume

/**
 * The Bluetooth codec the phone is sending to the connected headphones (SBC,
 * AAC, aptX, LDAC, LHDC…), with its rate, depth and, for LDAC, its bitrate,
 * as the phone's own Bluetooth settings show it.
 *
 * Android reads it with BluetoothA2dp.getCodecStatus, which isn't open to apps,
 * so it's reached through HiddenApiBypass; and it needs the "Nearby devices"
 * permission. When either isn't to be had, there's simply no line for it.
 */
object BluetoothCodec {
    data class Info(val name: String, val detail: String?) {
        override fun toString() = listOfNotNull(name, detail).joinToString(" · ")
    }

    val permission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH

    fun allowed(context: Context) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Info? = withContext(Dispatchers.Main) {
        if (!allowed(context)) return@withContext null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return@withContext null
        val a2dp = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<BluetoothA2dp?> { cont ->
                val asked = runCatching {
                    adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            if (cont.isActive) cont.resume(proxy as? BluetoothA2dp)
                        }
                        override fun onServiceDisconnected(profile: Int) = Unit
                    }, BluetoothProfile.A2DP)
                }.getOrDefault(false)
                if (!asked && cont.isActive) cont.resume(null)
            }
        } ?: return@withContext null
        try {
            val devices = a2dp.connectedDevices
            val device = devices.firstOrNull { a2dp.isA2dpPlaying(it) } ?: devices.firstOrNull() ?: return@withContext null
            val status = call(a2dp, "getCodecStatus", device) ?: return@withContext null
            describe(call(status, "getCodecConfig") ?: return@withContext null)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp) }
        }
    }

    private fun describe(config: Any): Info? {
        val type = call(config, "getCodecType") as? Int ?: return null
        // Android 14 names the codec itself, vendor ones (LHDC, aptX Adaptive…) too.
        val extended = if (Build.VERSION.SDK_INT >= 34) runCatching { call(config, "getExtendedCodecType")?.let { call(it, "getCodecName") as? String } }.getOrNull() else null
        val name = extended?.takeIf { it.isNotBlank() }?.let(::tidy) ?: CODECS[type] ?: return null
        val rate = (call(config, "getSampleRate") as? Int)?.let { mask -> RATES.entries.firstOrNull { mask and it.key != 0 }?.value }
        val bits = (call(config, "getBitsPerSample") as? Int)?.let { mask -> BITS.entries.firstOrNull { mask and it.key != 0 }?.value }
        // LDAC keeps its quality mode in the first codec-specific value.
        val ldac = if (name.equals("LDAC", ignoreCase = true)) LDAC_MODES[(call(config, "getCodecSpecific1") as? Long)?.toInt()] else null
        return Info(name, listOfNotNull(rate, bits, ldac).joinToString(" · ").ifEmpty { null })
    }

    /** A method of a Bluetooth class, public or not. */
    private fun call(target: Any, name: String, vararg args: Any): Any? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.invoke(target.javaClass, target, name, *args)
        else target.javaClass.getMethod(name, *args.map { if (it is BluetoothDevice) BluetoothDevice::class.java else it.javaClass }.toTypedArray()).invoke(target, *args)
    }.getOrNull()

    private fun tidy(raw: String) = when (raw.uppercase()) {
        "APTX" -> "aptX"
        "APTX HD", "APTX_HD" -> "aptX HD"
        "APTX ADAPTIVE", "APTX_ADAPTIVE" -> "aptX Adaptive"
        else -> raw
    }

    private val CODECS = mapOf(0 to "SBC", 1 to "AAC", 2 to "aptX", 3 to "aptX HD", 4 to "LDAC", 5 to "LC3", 6 to "Opus")
    private val RATES = linkedMapOf(0x20 to "192 kHz", 0x10 to "176.4 kHz", 0x8 to "96 kHz", 0x4 to "88.2 kHz", 0x2 to "48 kHz", 0x1 to "44.1 kHz")
    private val BITS = linkedMapOf(0x4 to "32-bit", 0x2 to "24-bit", 0x1 to "16-bit")
    private val LDAC_MODES = mapOf(1000 to "990 kbps", 1001 to "660 kbps", 1002 to "330 kbps", 1003 to "Adaptive bitrate")
}

/**
 * The codec on the Bluetooth link while the sound goes out over Bluetooth:
 * null otherwise, or until it's read. Asks once for the Nearby devices
 * permission it needs, the first time a details sheet opens on Bluetooth.
 */
@Composable
internal fun rememberBluetoothCodec(route: OutputRoute, device: String?): State<BluetoothCodec.Info?> {
    val context = LocalContext.current
    val granted = remember { mutableIntStateOf(0) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ok -> if (ok) granted.intValue++ }
    LaunchedEffect(route) {
        if (route != OutputRoute.BLUETOOTH || BluetoothCodec.allowed(context)) return@LaunchedEffect
        val prefs = context.getSharedPreferences("bluetooth_codec", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return@LaunchedEffect
        prefs.edit().putBoolean("asked", true).apply()
        runCatching { ask.launch(BluetoothCodec.permission) }
    }
    return produceState<BluetoothCodec.Info?>(null, route, device, granted.intValue) {
        value = if (route == OutputRoute.BLUETOOTH) BluetoothCodec.current(context) else null
    }
}
