package com.twslatencylab

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MeasurementViewModel : ViewModel() {

    var phase    by mutableStateOf(Phase.IDLE)
    var step     by mutableStateOf(0)
    var errorMsg by mutableStateOf("")
    var baseline by mutableStateOf<Double?>(null)
    var bsRaw    by mutableStateOf(listOf<Double>())
    var result   by mutableStateOf<MeasurementResult?>(null)

    val log = mutableStateListOf<LogEntry>()

    var gameComp by mutableStateOf(70.0) 
    val brainAdj = 15.0

    // Gaming Mode Toggle State
    var isGamingMode by mutableStateOf(false)

    private var measurer: LatencyMeasurer? = null

    fun init(context: Context) {
        if (measurer == null) {
            measurer = LatencyMeasurer(context.applicationContext)
        }
    }

    private fun push(text: String, type: LogType = LogType.INFO) {
        viewModelScope.launch(Dispatchers.Main) {
            if (log.size > 100) log.removeAt(0)
            log.add(LogEntry(text, type))
        }
    }

    fun startBaseline() {
        val m = measurer ?: return
        log.clear()
        phase = Phase.BASELINE
        step  = 0
        push("━━━ PHASE 1 · DEVICE BASELINE ━━━", LogType.HEADER)
        
        viewModelScope.launch(Dispatchers.Default) {
            val raw = mutableListOf<Double>()
            repeat(LatencyMeasurer.NUM_TRIALS) { i ->
                step = i + 1
                val d = m.runTrial(onLog = { msg -> push(msg) }, forceSpeaker = true)
                if (d != null) raw.add(d)
                
                yield() 
                delay(300) 
            }

            if (raw.isEmpty()) {
                errorMsg = "No signal detected during baseline."
                phase = Phase.ERROR
                return@launch
            }

            baseline = LatencyMeasurer.median(raw)
            bsRaw    = raw.toList()
            phase = Phase.READY_TWS
        }
    }

    fun startTWS() {
        val m = measurer ?: return
        phase = Phase.TWS
        step  = 0
        push("━━━ PHASE 2 · TWS BLUETOOTH ━━━", LogType.HEADER)

        viewModelScope.launch(Dispatchers.Default) {
            val raw = mutableListOf<Double>()
            repeat(LatencyMeasurer.NUM_TRIALS) { i ->
                step = i + 1
                val d = m.runTrial(onLog = { msg -> push(msg) }, forceSpeaker = false)
                if (d != null) raw.add(d)
                
                yield() 
                delay(300)
            }

            if (raw.isEmpty() || baseline == null) {
                errorMsg = "No signal detected from earbuds."
                phase = Phase.ERROR
                return@launch
            }

            val bsMed  = baseline!!
            val twsMed = LatencyMeasurer.median(raw)
            
            val bsMedMs  = bsMed * 1000.0
            val twsMedMs = twsMed * 1000.0

            // The DSP Tax Bypass for Modern Devices (A17 Fix)
            val effectiveBaselineMs = if (isGamingMode && bsMedMs > 40.0) {
                push("Notice: Capping baseline deduction due to Gaming Mode BT path.", LogType.INFO)
                min(bsMedMs, 25.0)
            } else {
                bsMedMs
            }

            // 1. Calculate relative latency
            val latMs  = max(0.0, twsMedMs - effectiveBaselineMs)
            val nets   = raw.map { max(0.0, (it * 1000.0) - effectiveBaselineMs) }
            
            // 2. Dynamic A2DP Buffer Estimation
            val stdDev = sqrt(nets.map { (it - latMs) * (it - latMs) }.average())
            
            val a2dpBuffer = if (isGamingMode) {
                20.0 
            } else {
                when {
                    stdDev < 5.0  -> 150.0
                    stdDev < 15.0 -> 200.0
                    stdDev < 30.0 -> 220.0
                    else          -> 240.0
                }
            }

            // 3. App/OS Processing Tax
            val calibrationOffset = if (isGamingMode) 15.0 else 35.0 
            
            // Final Corrected Latency
            val correctedLatMs = max(0.0, latMs - a2dpBuffer - calibrationOffset)

            val p25 = LatencyMeasurer.percentile(nets, 25.0)
            val p75 = LatencyMeasurer.percentile(nets, 75.0)
            val perceived = max(0.0, correctedLatMs - gameComp - brainAdj)

            result = MeasurementResult(
                latMs               = latMs,
                a2dpBufferMs        = a2dpBuffer,
                systemCalibrationMs = calibrationOffset,
                correctedLatMs      = correctedLatMs,
                loMs                = p25,
                hiMs                = p75,
                twsMedianMs         = twsMedMs,
                baselineMs          = bsMedMs, 
                validTrials         = raw.size,
                netDelaysMs         = nets,
                bsDelaysMs          = bsRaw.map { it * 1000.0 },
                gameCompMs          = gameComp,
                brainAdjMs          = brainAdj,
                perceivedLatencyMs  = perceived
            )
            phase = Phase.DONE
        }
    }

    fun reset() {
        phase = Phase.IDLE
        step = 0
        baseline = null
        bsRaw = emptyList()
        result = null
        errorMsg = ""
        log.clear()
    }
}
