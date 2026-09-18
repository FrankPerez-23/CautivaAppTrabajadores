package com.example.cautivaapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

class ServicioUbicacion : Service() {

    private val alcanceServicio = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var clienteUbicacion: FusedLocationProviderClient
    private lateinit var respuestaUbicacion: LocationCallback
    private lateinit var gestorSesion: GestorSesion
    private lateinit var baseDatosLocal: BaseDatosLocal

    companion object {
        const val ACCION_INICIAR = "ACCION_INICIAR_SERVICIO_UBICACION"
        const val ACCION_DETENER = "ACCION_DETENER_SERVICIO_UBICACION"
        const val ACCION_TRANSMISION_UBICACION = "com.example.cautivaapp.ACTUALIZACION_UBICACION"

        const val EXTRA_VELOCIDAD_KMH = "extra_velocidad_kmh"
        const val EXTRA_NIVEL_BATERIA = "extra_nivel_bateria"
        const val EXTRA_MARCA_TIEMPO = "extra_marca_tiempo"

        private const val ID_NOTIFICACION = 1001
        private const val CANAL_NOTIFICACION_ID = "cautiva_canal_ubicacion"
        private const val ETIQUETA_LOG = "ServicioUbicacion"
    }

    override fun onCreate() {
        super.onCreate()
        gestorSesion = GestorSesion(this)
        baseDatosLocal = BaseDatosLocal.obtenerInstancia(this)
        clienteUbicacion = LocationServices.getFusedLocationProviderClient(this)
        crearCanalNotificacion()

        respuestaUbicacion = object : LocationCallback() {
            override fun onLocationResult(resultadoUbicacion: LocationResult) {
                super.onLocationResult(resultadoUbicacion)
                val ubicacion = resultadoUbicacion.lastLocation ?: return

                // Filtro de calidad y precisión para evitar saltos erráticos y muestras antiguas
                val precisionMetros = ubicacion.accuracy
                val tiempoMuestra = ubicacion.time
                val tiempoActual = System.currentTimeMillis()

                if (precisionMetros > 30.0f) {
                    Log.d(ETIQUETA_LOG, "Muestra descartada por baja precisión: ${precisionMetros}m")
                    return
                }

                if (tiempoMuestra > 0 && (tiempoActual - tiempoMuestra > 10000L)) {
                    Log.d(ETIQUETA_LOG, "Muestra descartada por desfase temporal: ${tiempoActual - tiempoMuestra}ms")
                    return
                }

                val latitud = ubicacion.latitude
                val longitud = ubicacion.longitude
                val velocidadKmh = ubicacion.speed * 3.6f
                val direccionGrados = if (ubicacion.hasBearing()) ubicacion.bearing.toDouble() else null
                val nivelBateria = obtenerNivelBateria()
                val marcaTiempoActual = System.currentTimeMillis()

                Log.d(
                    ETIQUETA_LOG,
                    "Coordenada GPS obtenida: Lat=$latitud, Lng=$longitud, Vel=$velocidadKmh km/h, Rumbo=$direccionGrados°, Batería=$nivelBateria%, Precisión=${precisionMetros}m"
                )

                // 1. Emitir transmisión local para actualizar la UI en MainActivity
                val intencionTransmision = Intent(ACCION_TRANSMISION_UBICACION).apply {
                    putExtra(EXTRA_VELOCIDAD_KMH, velocidadKmh)
                    putExtra(EXTRA_NIVEL_BATERIA, nivelBateria)
                    putExtra(EXTRA_MARCA_TIEMPO, marcaTiempoActual)
                    setPackage(packageName)
                }
                sendBroadcast(intencionTransmision)

                val idUsuario = gestorSesion.obtenerIdUsuario() ?: return
                val idTurno = gestorSesion.obtenerIdTurno()

                // 2. Guardar la coordenada en SQLite local (Room) con estado pendiente
                alcanceServicio.launch {
                    val nuevaUbicacionLocal = UbicacionLocal(
                        usuarioId = idUsuario,
                        turnoId = idTurno,
                        latitud = latitud,
                        longitud = longitud,
                        velocidadKmh = velocidadKmh.toDouble(),
                        direccion = direccionGrados,
                        nivelBateria = nivelBateria,
                        fechaGps = marcaTiempoActual,
                        sincronizado = false
                    )

                    baseDatosLocal.ubicacionDao().insertarUbicacion(nuevaUbicacionLocal)
                    Log.d(ETIQUETA_LOG, "Coordenada guardada en Room local (Modo Offline activo)")

                    // 3. Intentar sincronización en lote contra Supabase si hay conectividad
                    procesarSincronizacionOffline()
                }
            }
        }
    }

    override fun onStartCommand(intencion: Intent?, banderas: Int, idInicio: Int): Int {
        val accion = intencion?.action
        Log.d(ETIQUETA_LOG, "Acción en ServicioUbicacion: $accion")

        when (accion) {
            ACCION_INICIAR -> {
                iniciarServicioEnPrimerPlano()
                solicitarActualizacionesUbicacion()
            }
            ACCION_DETENER -> {
                detenerActualizacionesUbicacion()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                iniciarServicioEnPrimerPlano()
                solicitarActualizacionesUbicacion()
            }
        }

        return START_STICKY
    }

