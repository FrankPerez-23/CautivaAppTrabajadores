package com.example.cautivaapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ServicioRastreoGps : Service() {

    private val alcanceServicio = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var clienteUbicacion: FusedLocationProviderClient
    private lateinit var respuestaUbicacion: LocationCallback
    private lateinit var gestorSesion: GestorSesion

    companion object {
        const val ACCION_DETENER = "ACCION_DETENER_RASTREO_GPS"
        const val ACCION_TRANSMISION_UBICACION = "com.example.cautivaapp.ACTUALIZACION_RASTREO"

        const val EXTRA_VELOCIDAD_KMH = "extra_velocidad_kmh"
        const val EXTRA_NIVEL_BATERIA = "extra_nivel_bateria"
        const val EXTRA_MARCA_TIEMPO = "extra_marca_tiempo"

        private const val ID_NOTIFICACION = 2002
        private const val CANAL_NOTIFICACION_ID = "cautiva_canal_rastreo_gps"
        private const val ETIQUETA_LOG = "ServicioRastreoGps"
    }

    override fun onCreate() {
        super.onCreate()
        gestorSesion = GestorSesion(this)
        clienteUbicacion = LocationServices.getFusedLocationProviderClient(this)
        crearCanalNotificacion()

        respuestaUbicacion = object : LocationCallback() {
            override fun onLocationResult(resultadoUbicacion: LocationResult) {
                super.onLocationResult(resultadoUbicacion)
                val ubicacion = resultadoUbicacion.lastLocation ?: return

                // 2. Filtro de Calidad Antirruido (Eliminar saltos falsos y demoras)
                val precisionMetros = ubicacion.accuracy
                val tiempoMuestra = ubicacion.time
                val tiempoActual = System.currentTimeMillis()

                // Descartar si la precisión es deficiente (> 25 metros)
                if (precisionMetros > 25.0f) {
                    Log.d(ETIQUETA_LOG, "Muestra descartada por baja precisión: ${precisionMetros}m")
                    return
                }

                // Descartar si hay un desfase temporal excesivo (> 10 segundos / 10000 ms)
                if (tiempoMuestra > 0 && (tiempoActual - tiempoMuestra > 10000L)) {
                    Log.d(ETIQUETA_LOG, "Muestra descartada por desfase temporal: ${tiempoActual - tiempoMuestra}ms")
                    return
                }

                val latitud = ubicacion.latitude
                val longitud = ubicacion.longitude
                val velocidadKmh = ubicacion.speed * 3.6f
                val rumboGrados = if (ubicacion.hasBearing()) ubicacion.bearing else 0.0f
                val nivelBateria = obtenerNivelBateria()
                val marcaTiempoActual = System.currentTimeMillis()

                Log.d(
                    ETIQUETA_LOG,
                    "GPS Válido: Lat=$latitud, Lng=$longitud, Vel=$velocidadKmh km/h, Rumbo=$rumboGrados°, Batería=$nivelBateria%, Precisión=${precisionMetros}m"
                )

                // Transmisión local para actualizar la UI en MainActivity
                val intencionTransmision = Intent(ACCION_TRANSMISION_UBICACION).apply {
                    putExtra(EXTRA_VELOCIDAD_KMH, velocidadKmh)
                    putExtra(EXTRA_NIVEL_BATERIA, nivelBateria)
                    putExtra(EXTRA_MARCA_TIEMPO, marcaTiempoActual)
                    setPackage(packageName)
                }
                sendBroadcast(intencionTransmision)

                val idUsuario = gestorSesion.obtenerIdUsuario() ?: return
                val idTurno = gestorSesion.obtenerIdTurno()
                val tokenAcceso = gestorSesion.obtenerTokenAcceso() ?: return

                // 4. Transmisión a Supabase (Upsert sobre ubicacion_en_vivo con manejo de microcortes 4G)
                alcanceServicio.launch {
                    enviarUbicacionASupabase(
                        idUsuario = idUsuario,
                        idTurno = idTurno,
                        latitud = latitud,
                        longitud = longitud,
                        velocidadKmh = velocidadKmh,
                        rumboGrados = rumboGrados,
                        nivelBateria = nivelBateria,
                        tokenAcceso = tokenAcceso
                    )
                }
            }
        }
    }

    override fun onStartCommand(intencion: Intent?, banderas: Int, idInicio: Int): Int {
        val accion = intencion?.action
        Log.d(ETIQUETA_LOG, "Acción en ServicioRastreoGps: $accion")

        when (accion) {
            ACCION_DETENER -> {
                detenerActualizacionesUbicacion()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                iniciarServicioEnPrimerPlano()
                solicitarActualizacionesUbicacionAltaPrecision()
            }
        }

        return START_STICKY
    }

    private fun iniciarServicioEnPrimerPlano() {
        val intencionActividad = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val intencionPendiente = PendingIntent.getActivity(
            this,
            0,
            intencionActividad,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificacion: Notification = NotificationCompat.Builder(this, CANAL_NOTIFICACION_ID)
            .setContentTitle("CAUTIVA - Rastreo GPS Activo")
            .setContentText("Transmitiendo telemetría en tiempo real (Optimizado para ruta)")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(intencionPendiente)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ID_NOTIFICACION,
                notificacion,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(ID_NOTIFICACION, notificacion)
        }
    }

    @SuppressLint("MissingPermission")
    private fun solicitarActualizacionesUbicacionAltaPrecision() {
        try {
            // 1. Configuración de LocationRequest de Alta Prioridad y Baja Latencia (4s / 2s / 2m)
            val solicitudUbicacion = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                4000L // Intervalo deseado de 4 segundos
            ).apply {
                setMinUpdateIntervalMillis(2000L) // Intervalo mínimo de 2 segundos
                setMinUpdateDistanceMeters(2.0f)   // Desplazamiento mínimo de 2 metros para evitar ruido estático
                setMaxUpdateDelayMillis(6000L)
            }.build()

            clienteUbicacion.requestLocationUpdates(
                solicitudUbicacion,
                respuestaUbicacion,
                Looper.getMainLooper()
            )
            Log.d(ETIQUETA_LOG, "Rastreo GPS de alta precisión iniciado correctamente")
        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Error al solicitar actualizaciones de alta precisión: ${e.localizedMessage}")
        }
    }

    private fun detenerActualizacionesUbicacion() {
        try {
            clienteUbicacion.removeLocationUpdates(respuestaUbicacion)
            Log.d(ETIQUETA_LOG, "Rastreo GPS detenido correctamente")
        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Error al detener rastreo GPS: ${e.localizedMessage}")
        }
    }

    private fun enviarUbicacionASupabase(
        idUsuario: String,
        idTurno: String?,
        latitud: Double,
        longitud: Double,
        velocidadKmh: Float,
        rumboGrados: Float,
        nivelBateria: Int,
        tokenAcceso: String,
    ) {
        try {
            val clienteHttp = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val urlCompleta = "${GestorSupabase.URL_SUPABASE}/rest/v1/ubicacion_en_vivo?on_conflict=usuario_id"

            val formatoFecha = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val marcaTiempoIso = formatoFecha.format(Date())

            val cuerpoJson = JSONObject().apply {
                put("usuario_id", idUsuario)
                if (!idTurno.isNullOrEmpty()) put("turno_id", idTurno)
                put("latitud", latitud)
                put("longitud", longitud)
                // Compatibilidad total con esquemas que usen 'velocidad' o 'velocidad_kmh'
                put("velocidad", velocidadKmh)
                put("velocidad_kmh", velocidadKmh)
                // Compatibilidad total con esquemas que usen 'rumbo' o 'direccion'
                put("rumbo", rumboGrados)
                put("direccion", rumboGrados)
                // Compatibilidad total con esquemas que usen 'bateria' o 'nivel_bateria'
                put("bateria", nivelBateria)
                put("nivel_bateria", nivelBateria)
                put("en_movimiento", velocidadKmh > 1.0f)
                put("ultima_actualizacion", marcaTiempoIso)
            }.toString()

            val tipoMediaJson = "application/json; charset=utf-8".toMediaType()
            val solicitud = Request.Builder()
                .url(urlCompleta)
                .addHeader("apikey", GestorSupabase.CLAVE_ANONIMA_SUPABASE)
                .addHeader("Authorization", "Bearer $tokenAcceso")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates")
                .post(cuerpoJson.toRequestBody(tipoMediaJson))
                .build()

            clienteHttp.newCall(solicitud).execute().use { respuesta ->
                if (respuesta.isSuccessful) {
                    Log.d(ETIQUETA_LOG, "Ubicación transmitida exitosamente a Supabase.")
                } else {
                    Log.w(ETIQUETA_LOG, "Error al transmitir a Supabase (Código: ${respuesta.code}) - Microcorte 4G tolerado.")
                }
            }
        } catch (e: Exception) {
            // Tolerancia a microcortes 4G: No detenemos el servicio ante caídas temporales de red
            Log.e(ETIQUETA_LOG, "Excepción de red al transmitir ubicación (tolerado): ${e.localizedMessage}")
        }
    }

    private fun obtenerNivelBateria(): Int {
        val estadoBateria: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val nivel = estadoBateria?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val escala = estadoBateria?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (nivel >= 0 && escala > 0) (nivel * 100 / escala.toFloat()).toInt() else 100
    }

    private fun crearCanalNotificacion() {
        val canal = NotificationChannel(
            CANAL_NOTIFICACION_ID,
            "Rastreo GPS de Alta Precisión CAUTIVA",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Canal persistente para transmisión optimizada de ubicación en segundo plano"
        }
        val gestorNotificaciones = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        gestorNotificaciones.createNotificationChannel(canal)
    }

    override fun onBind(intencion: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        detenerActualizacionesUbicacion()
        alcanceServicio.cancel()
        Log.d(ETIQUETA_LOG, "ServicioRastreoGps destruido correctamente")
    }
}
