package com.opentune.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.opentune.data.local.AnimatedArtwork
import com.opentune.data.local.LocalMusic
import com.opentune.ui.player.BluetoothCodec

/**
 * Everything the app needs, asked for together as soon as it opens: the music
 * files, the videos beside them (animated artwork), Nearby devices (to read the
 * Bluetooth codec) and notifications (the playback controls). Asked each time
 * the app opens while any is missing; Android itself stops asking for one
 * that's been refused twice, and it can still be given in the phone's settings.
 */
@Composable
fun StartupPermissions() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[LocalMusic.permission] == true) LocalMusic.rescan(0)
        if (result[AnimatedArtwork.permission] == true) AnimatedArtwork.refresh()
        if (result[BluetoothCodec.permission] == true) BluetoothCodec.refresh()
    }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(LocalMusic.permission)
            add(AnimatedArtwork.permission)
            add(BluetoothCodec.permission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.distinct()
        val missing = wanted.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) runCatching { launcher.launch(missing.toTypedArray()) }
    }
}
