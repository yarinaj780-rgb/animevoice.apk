package com.example.animevoice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

class VoiceDspProcessor(private val sampleRate: Int) {

    @Volatile var enabled: Boolean = false
    @Volatile var pitchRatio: Float = 1.45f
    @Volatile var robotMix: Float = 0.0f
    @Volatile var echoMix: Float = 0.08f
    @Volatile var noiseGateThreshold: Float = 0.012f

    private val bufSize = 4096
    private val bufMask = bufSize - 1
    private val pitchBuffer = FloatArray(bufSize)
    private var writeIdx = 0

    private val windowSize = 2048f
    private var phase = 0f

    private val echoDelaySamples = (sampleRate * 0.18f).toInt().coerceAtLeast(1)
    private val echoBuffer = FloatArray(echoDelaySamples)
    private var echoIdx = 0

    private var modPhase = 0.0
    private val modFreqHz = 55.0

    private var env = 0f

    fun applyPreset(preset: AnimeVoicePreset) {
        pitchRatio = preset.pitchRatio
        robotMix = preset.robotMix
        echoMix = preset.echoMix
    }

    fun processBlock(input: ShortArray, output: ShortArray, count: Int) {
        val isOn = enabled
        val pRatio = pitchRatio.coerceIn(0.5f, 2.2f)
        val rMix = robotMix.coerceIn(0f, 1f)
        val eMix = echoMix.coerceIn(0f, 0.8f)
        val gate = noiseGateThreshold

        val phaseDelta = (1.0f - pRatio) / windowSize
        val modInc = 2.0 * PI * modFreqHz / sampleRate

        for (i in 0 until count) {
            val raw = input[i] / 32768.0f

            if (!isOn) {
                output[i] = input[i]
                continue
            }

            val absIn = abs(raw)
            env = 0.992f * env + 0.008f * absIn
            val gated = if (env < gate) raw * (env / gate).coerceIn(0f, 1f) else raw

            pitchBuffer[writeIdx] = gated

            var sample = if (abs(pRatio - 1.0f) < 0.01f) {
                gated
            } else {
                val p1 = phase
                val p2 = if (phase + 0.5f >= 1.0f) phase - 0.5f else phase + 0.5f

                val d1 = 2f + p1 * windowSize
                val d2 = 2f + p2 * windowSize

                val s1 = readInterpolated(d1)
                val s2 = readInterpolated(d2)

                val g1 = 0.5f * (1.0f - cos(2.0 * PI * p1).toFloat())
                val g2 = 0.5f * (1.0f - cos(2.0 * PI * p2).toFloat())

                phase += phaseDelta
                while (phase >= 1.0f) phase -= 1.0f
                while (phase < 0.0f) phase += 1.0f

                s1 * g1 + s2 * g2
            }

            writeIdx = (writeIdx + 1) and bufMask

            if (rMix > 0.01f) {
                val mod = sin(modPhase).toFloat()
                modPhase += modInc
                if (modPhase >= 2.0 * PI) modPhase -= 2.0 * PI
                sample = sample * (1.0f - rMix) + (sample * mod) * rMix
            }

            val delayed = echoBuffer[echoIdx]
            echoBuffer[echoIdx] = sample + delayed * 0.35f
            echoIdx = (echoIdx + 1) % echoDelaySamples
            sample += delayed * eMix

            val limited = tanh((sample * 1.15f).toDouble()).toFloat()
            val outInt = (limited * 32767.0f).toInt().coerceIn(-32768, 32767)
            output[i] = outInt.toShort()
        }
    }

    private fun readInterpolated(delay: Float): Float {
        val pos = writeIdx - delay + bufSize
        val idx0 = pos.toInt() and bufMask
        val idx1 = (idx0 + 1) and bufMask
        val frac = pos - pos.toInt()
        return pitchBuffer[idx0] * (1.0f - frac) + pitchBuffer[idx1] * frac
    }
}
