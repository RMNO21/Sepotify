package com.music.spotui.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL

/**
 * Pure-Kotlin ID3v2.4 metadata, album art, and synchronized/unsynchronized lyrics tagger.
 * Injects:
 * - TIT2 (Title)
 * - TPE1 (Lead Artist)
 * - TALB (Album)
 * - TRCK (Track Number)
 * - TPOS (Disc Number)
 * - TCON (Genre)
 * - TSRC (ISRC Code)
 * - APIC (Cover Art)
 * - USLT (Unsynchronized Lyric / Full text)
 * - SYLT (Synchronized Lyrics / Timestamped frames)
 *
 * Also writes an external standard `.lrc` file alongside the audio file for universal compatibility.
 */
object ID3Tagger {

    private const val TAG = "ID3Tagger"

    suspend fun writeTags(
        file: File,
        title: String,
        artist: String,
        album: String = "",
        trackNumber: String = "",
        discNumber: String = "",
        genre: String = "",
        isrc: String = "",
        lyricsText: String = "",
        syncedLrc: String = "",
        coverArtUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext

        try {
            // Write external .lrc sidecar file if synced lyrics are available
            if (syncedLrc.isNotBlank()) {
                val lrcFile = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
                runCatching {
                    lrcFile.writeText(syncedLrc, Charsets.UTF_8)
                    Log.d(TAG, "Saved companion LRC file: ${lrcFile.name}")
                }
            }

            var imageBytes: ByteArray? = null
            if (!coverArtUrl.isNullOrBlank() && (coverArtUrl.startsWith("http://") || coverArtUrl.startsWith("https://"))) {
                runCatching {
                    val conn = URL(coverArtUrl).openConnection()
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.inputStream.use { input ->
                        val raw = input.readBytes()
                        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                        if (bmp != null) {
                            val baos = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                            imageBytes = baos.toByteArray()
                        } else {
                            imageBytes = raw
                        }
                    }
                }.onFailure { Log.w(TAG, "Failed to download cover art for ID3: ${it.message}") }
            }

            val tagBytes = buildId3v24Tag(
                title = title,
                artist = artist,
                album = album,
                trackNumber = trackNumber,
                discNumber = discNumber,
                genre = genre,
                isrc = isrc,
                lyricsText = lyricsText,
                imageBytes = imageBytes
            )
            if (tagBytes.isEmpty()) return@withContext

            prependId3Tag(file, tagBytes)
            Log.d(TAG, "Successfully injected ID3v2.4 tags into ${file.name} for '$title - $artist'")
        } catch (e: Exception) {
            Log.w(TAG, "ID3 tag injection error: ${e.message}")
        }
    }

