package com.example.animevoice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class VoiceOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.example.animevoice.START"
        const val ACTION_STOP = "com.example.animevoice.STOP"
        private const val CHANNEL_ID = "anime_voice_channel"
        private const val NOTIF_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var bubbleView: View? = null
    private var menuView: View? = null
    private var statusView: TextView? = null
    private var isMenuOpen = false

    private val isRunning = AtomicBoolean(false)
    private var audioThread: Thread? = null
    private var dsp: VoiceDspProcessor? = null
    private var activePreset = AnimeVoicePreset.ANIME_GIRL
    @Volatile private var micStatusText = "Mic: Starting..."

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        if (bubbleView == null) {
            showFloatingBubble()
        }
        startAudioEngine()

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning.set(false)
        audioThread?.interrupt()
        audioThread = null

        bubbleView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        menuView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        bubbleView = null
        menuView = null
        statusView = null
        super.onDestroy()
    }

    private fun updateStatus(text: String) {
        micStatusText = text
        mainHandler.post {
            statusView?.text = text
        }
    }

    private fun startAudioEngine() {
        if (isRunning.getAndSet(true)) return

        audioThread = Thread {
            val sampleRate = selectSupportedSampleRate()
            val processor = VoiceDspProcessor(sampleRate).apply {
                applyPreset(activePreset)
                enabled = false
            }
            dsp = processor

            val minRecBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val minPlayBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val chunkFrames = 256
            val recBufBytes = maxOf(minRecBuf, chunkFrames * 4)
            val playBufBytes = maxOf(minPlayBuf, chunkFrames * 4)

            var recorder: AudioRecord? = null
            var track: AudioTrack? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null

            try {
                recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(recBufBytes)
                    .build()

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(playBufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()

                if (recorder.state != AudioRecord.STATE_INITIALIZED ||
                    track.state != AudioTrack.STATE_INITIALIZED
                ) {
                    updateStatus("Mic error: Audio hardware busy")
                    return@Thread
                }

                if (AcousticEchoCanceler.isAvailable()) {
                    aec = runCatching {
                        AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = runCatching {
                        NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                }

                val inSamples = ShortArray(chunkFrames)
                val outSamples = ShortArray(chunkFrames)

                recorder.startRecording()
                track.play()
                updateStatus("Mic ready (Hold near ear)")

                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    val readCount = recorder.read(inSamples, 0, chunkFrames)
                    if (readCount > 0) {
                        if (processor.enabled) {
                            processor.processBlock(inSamples, outSamples, readCount)
                            track.write(outSamples, 0, readCount)
                        }
                    } else if (readCount < 0) {
                        updateStatus("Mic error ($readCount)")
                        break
                    }
                }
            } catch (_: SecurityException) {
                updateStatus("Mic permission denied")
            } catch (_: Exception) {
                updateStatus("Mic blocked by another app")
            } finally {
                runCatching { aec?.release() }
                runCatching { ns?.release() }
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                runCatching { track?.stop() }
                runCatching { track?.release() }
                isRunning.set(false)
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun selectSupportedSampleRate(): Int {
        for (rate in intArrayOf(48000, 44100, 16000)) {
            val size = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (size > 0) return rate
        }
        return 44100
    }

    private fun showFloatingBubble() {
        val params = WindowManager.LayoutParams(
            dp(54),
            dp(54),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(180)
        }

        val bubble = TextView(this).apply {
            text = "VA"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF4081"))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(6).toFloat()
        }

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (event.rawX - touchX).toInt()
                    params.y = initY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - touchX) < 12 && abs(event.rawY - touchY) < 12) {
                        toggleMenu()
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = bubble
        windowManager.addView(bubble, params)
    }

    private fun toggleMenu() {
        if (isMenuOpen) {
            menuView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            menuView = null
            statusView = null
            isMenuOpen = false
        } else {
            showMenu()
            isMenuOpen = true
        }
    }

    private fun showMenu() {
        val params = WindowManager.LayoutParams(
            dp(275),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#EE161822"))
                setStroke(dp(1), Color.parseColor("#FF4081"))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Anime Voice Menu"
            setTextColor(Color.WHITE)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val masterSwitch = Switch(this).apply {
            isChecked = dsp?.enabled == true
            setOnCheckedChangeListener { _, checked ->
                dsp?.enabled = checked
            }
        }
        header.addView(title)
        header.addView(masterSwitch)
        container.addView(header)

        val status = TextView(this).apply {
            text = micStatusText
            setTextColor(Color.parseColor("#80CBC4"))
            textSize = 11f
            setPadding(0, dp(2), 0, dp(6))
        }
        statusView = status
        container.addView(status)

        val pitchLabel = TextView(this).apply {
            text = "Pitch: ${"%.2f".format(dsp?.pitchRatio ?: activePreset.pitchRatio)}x"
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(2))
        }
        val pitchSeek = SeekBar(this).apply {
            max = 150
            progress = (((dsp?.pitchRatio ?: activePreset.pitchRatio) - 0.5f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val ratio = 0.5f + p / 100f
                    if (fromUser) dsp?.pitchRatio = ratio
                    pitchLabel.text = "Pitch: ${"%.2f".format(ratio)}x"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(pitchLabel)
        container.addView(pitchSeek)

        AnimeVoicePreset.entries.forEach { preset ->
            val btn = Button(this).apply {
                text = preset.displayName
                textSize = 12f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(
                        if (preset == activePreset) Color.parseColor("#FF4081")
                        else Color.parseColor("#2A2D3D")
                    )
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(36)
                ).apply { topMargin = dp(6) }
                layoutParams = lp

                setOnClickListener {
                    activePreset = preset
                    dsp?.applyPreset(preset)
                    pitchSeek.progress = ((preset.pitchRatio - 0.5f) * 100).toInt()
                    if (preset != AnimeVoicePreset.NORMAL && dsp?.enabled == false) {
                        dsp?.enabled = true
                        masterSwitch.isChecked = true
                    }
                    toggleMenu()
                    toggleMenu()
                }
            }
            container.addView(btn)
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val closeMenuBtn = Button(this).apply {
            text = "Minimize"
            textSize = 12f
            setOnClickListener { toggleMenu() }
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        }
        val stopAppBtn = Button(this).apply {
            text = "Stop"
            textSize = 12f
            setOnClickListener { stopSelf() }
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        }
        footer.addView(closeMenuBtn)
        footer.addView(stopAppBtn)
        container.addView(footer)

        menuView = container
        windowManager.addView(container, params)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Anime Voice Overlay",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, VoiceOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anime Voice Overlay Active")
            .setContentText("Tap the floating VA bubble to switch anime voices.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }
}
