package com.twslatencylab

import android.content.Context
import android.media.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

class LatencyMeasurer(private val context: Context) {

    companion object {
        const val SAMPLE_RATE     = 48000
        const val NUM_TRIALS      = 15
        const val PRE_RECORD_MS   = 300
        const val POST_PLAY_MS    = 1500
        const val TOTAL_RECORD_MS = PRE_RECORD_MS + POST_PLAY_MS
        val PRE_RECORD_SAMPLES    = SAMPLE_RATE * PRE_RECORD_MS / 1000
        val TOTAL_RECORD_SAMPLES  = SAMPLE_RATE * TOTAL_RECORD_MS / 1000

        const val BEEP_FREQ_HZ  = 1000.0
        const val BEEP_MS       = 300
        const val TEMPLATE_MS   = 20
        
        // This is kept here so the ViewModel can access it, 
        // but we no longer subtract it inside this file!
        const val SYSTEM_OFFSET_MS = 35.0 

        fun median(v: List<Double>): Double {
            if (v.isEmpty()) return 0.0
            val s = v.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
        }

        fun percentile(v: List<Double>, p: Double): Double {
            if (v.isEmpty()) return 0.0
            val s = v.sorted()
            val i = (p / 100.0) * (s.size - 1)
            val lo = i.toInt()
            val hi = min(lo + 1, s.size - 1)
            return s[lo] + (s[hi] - s[lo]) * (i - lo)
        }
    }

    private fun generateBeep(): FloatArray {
        val beepSamples    = SAMPLE_RATE * BEEP_MS / 1000
        val fadeOutSamples = SAMPLE_RATE * 5 / 1000
        val fadeOutStart   = beepSamples - fadeOutSamples
        val signal         = FloatArray(beepSamples)

        for (i in 0 until beepSamples) {
            val amplitude = if (i > fadeOutStart) {
                (beepSamples - i).toDouble() / fadeOutSamples
            } else {
                1.0
            }
            signal[i] = (amplitude * cos(2.0 * PI * BEEP_FREQ_HZ * i / SAMPLE_RATE)).toFloat()
        }
        return signal
    }

    private fun generateTemplate(): FloatArray {
        val templateSamples = SAMPLE_RATE * TEMPLATE_MS / 1000
        val template        = FloatArray(templateSamples)

        for (i in 0 until templateSamples) {
            template[i] = cos(2.0 * PI * BEEP_FREQ_HZ * i / SAMPLE_RATE).toFloat()
        }
        return template
    }

    private fun detectArrival(recorded: FloatArray, recSize: Int, template: FloatArray, onLog: (String) -> Unit): Int? {
        val tLen = template.size
        
        val searchFrom = PRE_RECORD_SAMPLES + (SAMPLE_RATE * 0.010).toInt()
        val searchTo   = min(recSize - tLen, PRE_RECORD_SAMPLES + (SAMPLE_RATE * 0.800).toInt())
        
        if (searchFrom >= searchTo) return null

        val searchLength = searchTo - searchFrom
        val correlations = DoubleArray(searchLength)
        
        var tEnergy = 0.0
        for (v in template) tEnergy += v * v
        val tStdDev = sqrt(tEnergy)

        var globalMaxCorr = 0.0

        for (i in 0 until searchLength) {
            val lag = searchFrom + i
            var dotProduct = 0.0
            var sEnergy = 0.0
            
            for (k in 0 until tLen) {
                val r = recorded[lag + k].toDouble()
                dotProduct += r * template[k]
                sEnergy += r * r
            }
            
            val sStdDev = sqrt(sEnergy)
            if (sStdDev < 0.0001) {
                correlations[i] = 0.0
                continue 
            }
            
            val corr = dotProduct / (tStdDev * sStdDev)
            correlations[i] = corr
            if (corr > globalMaxCorr) globalMaxCorr = corr
        }

        val dynamicThreshold = globalMaxCorr * 0.70
        val absoluteFloor    = 0.12
        val targetThreshold  = max(dynamicThreshold, absoluteFloor)
        
        var firstArrivalIdx = -1
        for (i in 0 until searchLength) {
            if (correlations[i] >= targetThreshold) {
                if (i > 0 && i < searchLength - 1 && correlations[i] > correlations[i-1] && correlations[i] > correlations[i+1]) {
                    firstArrivalIdx = searchFrom + i
                    break
                }
            }
        }

        onLog("  [corr] max=${"%.3f".format(globalMaxCorr)} threshold=${"%.3f".format(targetThreshold)}")

        return if (firstArrivalIdx != -1) firstArrivalIdx else null
    }

    suspend fun runTrial(onLog: (String) -> Unit, forceSpeaker: Boolean = false): Double? =
        withContext(Dispatchers.IO) {

        val beep         = generateBeep()
        val template     = generateTemplate()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val inBufSize = max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT),
            TOTAL_RECORD_SAMPLES * 4
        )

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(inBufSize)
            .build()

        val usage      = if (forceSpeaker) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
        val streamType = if (forceSpeaker) AudioManager.STREAM_ALARM   else AudioManager.STREAM_MUSIC

        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(beep.size * 4) 
            .setTransferMode(AudioTrack.MODE_STATIC) 
            .build()

        val recorded = FloatArray(TOTAL_RECORD_SAMPLES)
        var totalRead = 0

        val originalMode         = audioManager.mode
        val originalSpeakerphone = audioManager.isSpeakerphoneOn
        val originalVolume       = audioManager.getStreamVolume(streamType)
        val maxVolume            = audioManager.getStreamMaxVolume(streamType)

        try {
            audioManager.setStreamVolume(streamType, maxVolume, 0)
            audioManager.mode             = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false

            player.write(beep, 0, beep.size, AudioTrack.WRITE_BLOCKING)

            // Minimal sleep to let the system stabilize, but not enough to freeze UI
            Thread.sleep(50)

            recorder.startRecording()
            Thread.sleep(PRE_RECORD_MS.toLong())

            player.setVolume(1.0f)
            player.play() 

            while (totalRead < TOTAL_RECORD_SAMPLES) {
                val toRead = min(2048, TOTAL_RECORD_SAMPLES - totalRead)
                val n = recorder.read(recorded, totalRead, toRead, AudioRecord.READ_BLOCKING)
                if (n > 0) totalRead += n else break
            }

        } finally {
            runCatching {
                audioManager.setStreamVolume(streamType, originalVolume, 0)
                audioManager.isSpeakerphoneOn = originalSpeakerphone
                audioManager.mode             = originalMode
            }
            runCatching { player.stop();   player.release()   }
            runCatching { recorder.stop(); recorder.release() }
        }

        if (totalRead < TOTAL_RECORD_SAMPLES / 2) return@withContext null

        val arrivalIdx = detectArrival(recorded, totalRead, template, onLog)

        if (arrivalIdx == null) {
            onLog("  [fail] match below threshold")
            return@withContext null
        }

        // --- CALCULATION LOGIC ---
        val netSamples = arrivalIdx - PRE_RECORD_SAMPLES
        val rawMs      = (netSamples.toDouble() / SAMPLE_RATE) * 1000.0
        
        onLog("  [ok] Measured=${"%.1f".format(rawMs)}ms")

        // Return the pure, raw result in seconds
        val result = rawMs / 1000.0

        if (result > 1.0) {
            onLog("  [discard] result exceeds 1.0s")
            return@withContext null
        }

        result
    }
}
