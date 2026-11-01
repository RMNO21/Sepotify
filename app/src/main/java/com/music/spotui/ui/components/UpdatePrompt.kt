package com.music.spotui.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.music.spotui.R
import com.music.spotui.data.update.UpdateChecker

/**
 * Launch-time update prompt: checks the GitHub release once per app start and,
 * when a newer build exists, offers Upgrade / Dismiss / Don't show again
 * ("don't show again" is per release — the next release prompts again).
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    var update by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        update = UpdateChecker.check(context)
    }

    val info = update ?: return
    AlertDialog(
        onDismissRequest = { update = null },
        title = { Text(stringResource(R.string.update_available_title)) },
        text = { Text(stringResource(R.string.update_available_message, info.version)) },
        confirmButton = {
            TextButton(onClick = {
                com.music.spotui.data.update.AppUpdateInstaller.startUpdate(context, info)
                update = null
            }) { Text(stringResource(R.string.update_action_upgrade)) }
        },
        dismissButton = {
            TextButton(onClick = {
                UpdateChecker.skipRelease(context, info)
                update = null
            }) { Text(stringResource(R.string.update_action_skip)) }
            TextButton(onClick = { update = null }) { Text(stringResource(R.string.update_action_dismiss)) }
        },
    )
}
