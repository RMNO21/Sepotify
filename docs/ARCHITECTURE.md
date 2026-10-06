# Sepotify Audio Engine Architecture

## 1. System Overview
Sepotify is an offline and low-latency audio streaming playback system engineered for Android using Kotlin and Jetpack Media3 APIs.

## 2. Core Subsystems
- **Audio Service Layer**: Background Service implementing `MediaSessionCompat` and foreground playback notification channels.
- **Audio Decoding Pipeline**: Multi-format decoders supporting lossless FLAC, Opus, AAC, and standard MP3 bitstreams.
- **Buffer Preload Queue**: Double-buffering architecture preventing underrun clicks on high-sample-rate audio files (up to 24-bit 96kHz).
- **State Machine**: Deterministic state transitions handling AudioFocus loss, headset plug/unplug events, and OS lifecycle calls.

## 3. Data Flow
```
User / UI -> MediaController -> MediaSessionService -> ExoPlayer Engine -> AudioTrack HAL
```
