package com.example.animevoice

enum class AnimeVoicePreset(
    val displayName: String,
    val pitchRatio: Float,
    val robotMix: Float,
    val echoMix: Float
) {
    NORMAL("Normal", 1.00f, 0.0f, 0.0f),
    ANIME_GIRL("Anime Girl", 1.45f, 0.0f, 0.08f),
    CHIBI("Chibi Mascot", 1.75f, 0.0f, 0.05f),
    SHONEN("Shonen Hero", 1.15f, 0.0f, 0.05f),
    DEMON_LORD("Demon Lord", 0.68f, 0.0f, 0.35f),
    MECHA("Mecha / Robot", 0.90f, 0.75f, 0.15f)
}
