package com.music.spotui

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.music.spotui.data.network.NetworkMonitor
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.SplashScreenView
import com.music.spotui.ui.components.UpdatePrompt
import com.music.spotui.ui.notification.PlaybackService
import com.music.spotui.ui.theme.SpotuiTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import android.widget.Toast

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        // Initialize active network listener
        NetworkMonitor.init(this)

        // Ask for notification permission (Android 13+) so the media notification shows.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Connect a controller to bootstrap the MediaSessionService
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            SpotuiTheme {
                var showSplash by remember { mutableStateOf(true) }
                val isOnline by NetworkMonitor.isOnline.collectAsState()
                val context = LocalContext.current

                val dotColor by animateColorAsState(
                    targetValue = if (isOnline) Color(0xFF2196F3) else Color(0xFFF44336),
                    animationSpec = tween(durationMillis = 300),
                    label = "network_status_dot_color"
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .statusBarsPadding()
                        .consumeWindowInsets(WindowInsets.statusBars)
                ) {
                    App()

                    // Top-corner online/offline status dot (Blue = Online, Red = Offline)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 16.dp, top = 8.dp)
                            .zIndex(100f)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                            .border(1.dp, Color.Black.copy(alpha = 0.7f), CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                NetworkMonitor.triggerProbe(context)
                                Toast.makeText(
                                    context,
                                    if (isOnline) "Connected • Online" else "Offline • Only downloaded tracks available",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    )

                    SplashScreenView(
                        visible = showSplash,
                        onTimeout = { showSplash = false }
                    )
                }

                // New-release check (GitHub)
                UpdatePrompt()
            }
        }

        // Attach WebView for Spotify web player if enabled
        if (com.music.spotui.di.SongPlayer.webPlayerEnabled &&
            com.music.spotui.data.preferences.isWebPlaybackEnabled(this)
        ) {
            com.music.spotui.di.SpotifyWebPlayer.attach(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        SongPlayer.release()
    }
}
