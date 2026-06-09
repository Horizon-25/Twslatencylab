package com.twslatencylab

// Put the "Shared" definitions here so everyone can see them
enum class Phase {
    IDLE, BASELINE, READY_TWS, TWS, DONE, ERROR
}

enum class LogType { 
    INFO, SUCCESS, HEADER, RESULT 
}

data class LogEntry(val text: String, val type: LogType)

data class MeasurementResult(
    val latMs:              Double,
    val a2dpBufferMs:       Double, 
    val systemCalibrationMs: Double, 
    val correctedLatMs:     Double, 
    val loMs:               Double,
    val hiMs:               Double,
    val twsMedianMs:        Double,
    val baselineMs:         Double,
    val validTrials:        Int,
    val netDelaysMs:        List<Double>,
    val bsDelaysMs:         List<Double>,
    val gameCompMs:         Double,
    val brainAdjMs:         Double,
    val perceivedLatencyMs: Double
)
