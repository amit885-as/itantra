package com.amit.itantra

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

class EmergencyAudioManager(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var originalVolume: Int = 0

    fun startAlarm() {
        Log.e("EmergencyAudio", "startAlarm() called. Setting up...")

        // If the player was left dangling or wasn't cleaned up, release it first.
        if (mediaPlayer != null) {
            Log.e("EmergencyAudio", "mediaPlayer was not null before start! Forcing cleanup.")
            stopAlarm() 
        }

        try {
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            Log.e("EmergencyAudio", "Setting STREAM_ALARM to max ($maxVolume). Original: $originalVolume")
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
        } catch (e: Exception) {
            Log.e("EmergencyAudio", "Volume override failed", e)
        }

        try {
            Log.e("EmergencyAudio", "Requesting Audio Focus...")
            audioManager.requestAudioFocus(
                null, 
                AudioManager.STREAM_ALARM, 
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        } catch (e: Exception) {
            Log.e("EmergencyAudio", "Audio Focus request failed", e)
        }

        try {
            Log.e("EmergencyAudio", "Opening raw resource file descriptor...")
            val resId = context.resources.getIdentifier("emergency_alarm", "raw", context.packageName)
            val afd = if (resId != 0) context.resources.openRawResourceFd(resId) else null
            if (afd == null) {
                Log.e("EmergencyAudio", "CRITICAL: emergency_alarm raw resource not found! Cannot play sound.")
                return
            }

            Log.e("EmergencyAudio", "Initializing MediaPlayer...")
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                prepare()
                
                Log.e("EmergencyAudio", "MediaPlayer prepared successfully. Starting playback...")
                start()
            }
        } catch (e: Exception) {
            Log.e("EmergencyAudio", "CRITICAL ERROR during MediaPlayer setup/playback", e)
            try {
                mediaPlayer?.release()
            } catch (releaseEx: Exception) {
                Log.e("EmergencyAudio", "Failed to release corrupted MediaPlayer", releaseEx)
            }
            mediaPlayer = null
        }
    }

    fun stopAlarm() {
        Log.e("EmergencyAudio", "stopAlarm() called.")
        
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    Log.e("EmergencyAudio", "MediaPlayer is playing. Calling stop()...")
                    it.stop()
                }
            }
        } catch (e: Exception) {
            Log.e("EmergencyAudio", "Error during mediaPlayer.stop()", e)
        }

        try {
            Log.e("EmergencyAudio", "Calling mediaPlayer.release()...")
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e("EmergencyAudio", "Error during mediaPlayer.release()", e)
        } finally {
            mediaPlayer = null
            Log.e("EmergencyAudio", "mediaPlayer safely nulled out.")
        }

        try {
            Log.e("EmergencyAudio", "Restoring volume ($originalVolume) and abandoning focus.")
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
            audioManager.abandonAudioFocus(null)
        } catch (e: Exception) {
            Log.e("EmergencyAudio", "Failed to restore volume", e)
        }
    }
}