    /**
     * Revisa si hay internet y envía el lote pendiente almacenado en Room hacia Supabase
     */
    private suspend fun procesarSincronizacionOffline() {
        if (!hayConexionInternet()) {
            Log.d(ETIQUETA_LOG, "Sin conexión a internet. Coordenadas conservadas en Room local.")
            return
        }

        val tokenAcceso = gestorSesion.obtenerTokenAcceso() ?: return
        val listaPendientes = baseDatosLocal.ubicacionDao().obtenerUbicacionesPendientes()

        if (listaPendientes.isEmpty()) {
            return
        }

        Log.d(ETIQUETA_LOG, "Conexión detectada. Sincronizando ${listaPendientes.size} registros pendientes con Supabase...")

        val idsExitosos = mutableListOf<Int>()

        for (item in listaPendientes) {
            // Actualizar la última posición en 'ubicacion_en_vivo'
            val resultadoVivo = GestorSupabase.actualizarUbicacionEnVivo(
                idChofer = item.usuarioId,
                idTurno = item.turnoId,
                latitud = item.latitud,
                longitud = item.longitud,
                velocidadKmh = item.velocidadKmh.toFloat(),
                direccion = item.direccion,
                nivelBateria = item.nivelBateria,
                tokenAcceso = tokenAcceso
            )

            // Insertar la posición en 'historial_ubicaciones'
            val resultadoHistorial = GestorSupabase.insertarHistorialUbicacion(
                idChofer = item.usuarioId,
                idTurno = item.turnoId,
                latitud = item.latitud,
                longitud = item.longitud,
                velocidadKmh = item.velocidadKmh.toFloat(),
                direccion = item.direccion,
                nivelBateria = item.nivelBateria,
                tokenAcceso = tokenAcceso
            )

            if (resultadoVivo.isSuccess || resultadoHistorial.isSuccess) {
                idsExitosos.add(item.id)
            }
        }

        // Eliminar de SQLite local todos los registros confirmados
        if (idsExitosos.isNotEmpty()) {
            baseDatosLocal.ubicacionDao().eliminarUbicacionesSincronizadas(idsExitosos)
            Log.d(ETIQUETA_LOG, "Sincronización completada: ${idsExitosos.size} registros eliminados de Room local.")
        }
    }

    /**
     * Verifica la disponibilidad de red a través de ConnectivityManager
     */
    private fun hayConexionInternet(): Boolean {
        val gestorConectividad = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val redActiva = gestorConectividad.activeNetwork ?: return false
        val capacidades = gestorConectividad.getNetworkCapabilities(redActiva) ?: return false

        return capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun iniciarServicioEnPrimerPlano() {
        val intencionActividad = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val banderasIntencionPendiente = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val intencionPendiente = PendingIntent.getActivity(
            this,
            0,
            intencionActividad,
            banderasIntencionPendiente
        )

        val notificacion: Notification = NotificationCompat.Builder(this, CANAL_NOTIFICACION_ID)
            .setContentTitle("Jornada activa - Transmitiendo ubicación")
            .setContentText("CAUTIVA: Telemetría GPS en tiempo real activa (Modo Offline disponible)")
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
    private fun solicitarActualizacionesUbicacion() {
        try {
            val solicitudUbicacion = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                5000L // Intervalo deseado de 5 segundos para telemetría fluida en mapa de administración
            ).apply {
                setMinUpdateIntervalMillis(2000L) // 2 segundos mínimo
                setMinUpdateDistanceMeters(2.0f)   // Desplazamiento mínimo de 2 metros
            }.build()

            clienteUbicacion.requestLocationUpdates(
                solicitudUbicacion,
                respuestaUbicacion,
                Looper.getMainLooper()
            )
            Log.d(ETIQUETA_LOG, "Solicitud de actualizaciones de ubicación iniciada correctamente")
        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Error al solicitar ubicación GPS: ${e.localizedMessage}")
        }
    }

    private fun detenerActualizacionesUbicacion() {
        try {
            clienteUbicacion.removeLocationUpdates(respuestaUbicacion)
            Log.d(ETIQUETA_LOG, "Actualizaciones de ubicación detenidas correctamente")
        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Error al detener actualizaciones de ubicación: ${e.localizedMessage}")
        }
    }

    private fun obtenerNivelBateria(): Int {
        val estadoBateria: Intent? = registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val nivel: Int = estadoBateria?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val escala: Int = estadoBateria?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        return if (nivel >= 0 && escala > 0) {
            (nivel * 100 / escala.toFloat()).toInt()
        } else {
            100
        }
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL_NOTIFICACION_ID,
                "Jornada Laboral y Telemetría CAUTIVA",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación persistente requerida para transmisión GPS en vivo y offline"
            }
            val gestorNotificaciones = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            gestorNotificaciones.createNotificationChannel(canal)
        }
    }

    override fun onBind(intencion: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        detenerActualizacionesUbicacion()
        alcanceServicio.cancel()
        Log.d(ETIQUETA_LOG, "ServicioUbicacion destruido correctamente")
    }
}
