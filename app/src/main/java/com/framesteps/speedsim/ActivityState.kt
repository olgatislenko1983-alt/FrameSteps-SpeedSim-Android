package com.framesteps.speedsim

import android.os.SystemClock

/** Последнее наблюдение распознавания активности ОС. Переносится между тиками, возраст растёт (Слой 2: window_activity_last). */
object ActivityState {
    @Volatile var code: String? = null
    @Volatile var confidence: Int = 0
    @Volatile var observedElapsedMs: Long = 0L

    fun set(code: String, confidence: Int) {
        this.code = code
        this.confidence = confidence
        this.observedElapsedMs = SystemClock.elapsedRealtime()
    }

    /** Возраст наблюдения в секундах, null — наблюдений ещё не было. */
    fun ageSec(): Double? =
        if (code == null) null else (SystemClock.elapsedRealtime() - observedElapsedMs) / 1000.0
}
