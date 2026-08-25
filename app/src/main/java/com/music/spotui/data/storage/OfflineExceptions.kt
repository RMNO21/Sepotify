package com.music.spotui.data.storage

class InsufficientStorageException(
    override val message: String = "Offline storage limit reached. Please free up space or increase storage quota."
) : Exception(message)

class UnavailableOfflineException(
    override val message: String = "This track is not available offline"
) : Exception(message)

class OfflineBufferExhaustedException(
    override val message: String = "Playback stopped: Audio buffer exhausted while offline"
) : Exception(message)
