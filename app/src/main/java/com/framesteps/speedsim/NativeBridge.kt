package com.framesteps.speedsim

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Мост «датчики ОС → симулятор». В WebView доступен как window.FSNative.
 * Все методы возвращают строки (JSON), формат описан в docs/BRIDGE.md.
 *
 * Сбор следует Слою 2/3 эталона:
 *  - позиция: fused location (скорость ОС, speed_accuracy, mock-флаг, монотонное время);
 *  - IMU: TYPE_LINEAR_ACCELERATION, при отсутствии — высокочастотный фильтр TYPE_ACCELEROMETER;
 *    sma = среднее(|ax|+|ay|+|az|) за тик;
 *  - шаги: TYPE_STEP_COUNTER (накопленное), steps_delta = разность между тиками, null на первом тике
 *    и при откате счётчика;
 *  - активность: Activity Recognition, null если наблюдений нет.
 */
class NativeBridge(private val act: MainActivity) : SensorEventListener {

    private val app: Context = act.applicationContext
    private val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fused = LocationServices.getFusedLocationProviderClient(app)
    private val lock = Any()

    private class Fix(
        val id: Long, val lat: Double, val lon: Double, val acc: Double,
        val speed: Double?, val speedAcc: Double?, val tsMs: Long, val elapsedNs: Long, val mock: Boolean
    )

    private var running = false
    private var fix: Fix? = null
    private var fixSeq = 0L

    private var smaSum = 0.0
    private var imuN = 0
    private var imuTotal = 0L
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var gInit = false

    private var stepTotal = -1f
    private var prevSteps = -1f

