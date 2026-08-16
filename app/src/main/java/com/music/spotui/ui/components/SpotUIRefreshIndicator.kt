package com.music.spotui.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotUIRefreshIndicator(
    isRefreshing: Boolean,
    state: PullToRefreshState,
    modifier: Modifier = Modifier
) {
    if (isRefreshing || state.progress > 0f) {
        val transition = rememberInfiniteTransition(label = "equalizer")
        
        val h1 by transition.animateFloat(
            initialValue = 0.3f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "h1"
        )
        val h2 by transition.animateFloat(
            initialValue = 0.8f, targetValue = 0.2f,
            animationSpec = infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "h2"
        )
        val h3 by transition.animateFloat(
            initialValue = 0.4f, targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "h3"
        )
        val h4 by transition.animateFloat(
            initialValue = 0.9f, targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(450, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "h4"
        )

        val heights = listOf(h1, h2, h3, h4)

        Box(
            modifier = modifier
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF282828))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                heights.forEach { h ->
                    Box(
                        modifier = Modifier
                            .width(3.5.dp)
                            .height(18.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .fillMaxHeight(if (isRefreshing) h else (state.progress * h).coerceIn(0.1f, 1f))
                                .align(Alignment.Center)
                                .clip(CircleShape)
                                .background(Color(0xFF1ED760))
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = if (isRefreshing) "Updating..." else "Pull to refresh",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
