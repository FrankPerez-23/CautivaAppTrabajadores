package com.example.cautivaapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
    private lateinit var textoUltimaSincronizacion: TextView
    private lateinit var botonAccionJornada: Button
    private lateinit var indicadorCargandoPrincipal: ProgressBar

    private lateinit var gestorSesion: GestorSesion

    // Receptor de transmisión local para actualizar batería en pantalla
    private val receptorTransmisionUbicacion = object : BroadcastReceiver() {
        override fun onReceive(contexto: Context?, intencion: Intent?) {
            if (intencion?.action == ServicioUbicacion.ACCION_TRANSMISION_UBICACION) {
                val nivelBateria = intencion.getIntExtra(ServicioUbicacion.EXTRA_NIVEL_BATERIA, 100)
                val marcaTiempo = intencion.getLongExtra(ServicioUbicacion.EXTRA_MARCA_TIEMPO, System.currentTimeMillis())

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
            verificarYPedirExencionBateria()
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
        textoUltimaSincronizacion = findViewById(R.id.tv_ultima_sincronizacion)
        botonAccionJornada = findViewById(R.id.btn_accion_jornada)
        indicadorCargandoPrincipal = findViewById(R.id.pb_cargando_principal)

        // Asignar nombre del chofer obtenido previamente de 'perfiles'
        val nombreChofer = gestorSesion.obtenerNombreChofer() ?: "Chofer"
        textoBienvenida.text = "Bienvenido, $nombreChofer"

        val jornadaActiva = gestorSesion.jornadaActiva()
        actualizarEstadoInterfaz(jornadaActiva)

        if (jornadaActiva) {
            // Mostrar telemetría inicial de inmediato sin esperar al primer fix GPS
            val nivelBateriaActual = obtenerNivelBateriaLocal()
            textoNivelBateria.text = "$nivelBateriaActual %"
            textoUltimaSincronizacion.text = "Última sincronización: En curso..."

            verificarYPedirExencionBateria()

            // Garantizar que el servicio de rastreo GPS esté activo en primer plano
            val intencionServicio = Intent(this, ServicioUbicacion::class.java).apply {
                action = ServicioUbicacion.ACCION_INICIAR
            }
            ContextCompat.startForegroundService(this, intencionServicio)
        }

        botonAccionJornada.setOnClickListener {
            lifecycleScope.launch {
                if (validarSesionDispositivoAntesDeAccion()) {
                    verificarPermisosYConmutarJornada()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            validarSesionDispositivoAntesDeAccion()
        }
    }

    /**
     * Consulta a Supabase si este teléfono sigue siendo la sesión activa registrada en 'perfiles.metadatos'.
     * Si otro teléfono inició sesión, cancela la acción, cierra la sesión local y redirige al Login.
     */
    private suspend fun validarSesionDispositivoAntesDeAccion(): Boolean {
        val idUsuario = gestorSesion.obtenerIdUsuario() ?: return false
        val tokenAcceso = gestorSesion.obtenerTokenAcceso() ?: ""
        val idDispositivoLocal = gestorSesion.obtenerIdDispositivoLocal()

        val resultadoDisp = GestorSupabase.obtenerIdDispositivoRegistrado(idUsuario, tokenAcceso)
        val idRegistradoEnServidor = resultadoDisp.getOrNull()

        if (!idRegistradoEnServidor.isNullOrEmpty() && idRegistradoEnServidor != idDispositivoLocal) {
            // Sesión anulada en la base de datos por otro teléfono
            val intencionServicio = Intent(this, ServicioUbicacion::class.java).apply {
                action = ServicioUbicacion.ACCION_DETENER
            }
            stopService(intencionServicio)

            gestorSesion.cerrarSesion()
            Toast.makeText(
                this,
                "Se ha iniciado sesión en otro dispositivo. Tu sesión en este teléfono se ha cerrado.",
                Toast.LENGTH_LONG
            ).show()

            val intencionLogin = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intencionLogin)
            finish()
            return false
        }
        return true
    }

    @SuppressLint("BatteryLife")
    private fun verificarYPedirExencionBateria() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val gestorEnergia = getSystemService(Context.POWER_SERVICE) as PowerManager
            val nombrePaquete = packageName

            if (!gestorEnergia.isIgnoringBatteryOptimizations(nombrePaquete)) {
                try {
                    val intencionExencion = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$nombrePaquete")
                    }
                    startActivity(intencionExencion)
                } catch (_: Exception) {
                    try {
                        val intencionAjustes = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intencionAjustes)
                    } catch (_: Exception) {
                        // Manejo seguro ante ROMs con configuraciones restringidas
                    }
                }
            }
        }
    }

    private fun obtenerNivelBateriaLocal(): Int {
        val estadoBateria: Intent? = registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val nivel: Int = estadoBateria?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val escala: Int = estadoBateria?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1

        return if (nivel >= 0 && escala > 0) {
            (nivel * 100 / escala.toFloat()).toInt()
        } else {
            100
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
            verificarYPedirExencionBateria()
            procesarConmutacionJornada()
        }
    }

    private fun procesarConmutacionJornada() {
        val jornadaActivaActual = gestorSesion.jornadaActiva()

        if (!jornadaActivaActual) {
            iniciarJornadaLaboral()
        } else {
            mostrarDialogoConfirmacionFinalizacion()
        }
    }

    private fun mostrarDialogoConfirmacionFinalizacion() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("¿Finalizar jornada laboral?")
            .setMessage("Se detendrá el rastreo GPS y tu ubicación dejará de ser visible para la administración.")
            .setNegativeButton("Cancelar") { dialogo, _ ->
                dialogo.dismiss()
            }
            .setPositiveButton("Finalizar") { dialogo, _ ->
                dialogo.dismiss()
                ejecutarSecuenciaFinalizacion()
            }
            .show()
    }

    private fun iniciarJornadaLaboral() {
        cambiarEstadoCargandoPrincipal(true)

        val idChofer = gestorSesion.obtenerIdUsuario() ?: return
        val tokenAcceso = gestorSesion.obtenerTokenAcceso() ?: return

        lifecycleScope.launch {
            // VERIFICACIÓN OBLIGATORIA EN BASE DE DATOS ANTES DE CUALQUIER ACCIÓN
            if (!validarSesionDispositivoAntesDeAccion()) {
                cambiarEstadoCargandoPrincipal(false)
                return@launch
            }

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

    private fun ejecutarSecuenciaFinalizacion() {
        cambiarEstadoCargandoPrincipal(true)

        val idChofer = gestorSesion.obtenerIdUsuario()
        val idTurno = gestorSesion.obtenerIdTurno()
        val tokenAcceso = gestorSesion.obtenerTokenAcceso()

        if (idChofer == null || tokenAcceso == null) {
            cambiarEstadoCargandoPrincipal(false)
            Toast.makeText(this, "Error de sesión. Vuelve a iniciar sesión.", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            // VERIFICACIÓN OBLIGATORIA EN BASE DE DATOS ANTES DE CUALQUIER ACCIÓN
            if (!validarSesionDispositivoAntesDeAccion()) {
                cambiarEstadoCargandoPrincipal(false)
                return@launch
            }

            try {
                // Paso 1: Detener inmediatamente el ForegroundService de rastreo GPS
                val intencionServicio = Intent(this@MainActivity, ServicioUbicacion::class.java).apply {
                    action = ServicioUbicacion.ACCION_DETENER
                }
                stopService(intencionServicio)

                // Paso 2: Actualizar en Supabase turnos_laborales (estado = 'FINALIZADO', fin_real = NOW())
                if (!idTurno.isNullOrEmpty()) {
                    val resultadoTurno = GestorSupabase.finalizarTurnoLaboral(idTurno, tokenAcceso)
                    if (resultadoTurno.isFailure) {
                        Toast.makeText(
                            this@MainActivity,
                            "Advertencia: No se pudo sincronizar el cierre de turno con el servidor.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                // Paso 3: Ejecutar DELETE en Supabase sobre ubicacion_en_vivo filtrando por usuario_id (Protección de Privacidad)
                val resultadoPrivacidad = GestorSupabase.eliminarUbicacionEnVivo(idChofer, tokenAcceso)
                if (resultadoPrivacidad.isFailure) {
                    Toast.makeText(
                        this@MainActivity,
                        "Advertencia: Error al eliminar ubicación en vivo del servidor.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Paso 4: Limpiar variables de sesión local y redirigir a InicioTurnoActivity
                gestorSesion.establecerEstadoJornada(activa = false, idTurno = null, idVehiculo = null, placaVehiculo = null)
                cambiarEstadoCargandoPrincipal(false)
                Toast.makeText(this@MainActivity, "Jornada finalizada correctamente. Ubicación oculta.", Toast.LENGTH_LONG).show()

                val intencionInicioTurno = Intent(this@MainActivity, InicioTurnoActivity::class.java)
                startActivity(intencionInicioTurno)
                finish()

            } catch (e: Exception) {
                cambiarEstadoCargandoPrincipal(false)
                Toast.makeText(
                    this@MainActivity,
                    "Error de red al finalizar jornada: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()

                // Asegurar limpieza local y parada de servicio incluso ante fallo crítico de red
                val intencionServicio = Intent(this@MainActivity, ServicioUbicacion::class.java).apply {
                    action = ServicioUbicacion.ACCION_DETENER
                }
                stopService(intencionServicio)
                gestorSesion.establecerEstadoJornada(activa = false, idTurno = null, idVehiculo = null, placaVehiculo = null)

                val intencionInicioTurno = Intent(this@MainActivity, InicioTurnoActivity::class.java)
                startActivity(intencionInicioTurno)
                finish()
            }
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
        } else {
            indicadorCargandoPrincipal.visibility = View.GONE
            botonAccionJornada.isEnabled = true
        }
    }
}