    private fun buildId3v24Tag(
        title: String,
        artist: String,
        album: String,
        trackNumber: String,
        discNumber: String,
        genre: String,
        isrc: String,
        lyricsText: String,
        imageBytes: ByteArray?
    ): ByteArray {
        val frameStream = ByteArrayOutputStream()

        fun writeTextFrame(frameId: String, text: String) {
            if (text.isBlank()) return
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val payload = ByteArray(1 + textBytes.size)
            payload[0] = 0x03 // UTF-8 descriptor
            System.arraycopy(textBytes, 0, payload, 1, textBytes.size)

            frameStream.write(frameId.toByteArray(Charsets.ISO_8859_1))
            val size = toSynchsafeInt(payload.size)
            frameStream.write(size)
            frameStream.write(0) // Flags byte 1
            frameStream.write(0) // Flags byte 2
            frameStream.write(payload)
        }

        // Standard ID3v2.4 text frames
        writeTextFrame("TIT2", title)
        writeTextFrame("TPE1", artist)
        writeTextFrame("TALB", album)
        if (trackNumber.isNotBlank()) writeTextFrame("TRCK", trackNumber)
        if (discNumber.isNotBlank()) writeTextFrame("TPOS", discNumber)
        if (genre.isNotBlank()) writeTextFrame("TCON", genre)
        if (isrc.isNotBlank()) writeTextFrame("TSRC", isrc)

        // USLT: Unsynchronized lyrics/text frame
        if (lyricsText.isNotBlank()) {
            val lyricsBytes = lyricsText.toByteArray(Charsets.UTF_8)
            val usltPayload = ByteArrayOutputStream()
            usltPayload.write(0x03) // UTF-8 descriptor
            usltPayload.write("eng".toByteArray(Charsets.ISO_8859_1)) // Language: English / Default
            usltPayload.write(0x00) // Content descriptor terminator (empty descriptor)
            usltPayload.write(lyricsBytes)

            val payload = usltPayload.toByteArray()
            frameStream.write("USLT".toByteArray(Charsets.ISO_8859_1))
            val size = toSynchsafeInt(payload.size)
            frameStream.write(size)
            frameStream.write(0)
            frameStream.write(0)
            frameStream.write(payload)
        }

        // APIC: Attached Picture (Cover art)
        if (imageBytes != null && imageBytes.isNotEmpty()) {
            val mime = "image/jpeg".toByteArray(Charsets.ISO_8859_1)
            val apicPayload = ByteArrayOutputStream()
            apicPayload.write(0x00) // ISO-8859-1 for mime
            apicPayload.write(mime)
            apicPayload.write(0x00) // Null terminator
            apicPayload.write(0x03) // Picture type: Cover (front)
            apicPayload.write(0x00) // Description null terminator
            apicPayload.write(imageBytes)

            val payload = apicPayload.toByteArray()
            frameStream.write("APIC".toByteArray(Charsets.ISO_8859_1))
            val size = toSynchsafeInt(payload.size)
            frameStream.write(size)
            frameStream.write(0)
            frameStream.write(0)
            frameStream.write(payload)
        }

        val allFrames = frameStream.toByteArray()
        if (allFrames.isEmpty()) return ByteArray(0)

        val headerStream = ByteArrayOutputStream()
        headerStream.write("ID3".toByteArray(Charsets.ISO_8859_1))
        headerStream.write(0x04) // Major version: ID3v2.4.0
        headerStream.write(0x00) // Revision
        headerStream.write(0x00) // Flags

        val synchsafe = toSynchsafeInt(allFrames.size)
        headerStream.write(synchsafe)
        headerStream.write(allFrames)

        return headerStream.toByteArray()
    }

    private fun toSynchsafeInt(size: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = ((size shr 21) and 0x7F).toByte()
        b[1] = ((size shr 14) and 0x7F).toByte()
        b[2] = ((size shr 7) and 0x7F).toByte()
        b[3] = (size and 0x7F).toByte()
        return b
    }

    private fun prependId3Tag(file: File, tagBytes: ByteArray) {
        val existingHeader = ByteArray(10)
        var hasExistingId3 = false
        var existingTagSize = 0

        FileInputStream(file).use { fis ->
            val read = fis.read(existingHeader)
            if (read >= 10 && existingHeader[0] == 'I'.code.toByte() && existingHeader[1] == 'D'.code.toByte() && existingHeader[2] == '3'.code.toByte()) {
                hasExistingId3 = true
                existingTagSize = 10 + (
                    ((existingHeader[6].toInt() and 0x7F) shl 21) or
                        ((existingHeader[7].toInt() and 0x7F) shl 14) or
                        ((existingHeader[8].toInt() and 0x7F) shl 7) or
                        (existingHeader[9].toInt() and 0x7F)
                    )
            }
        }

        val tempOut = File(file.parentFile, "${file.name}.id3tmp")
        FileOutputStream(tempOut).use { fos ->
            fos.write(tagBytes)
            FileInputStream(file).use { fis ->
                if (hasExistingId3 && existingTagSize > 0) {
                    fis.skip(existingTagSize.toLong())
                }
                fis.copyTo(fos)
            }
        }

        if (tempOut.exists() && tempOut.length() > tagBytes.size) {
            file.delete()
            tempOut.renameTo(file)
        } else {
            tempOut.delete()
        }
    }
}
