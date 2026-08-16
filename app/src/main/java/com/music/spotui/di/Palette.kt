package com.music.spotui.di

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

data class MeshGradientPalette(
    val darkVibrant: Color,
    val dominantDark: Color,
    val darkMuted: Color
)

class Palette {

    fun extractMeshGradientColors(
        context: Context,
        imageUrl: String,
        onColorsExtracted: (MeshGradientPalette) -> Unit
    ) {
        if (imageUrl.isBlank()) return
        Glide.with(context)
            .asBitmap()
            .load(imageUrl)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    val scaledBitmap = Bitmap.createScaledBitmap(resource, 100, 100, true)
                    Palette.from(scaledBitmap).generate { palette ->
                        if (palette == null) return@generate
                        val darkVibrantInt = palette.darkVibrantSwatch?.rgb
                            ?: palette.vibrantSwatch?.rgb
                            ?: 0xFF121212.toInt()
                        val dominantDarkInt = palette.darkMutedSwatch?.rgb
                            ?: palette.dominantSwatch?.rgb
                            ?: 0xFF181818.toInt()
                        val darkMutedInt = palette.mutedSwatch?.rgb
                            ?: palette.lightVibrantSwatch?.rgb
                            ?: 0xFF050505.toInt()

                        val paletteResult = MeshGradientPalette(
                            darkVibrant = Color(darkVibrantInt or (0xFF shl 24)),
                            dominantDark = Color(dominantDarkInt or (0xFF shl 24)),
                            darkMuted = Color(darkMutedInt or (0xFF shl 24))
                        )
                        onColorsExtracted(paletteResult)
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    fun extractFirstColorFromImageUrl(context: Context, imageUrl: String, onColorExtracted: (Color) -> Unit) {
        Glide.with(context)
            .asBitmap()
            .load(imageUrl)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    Palette.from(resource).generate { palette ->
                        val dominantColor = palette?.darkVibrantSwatch?.rgb ?: palette?.dominantSwatch?.rgb
                        dominantColor?.let {
                            val argbColor = Color(it or (0xFF shl 24))
                            onColorExtracted(argbColor)
                        }
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    fun extractSecondColorFromCoverUrl(context: Context, imageUrl: String, onColorExtracted: (Color) -> Unit) {
        Glide.with(context)
            .asBitmap()
            .load(imageUrl)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    val scaledBitmap = Bitmap.createScaledBitmap(resource, 50, 50, true)
                    Palette.from(scaledBitmap).generate { palette ->
                        val lightVibrantColor = palette?.mutedSwatch?.rgb ?: palette?.darkMutedSwatch?.rgb
                        lightVibrantColor?.let {
                            val argbColor = Color(it or (0xFF shl 24))
                            onColorExtracted(argbColor)
                        }
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }
}
