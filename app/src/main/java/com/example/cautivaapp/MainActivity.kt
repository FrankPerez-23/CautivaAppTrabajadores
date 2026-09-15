package com.example.cautivaapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var textoBienvenida: TextView
    private lateinit var indicadorPuntoEstado: View
    private lateinit var textoTituloEstado: TextView
    private lateinit var textoSubtituloEstado: TextView
    private lateinit var textoNivelBateria: TextView
    private lateinit var textoVelocidadActual: TextView
    private lateinit var textoUltimaSincronizacion: TextView
    private lateinit var botonAccionJornada: Button
    private lateinit var botonCerrarSesion: Button
    private lateinit var indicadorCargandoPrincipal: ProgressBar

    private lateinit var gestorSesion: GestorSesion

    // Receptor de transmisión local para actualizar velocidad y batería en pantalla
    private val receptorTransmisionUbicacion = object : BroadcastReceiver() {
        override fun onReceive(contexto: Context?, intencion: Intent?) {
            if (intencion?.action == ServicioUbicacion.ACCION_TRANSMISION_UBICACION) {
                val velocidadKmh = intencion.getFloatExtra(ServicioUbicacion.EXTRA_VELOCIDAD_KMH, 0.0f)
                val nivelBateria = intencion.getIntExtra(ServicioUbicacion.EXTRA_NIVEL_BATERIA, 100)
                val marcaTiempo = intencion.getLongExtra(ServicioUbicacion.EXTRA_MARCA_TIEMPO, System.currentTimeMillis())

                textoVelocidadActual.text = String.format(Locale.US, "%.1f km/h", velocidadKmh)
                textoNivelBateria.text = "$nivelBateria %"

                val horaFormateada = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(marcaTiempo))
                textoUltimaSincronizacion.text = "Última sincronización: $horaFormateada"
            }
        }
    }

    // Gestor de solicitudes de permisos en tiempo de ejecución
    private val lanzadorSolicitudPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { mapaPermisos ->
        val permisoUbicacionPrecisaConcedido = mapaPermisos[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val permisoUbicacionAproximadaConcedido = mapaPermisos[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (permisoUbicacionPrecisaConcedido || permisoUbicacionAproximadaConcedido) {
            procesarConmutacionJornada()
        } else {
            Toast.makeText(
                this,
                "Se requieren permisos de ubicación para registrar la jornada.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(estadoGuardado: Bundle?) {
        super.onCreate(estadoGuardado)
        setContentView(R.layout.activity_main)

        gestorSesion = GestorSesion(this)

        if (!gestorSesion.sesionIniciada()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // Búsqueda estricta mediante findViewById con IDs en español
        textoBienvenida = findViewById(R.id.tv_mensaje_bienvenida)
        indicadorPuntoEstado = findViewById(R.id.indicador_punto_estado)
        textoTituloEstado = findViewById(R.id.tv_titulo_estado)
        textoSubtituloEstado = findViewById(R.id.tv_subtitulo_estado)
        textoNivelBateria = findViewById(R.id.tv_nivel_bateria)
        textoVelocidadActual = findViewById(R.id.tv_velocidad_actual)
        textoUltimaSincronizacion = findViewById(R.id.tv_ultima_sincronizacion)
        botonAccionJornada = findViewById(R.id.btn_accion_jornada)
        botonCerrarSesion = findViewById(R.id.btn_cerrar_sesion)
        indicadorCargandoPrincipal = findViewById(R.id.pb_cargando_principal)

        // Asignar nombre del chofer obtenido previamente de 'perfiles'
        val nombreChofer = gestorSesion.obtenerNombreChofer() ?: "Chofer"
        textoBienvenida.text = "Bienvenido, $nombreChofer"

        actualizarEstadoInterfaz(gestorSesion.jornadaActiva())

        botonAccionJornada.setOnClickListener {
            verificarPermisosYConmutarJornada()
        }

        botonCerrarSesion.setOnClickListener {
            ejecutarCierreSesion()
        }
    }

    override fun onStart() {
        super.onStart()
        val filtroIntenciones = IntentFilter(ServicioUbicacion.ACCION_TRANSMISION_UBICACION)
        ContextCompat.registerReceiver(this, receptorTransmisionUbicacion, filtroIntenciones, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(receptorTransmisionUbicacion)
        } catch (_: Exception) {
            // Manejo seguro por si el receptor no estaba registrado
        }
    }

    private fun verificarPermisosYConmutarJornada() {
        val listaPermisosRequeridos = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            listaPermisosRequeridos.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            listaPermisosRequeridos.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                listaPermisosRequeridos.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (listaPermisosRequeridos.isNotEmpty()) {
            lanzadorSolicitudPermisos.launch(listaPermisosRequeridos.toTypedArray())
        } else {
            procesarConmutacionJornada()
        }
    }

    private fun procesarConmutacionJornada() {
        val jornadaActivaActual = gestorSesion.jornadaActiva()

        if (!jornadaActivaActual) {
            iniciarJornadaLaboral()
        } else {
            finalizarJornadaLaboral()
        }
    }

    private fun iniciarJornadaLaboral() {
        cambiarEstadoCargandoPrincipal(true)

        val idChofer = gestorSesion.obtenerIdUsuario() ?: return
        val tokenAcceso = gestorSesion.obtenerTokenAcceso() ?: return

        lifecycleScope.launch {
            val resultadoTurno = GestorSupabase.iniciarTurnoLaboral(idChofer, tokenAcceso)

            resultadoTurno.fold(
                onSuccess = { idTurno ->
                    gestorSesion.establecerEstadoJornada(activa = true, idTurno = idTurno)

                    // Iniciar Foreground Service de ubicación GPS
                    val intencionServicio = Intent(this@MainActivity, ServicioUbicacion::class.java).apply {
                        action = ServicioUbicacion.ACCION_INICIAR
                    }
                    ContextCompat.startForegroundService(this@MainActivity, intencionServicio)

                    actualizarEstadoInterfaz(jornadaActiva = true)
                    cambiarEstadoCargandoPrincipal(false)
                    Toast.makeText(this@MainActivity, "Jornada iniciada con éxito", Toast.LENGTH_SHORT).show()
                },
                onFailure = { error ->
                    cambiarEstadoCargandoPrincipal(false)
                    Toast.makeText(this@MainActivity, "Error al iniciar jornada: ${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun finalizarJornadaLaboral() {
        cambiarEstadoCargandoPrincipal(true)

        val idTurno = gestorSesion.obtenerIdTurno()
        val tokenAcceso = gestorSesion.obtenerTokenAcceso() ?: return

        lifecycleScope.launch {
            if (!idTurno.isNullOrEmpty()) {
                GestorSupabase.finalizarTurnoLaboral(idTurno, tokenAcceso)
            }

            gestorSesion.establecerEstadoJornada(activa = false, idTurno = null)

            // Detener Foreground Service de ubicación GPS
            val intencionServicio = Intent(this@MainActivity, ServicioUbicacion::class.java).apply {
                action = ServicioUbicacion.ACCION_DETENER
            }
            startService(intencionServicio)

            actualizarEstadoInterfaz(jornadaActiva = false)
            cambiarEstadoCargandoPrincipal(false)
            Toast.makeText(this@MainActivity, "Jornada finalizada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun actualizarEstadoInterfaz(jornadaActiva: Boolean) {
        if (jornadaActiva) {
            indicadorPuntoEstado.setBackgroundResource(R.drawable.dot_active)
            textoTituloEstado.text = "Transmitiendo ubicación en vivo"
            textoSubtituloEstado.text = "GPS activo - Enviando telemetría..."

            // Estado Activo: Botón Rojo (#D8262C) con texto "FINALIZAR JORNADA"
            botonAccionJornada.text = "FINALIZAR JORNADA"
            botonAccionJornada.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.cautiva_alert_red)
            )
        } else {
            indicadorPuntoEstado.setBackgroundResource(R.drawable.dot_inactive)
            textoTituloEstado.text = "Fuera de jornada"
            textoSubtituloEstado.text = "Presiona el botón para comenzar a transmitir"
            textoVelocidadActual.text = "0.0 km/h"
            textoUltimaSincronizacion.text = "Última sincronización: Ninguna"

            // Estado Apagado: Botón Naranja (#F27824) con texto "INICIAR JORNADA"
            botonAccionJornada.text = "INICIAR JORNADA"
            botonAccionJornada.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.cautiva_primary_orange)
            )
        }
    }

    private fun cambiarEstadoCargandoPrincipal(estaCargando: Boolean) {
        if (estaCargando) {
            indicadorCargandoPrincipal.visibility = View.VISIBLE
            botonAccionJornada.isEnabled = false
            botonCerrarSesion.isEnabled = false
        } else {
            indicadorCargandoPrincipal.visibility = View.GONE
            botonAccionJornada.isEnabled = true
            botonCerrarSesion.isEnabled = true
        }
    }

    private fun ejecutarCierreSesion() {
        if (gestorSesion.jornadaActiva()) {
            Toast.makeText(
                this,
                "Debes finalizar tu jornada antes de cerrar sesión.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        gestorSesion.cerrarSesion()
        Toast.makeText(this, "Sesión cerrada", Toast.LENGTH_SHORT).show()

        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
