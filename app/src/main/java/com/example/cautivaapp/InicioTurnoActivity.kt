package com.example.cautivaapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class InicioTurnoActivity : AppCompatActivity() {

    private lateinit var textoSaludoChofer: TextView
    private lateinit var botonCerrarSesionInicio: Button
    private lateinit var layoutInputBusqueda: TextInputLayout
    private lateinit var campoBuscarVehiculo: AutoCompleteTextView
    private lateinit var cardVehiculoSeleccionado: MaterialCardView
    private lateinit var textoPlacaSeleccionada: TextView
    private lateinit var textoMarcaModeloSeleccionada: TextView
    private lateinit var botonCambiarUnidad: Button
    private lateinit var botonIniciarJornadaLaboral: Button
    private lateinit var indicadorCargandoInicioTurno: ProgressBar

    private lateinit var gestorSesion: GestorSesion
    private var listaVehiculosActivos: List<Vehiculo> = emptyList()
    private var vehiculoSeleccionado: Vehiculo? = null

    // Lanzador de permisos para ubicación y notificaciones
    private val lanzadorSolicitudPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { mapaPermisos ->
        val permisoUbicacionPrecisaConcedido = mapaPermisos[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val permisoUbicacionAproximadaConcedido = mapaPermisos[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (permisoUbicacionPrecisaConcedido || permisoUbicacionAproximadaConcedido) {
            verificarYPedirExencionBateria()
            ejecutarRegistroTurno(vehiculoSeleccionado?.id)
        } else {
            Toast.makeText(
                this,
                "Se requieren permisos de ubicación para registrar la jornada con GPS.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(estadoGuardado: Bundle?) {
        super.onCreate(estadoGuardado)
        setContentView(R.layout.activity_inicio_turno)

        gestorSesion = GestorSesion(this)

        if (!gestorSesion.sesionIniciada()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // Si ya tiene una jornada activa, redirigir directamente a MainActivity
        if (gestorSesion.jornadaActiva()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        inicializarVistas()
        configurarEventos()
        cargarVehiculosActivos()
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
            val gestorEnergia = getSystemService(POWER_SERVICE) as PowerManager
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

    private fun inicializarVistas() {
        textoSaludoChofer = findViewById(R.id.tv_saludo_chofer)
        botonCerrarSesionInicio = findViewById(R.id.btn_cerrar_sesion_inicio)
        layoutInputBusqueda = findViewById(R.id.til_buscar_vehiculo)
        campoBuscarVehiculo = findViewById(R.id.et_buscar_vehiculo)
        cardVehiculoSeleccionado = findViewById(R.id.card_vehiculo_seleccionado)
        textoPlacaSeleccionada = findViewById(R.id.tv_placa_seleccionada)
        textoMarcaModeloSeleccionada = findViewById(R.id.tv_marca_modelo_seleccionada)
        botonCambiarUnidad = findViewById(R.id.btn_cambiar_unidad)
        botonIniciarJornadaLaboral = findViewById(R.id.btn_iniciar_jornada_laboral)
        indicadorCargandoInicioTurno = findViewById(R.id.pb_cargando_inicio_turno)

        val nombreChofer = gestorSesion.obtenerNombreChofer() ?: "Chofer"
        textoSaludoChofer.text = "Hola, $nombreChofer"
    }

    private fun configurarEventos() {
        botonCerrarSesionInicio.setOnClickListener {
            lifecycleScope.launch {
                if (validarSesionDispositivoAntesDeAccion()) {
                    mostrarDialogoConfirmacionCierreSesion()
                }
            }
        }

        botonCambiarUnidad.setOnClickListener {
            lifecycleScope.launch {
                if (validarSesionDispositivoAntesDeAccion()) {
                    limpiarVehiculoSeleccionado()
                }
            }
        }

        botonIniciarJornadaLaboral.setOnClickListener {
            lifecycleScope.launch {
                if (validarSesionDispositivoAntesDeAccion()) {
                    verificarPermisosYContinuar()
                }
            }
        }

        campoBuscarVehiculo.setOnItemClickListener { parent, _, position, _ ->
            val vehiculoElegido = parent.getItemAtPosition(position) as? Vehiculo
            if (vehiculoElegido != null) {
                lifecycleScope.launch {
                    if (validarSesionDispositivoAntesDeAccion()) {
                        seleccionarVehiculo(vehiculoElegido)
                    }
                }
            }
        }
    }

    private fun cargarVehiculosActivos() {
        val tokenAcceso = gestorSesion.obtenerTokenAcceso() ?: return
        cambiarEstadoCargando(true)

        lifecycleScope.launch {
            if (!validarSesionDispositivoAntesDeAccion()) {
                cambiarEstadoCargando(false)
                return@launch
            }

            val resultado = GestorSupabase.obtenerVehiculosActivos(tokenAcceso)
            cambiarEstadoCargando(false)

            resultado.fold(
                onSuccess = { lista ->
                    listaVehiculosActivos = lista
                    val adaptador = ArrayAdapter(
                        this@InicioTurnoActivity,
                        android.R.layout.simple_dropdown_item_1line,
                        listaVehiculosActivos
                    )
                    campoBuscarVehiculo.setAdapter(adaptador)
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@InicioTurnoActivity,
                        "Error al cargar vehículos: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun seleccionarVehiculo(vehiculo: Vehiculo) {
        vehiculoSeleccionado = vehiculo
        textoPlacaSeleccionada.text = vehiculo.placa
        val marcaModelo = listOfNotNull(vehiculo.marca, vehiculo.modelo).joinToString(" ").trim()
        val anioStr = if (vehiculo.anio != null) " (${vehiculo.anio})" else ""
        textoMarcaModeloSeleccionada.text = if (marcaModelo.isNotEmpty()) "$marcaModelo$anioStr" else "Camión Mixer Cautiva"

        // Ocultar buscador y mostrar tarjeta de unidad seleccionada
        layoutInputBusqueda.visibility = View.GONE
        cardVehiculoSeleccionado.visibility = View.VISIBLE
        campoBuscarVehiculo.setText("")
    }

    private fun limpiarVehiculoSeleccionado() {
        vehiculoSeleccionado = null
        cardVehiculoSeleccionado.visibility = View.GONE
        layoutInputBusqueda.visibility = View.VISIBLE
        campoBuscarVehiculo.setText("")
        campoBuscarVehiculo.requestFocus()
    }

    private fun verificarPermisosYContinuar() {
        if (vehiculoSeleccionado == null) {
            // Mostrar diálogo de confirmación si no seleccionó ningún vehículo
            mostrarDialogoSinVehiculo()
        } else {
            verificarPermisosUbicacionYEjecutar()
        }
    }

    private fun mostrarDialogoSinVehiculo() {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Iniciar sin vehículo?")
            .setMessage("No has seleccionado ningún camión asignado. ¿Estás seguro de que deseas iniciar tu jornada laboral sin vehículo?")
            .setNegativeButton("Cancelar") { dialogo, _ ->
                dialogo.dismiss()
            }
            .setPositiveButton("Continuar") { dialogo, _ ->
                dialogo.dismiss()
                verificarPermisosUbicacionYEjecutar()
            }
            .show()
    }

    private fun verificarPermisosUbicacionYEjecutar() {
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
            ejecutarRegistroTurno(vehiculoSeleccionado?.id)
        }
    }

    private fun ejecutarRegistroTurno(idVehiculo: String?) {
        val idChofer = gestorSesion.obtenerIdUsuario() ?: return
        val tokenAcceso = gestorSesion.obtenerTokenAcceso() ?: return

        cambiarEstadoCargando(true)

        lifecycleScope.launch {
            // VERIFICACIÓN OBLIGATORIA EN BASE DE DATOS ANTES DE CUALQUIER ACCIÓN
            if (!validarSesionDispositivoAntesDeAccion()) {
                cambiarEstadoCargando(false)
                return@launch
            }

            val resultadoTurno = GestorSupabase.iniciarTurnoLaboral(idChofer, idVehiculo, tokenAcceso)

            resultadoTurno.fold(
                onSuccess = { idTurno ->
                    val placaVehiculo = vehiculoSeleccionado?.placa ?: "Sin Vehículo"
                    gestorSesion.establecerEstadoJornada(
                        activa = true,
                        idTurno = idTurno,
                        idVehiculo = idVehiculo,
                        placaVehiculo = placaVehiculo
                    )

                    // Iniciar el Foreground Service de ubicación GPS en segundo plano
                    val intencionServicio = Intent(this@InicioTurnoActivity, ServicioUbicacion::class.java).apply {
                        action = ServicioUbicacion.ACCION_INICIAR
                    }
                    ContextCompat.startForegroundService(this@InicioTurnoActivity, intencionServicio)

                    cambiarEstadoCargando(false)
                    Toast.makeText(this@InicioTurnoActivity, "¡Jornada iniciada con éxito!", Toast.LENGTH_SHORT).show()

                    // Navegar a la pantalla principal
                    val intencionNavegacion = Intent(this@InicioTurnoActivity, MainActivity::class.java)
                    startActivity(intencionNavegacion)
                    finish()
                },
                onFailure = { error ->
                    cambiarEstadoCargando(false)
                    Toast.makeText(
                        this@InicioTurnoActivity,
                        "Error al registrar jornada: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun mostrarDialogoConfirmacionCierreSesion() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Cerrar sesión")
            .setMessage("¿Estás seguro de que deseas salir de tu cuenta?")
            .setNegativeButton("Cancelar") { dialogo, _ ->
                dialogo.dismiss()
            }
            .setPositiveButton("Cerrar sesión") { dialogo, _ ->
                dialogo.dismiss()
                gestorSesion.cerrarSesion()
                Toast.makeText(this, "Sesión cerrada correctamente", Toast.LENGTH_SHORT).show()

                val intencionLogin = Intent(this, LoginActivity::class.java)
                startActivity(intencionLogin)
                finish()
            }
            .show()
    }

    private fun cambiarEstadoCargando(estaCargando: Boolean) {
        if (estaCargando) {
            indicadorCargandoInicioTurno.visibility = View.VISIBLE
            botonIniciarJornadaLaboral.isEnabled = false
            botonCerrarSesionInicio.isEnabled = false
            campoBuscarVehiculo.isEnabled = false
        } else {
            indicadorCargandoInicioTurno.visibility = View.GONE
            botonIniciarJornadaLaboral.isEnabled = true
            botonCerrarSesionInicio.isEnabled = true
            campoBuscarVehiculo.isEnabled = true
        }
    }
}
