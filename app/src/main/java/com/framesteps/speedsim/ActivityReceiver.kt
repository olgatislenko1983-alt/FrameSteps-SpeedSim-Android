package com.framesteps.speedsim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Принимает события Activity Recognition (Google Play services) и приводит их к перечню эталона
 * activity_code ∈ {WALKING, RUNNING, STILL, VEHICLE, UNKNOWN} (Слой 2, раздел 3).
 *
 * Решение адаптера (не из эталона, подтвердить): ON_FOOT, ON_BICYCLE и TILTING в перечень не входят
 * и пропускаются; если подходящих активностей нет — UNKNOWN.
 */
class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val sorted = result.probableActivities.sortedByDescending { it.confidence }
        for (a in sorted) {
            val code = when (a.type) {
                DetectedActivity.IN_VEHICLE -> "VEHICLE"
                DetectedActivity.RUNNING -> "RUNNING"
                DetectedActivity.WALKING -> "WALKING"
                DetectedActivity.STILL -> "STILL"
                else -> null
            }
            if (code != null) {
                ActivityState.set(code, a.confidence)
                return
            }
        }
        ActivityState.set("UNKNOWN", sorted.firstOrNull()?.confidence ?: 0)
    }
}
