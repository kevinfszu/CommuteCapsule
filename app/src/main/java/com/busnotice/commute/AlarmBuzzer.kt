package com.busnotice.commute

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper

/**
 * 到站前的响铃。走闹钟流，避免通知渠道被用户改成静音后失效；
 * 只响几秒，不循环占用音频焦点。戴耳机时由系统决定走耳机还是扬声器，App 不强制。
 */
object AlarmBuzzer {

    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var audio: AudioManager? = null
    private var token = 0

    fun beep(ctx: Context) = play(ctx, 800L)

    fun ring(ctx: Context, long: Boolean = false) = play(ctx, if (long) 4_500L else 3_000L)

    fun stop() {
        token++
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { audio?.abandonAudioFocus(null) }
        audio = null
    }

    @Suppress("DEPRECATION")
    private fun play(ctx: Context, durationMs: Long) {
        stop()
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            audio = am
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(ctx.applicationContext, uri) ?: return@runCatching
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone = r
            r.play()
            val my = ++token
            main.postDelayed({ if (my == token) stop() }, durationMs)
        }
    }
}
