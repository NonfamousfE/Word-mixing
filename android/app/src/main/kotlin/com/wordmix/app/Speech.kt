package com.wordmix.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import java.net.URLEncoder
import java.util.Locale

/**
 * 单词发音：双引擎架构。
 *
 * 1. 优先：在线纯正真人发音（有道云发音接口，返回标准 MP3 音频），音质纯正自然，
 *    解决国内 Android 手机（小米/华为/OPPO/vivo等）普遍未预装 Google 英文 TTS 导致静音的问题。
 * 2. 兜底：离线或网络异常时，自动无缝降级为系统自带的 TextToSpeech 本地语音合成。
 */
object Speech {

    private const val TAG = "WordMixSpeech"
    private var tts: TextToSpeech? = null
    private var ready = false
    private var mediaPlayer: MediaPlayer? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                runCatching {
                    tts?.language = Locale.US
                }
            }
        }
    }

    /** 朗读单词：优先播放在线真人纯正发音，失败自动降级到系统 TTS */
    fun say(ctx: Context, word: String) {
        val clean = word.trim()
        if (clean.isBlank()) return
        init(ctx)
        playOnline(ctx, clean)
    }

    private fun playOnline(ctx: Context, word: String) {
        val appContext = ctx.applicationContext
        uiHandler.post {
            try {
                stopPlayer()
                val encoded = URLEncoder.encode(word, "UTF-8")
                val url = "https://dict.youdao.com/dictvoice?audio=$encoded&type=2"

                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(appContext, Uri.parse(url))
                    setOnPreparedListener { mp ->
                        try {
                            mp.start()
                        } catch (e: Exception) {
                            Log.w(TAG, "MediaPlayer start error: ${e.message}, fallback to TTS")
                            speakTts(appContext, word)
                        }
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.w(TAG, "MediaPlayer error ($what, $extra), fallback to TTS")
                        stopPlayer()
                        speakTts(appContext, word)
                        true
                    }
                    setOnCompletionListener {
                        stopPlayer()
                    }
                    prepareAsync()
                }
                mediaPlayer = player
            } catch (e: Exception) {
                Log.w(TAG, "playOnline error: ${e.message}, fallback to TTS")
                speakTts(appContext, word)
            }
        }
    }

    private fun stopPlayer() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun speakTts(ctx: Context, word: String) {
        if (!ready || tts == null) {
            uiHandler.post {
                Toast.makeText(ctx, "朗读失败：网络不可用且设备未安装英文语音包", Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            val res = tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, "wm-" + word)
            if (res == TextToSpeech.ERROR) {
                uiHandler.post {
                    Toast.makeText(ctx, "系统语音朗读失败", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak exception: ${e.message}")
        }
    }

    fun shutdown() {
        stopPlayer()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    fun available(): Boolean = true
}