    private val linearSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val stepSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private val activityIntent: PendingIntent by lazy {
        val i = Intent(app, ActivityReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        PendingIntent.getBroadcast(app, 1, i, flags)
    }

    private val locCb = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l: Location = result.lastLocation ?: return
            synchronized(lock) {
                fixSeq++
                fix = Fix(
                    id = fixSeq,
                    lat = l.latitude,
                    lon = l.longitude,
                    acc = if (l.hasAccuracy()) l.accuracy.toDouble() else 9999.0,
                    speed = if (l.hasSpeed()) l.speed.toDouble() else null,
                    speedAcc = if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond.toDouble() else null,
                    tsMs = l.time,
                    elapsedNs = l.elapsedRealtimeNanos,
                    mock = isMock(l)
                )
            }
        }
    }

    private fun isMock(l: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) l.isMock else legacyMock(l)

    @Suppress("DEPRECATION")
    private fun legacyMock(l: Location): Boolean = l.isFromMockProvider

    private fun has(perm: String) =
        app.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun locationServiceOn(): Boolean =
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    private fun stepPermOk() = Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACTIVITY_RECOGNITION)

    private fun playServicesOk() =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS

    private fun err(code: String) = JSONObject().put("ok", false).put("error", code).toString()

    // ---------------------------------------------------------------- JS API

    /** Запуск датчиков. {ok:true} либо {ok:false,error:"LOCATION_PERMISSION_MISSING"|"LOCATION_SERVICE_DISABLED"}. */
    @JavascriptInterface
    fun start(): String {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            act.runOnUiThread { act.askPermissions() }
            return err("LOCATION_PERMISSION_MISSING")
        }
        if (!locationServiceOn()) return err("LOCATION_SERVICE_DISABLED")
        synchronized(lock) {
            fix = null
            smaSum = 0.0; imuN = 0; imuTotal = 0L; gInit = false
            stepTotal = -1f; prevSteps = -1f
            running = true
        }
        act.runOnUiThread { registerAll() }
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface
    fun stop(): String {
        synchronized(lock) { running = false }
        act.runOnUiThread { unregisterAll() }
        return "ok"
    }

    /** Состояние источников для панели диагностики. */
    @JavascriptInterface
    fun caps(): String = synchronized(lock) {
        val o = JSONObject()
        val f = fix
        val fixAge = if (f != null) (SystemClock.elapsedRealtimeNanos() - f.elapsedNs) / 1e9 else Double.MAX_VALUE
        o.put("locationPerm", has(Manifest.permission.ACCESS_FINE_LOCATION))
        o.put("gpsEnabled", locationServiceOn())
        o.put("fixing", running && fixAge < 5.0)
        o.put("accel", linearSensor != null || accelSensor != null)
        o.put("imuN", imuTotal)
        o.put("stepPerm", stepPermOk())
        o.put("stepCounter", stepSensor != null)
        o.put("stepDetector", false)
        o.put("actPerm", stepPermOk())
        o.put("actAvail", playServicesOk())
        val code = ActivityState.code
        o.put("actCode", code ?: JSONObject.NULL)
        val age = ActivityState.ageSec()
        o.put("actAge", age ?: JSONObject.NULL)
        o.toString()
    }

    /**
     * Срез за прошедший тик (вызывается раз в секунду). Накопители IMU и дельта шагов сбрасываются.
     * Поля: fix{id,lat,lon,acc,speed|null,sacc|null,ts,age,mock}|null, steps|null, sma|null, imuN,
     * act{code,conf,age}|null.
     */
    @JavascriptInterface
    fun sample(): String = synchronized(lock) {
        val o = JSONObject()
        val f = fix
        if (f != null) {
            val fo = JSONObject()
            fo.put("id", f.id)
            fo.put("lat", f.lat)
            fo.put("lon", f.lon)
            fo.put("acc", f.acc)
            fo.put("speed", f.speed ?: JSONObject.NULL)
            fo.put("sacc", f.speedAcc ?: JSONObject.NULL)
            fo.put("ts", f.tsMs)
            fo.put("age", maxOf(0.0, (SystemClock.elapsedRealtimeNanos() - f.elapsedNs) / 1e9))
            fo.put("mock", f.mock)
            o.put("fix", fo)
        } else {
            o.put("fix", JSONObject.NULL)
        }

        var steps: Int? = null
        if (stepSensor != null && stepTotal >= 0f) {
            if (prevSteps >= 0f) {
                val d = stepTotal - prevSteps
                if (d >= 0f) steps = d.toInt()   // d < 0 — откат счётчика, steps_delta = null
            }
            prevSteps = stepTotal
        }
        o.put("steps", steps ?: JSONObject.NULL)

        val sma = if (imuN > 0) smaSum / imuN else Double.NaN
        o.put("sma", if (sma.isNaN() || sma.isInfinite()) JSONObject.NULL else sma)
        o.put("imuN", imuN)
        smaSum = 0.0
        imuN = 0

        val code = ActivityState.code
        if (code != null) {
            val ao = JSONObject()
            ao.put("code", code)
            ao.put("conf", ActivityState.confidence)
            ao.put("age", ActivityState.ageSec() ?: 0.0)
            o.put("act", ao)
        } else {
            o.put("act", JSONObject.NULL)
        }
        o.toString()
    }

    @JavascriptInterface
    fun requestPermissions() {
        act.runOnUiThread { act.askPermissions() }
    }

    /** Сохранение текста в «Загрузки». Возвращает "ok" либо текст ошибки. */
    @JavascriptInterface
    fun saveFile(name: String, mime: String, text: String): String {
        return try {
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime.substringBefore(';'))
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                    ?: return "не удалось создать файл"
                app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, safe).writeBytes(bytes)
            }
            "ok"
        } catch (e: Exception) {
            e.message ?: "ошибка записи"
        }
    }

    // ---------------------------------------------------------------- регистрация

    private fun registerAll() {
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .build()
            fused.requestLocationUpdates(req, locCb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // разрешения нет — start() вернёт ошибку при следующей попытке
        }
        val imu = linearSensor ?: accelSensor
        if (imu != null) sm.registerListener(this, imu, SensorManager.SENSOR_DELAY_GAME)
        if (stepSensor != null && stepPermOk()) sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_FASTEST)
        if (stepPermOk() && playServicesOk()) {
            try {
                ActivityRecognition.getClient(app).requestActivityUpdates(1000L, activityIntent)
            } catch (e: SecurityException) {
                // нет разрешения на распознавание активности
            }
        }
    }

    private fun unregisterAll() {
        try { fused.removeLocationUpdates(locCb) } catch (e: Exception) { }
        sm.unregisterListener(this)
        if (stepPermOk() && playServicesOk()) {
            try { ActivityRecognition.getClient(app).removeActivityUpdates(activityIntent) } catch (e: Exception) { }
        }
    }

    // ---------------------------------------------------------------- SensorEventListener

    override fun onSensorChanged(e: SensorEvent) {
        synchronized(lock) {
            if (!running) return
            when (e.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> addImu(e.values[0], e.values[1], e.values[2])
                Sensor.TYPE_ACCELEROMETER -> {
                    // запасной путь (Слой 3, Р6): высокочастотный фильтр, гравитация выделяется НЧ-составляющей.
                    // Константа 0.8 — часть версии логики адаптера.
                    val a = 0.8f
                    if (!gInit) { gx = e.values[0]; gy = e.values[1]; gz = e.values[2]; gInit = true }
                    gx = a * gx + (1 - a) * e.values[0]
                    gy = a * gy + (1 - a) * e.values[1]
                    gz = a * gz + (1 - a) * e.values[2]
                    addImu(e.values[0] - gx, e.values[1] - gy, e.values[2] - gz)
                }
                Sensor.TYPE_STEP_COUNTER -> stepTotal = e.values[0]
            }
        }
    }

    private fun addImu(x: Float, y: Float, z: Float) {
        smaSum += (abs(x) + abs(y) + abs(z)).toDouble()
        imuN++
        imuTotal++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
