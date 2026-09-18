package com.example.cautivaapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var campoCorreo: EditText
    private lateinit var campoContrasena: EditText
    private lateinit var botonIniciarSesion: Button
    private lateinit var indicadorCargando: ProgressBar
    private lateinit var textoMensajeError: TextView
    private lateinit var gestorSesion: GestorSesion

    override fun onCreate(estadoGuardado: Bundle?) {
        super.onCreate(estadoGuardado)
        setContentView(R.layout.activity_login)

        gestorSesion = GestorSesion(this)

        // Redirigir a MainActivity o InicioTurnoActivity según estado de jornada
        if (gestorSesion.sesionIniciada()) {
            val destino = if (gestorSesion.jornadaActiva()) MainActivity::class.java else InicioTurnoActivity::class.java
            startActivity(Intent(this, destino))
            finish()
            return
        }

        // Búsqueda estricta mediante findViewById con IDs en español
        campoCorreo = findViewById(R.id.et_correo)
        campoContrasena = findViewById(R.id.et_contrasena)
        botonIniciarSesion = findViewById(R.id.btn_iniciar_sesion)
        indicadorCargando = findViewById(R.id.pb_cargando_login)
        textoMensajeError = findViewById(R.id.tv_mensaje_error)

        botonIniciarSesion.setOnClickListener {
            ejecutarInicioSesion()
        }
    }

    private fun ejecutarInicioSesion() {
        val correo = campoCorreo.text.toString().trim()
        val contrasena = campoContrasena.text.toString().trim()

        if (correo.isEmpty()) {
            campoCorreo.error = "Ingresa tu correo electrónico"
            campoCorreo.requestFocus()
            return
        }

        if (contrasena.isEmpty()) {
            campoContrasena.error = "Ingresa tu contraseña"
            campoContrasena.requestFocus()
            return
        }

        cambiarEstadoCargando(true)

        lifecycleScope.launch {
            val resultadoAutenticacion = GestorSupabase.autenticarUsuario(correo, contrasena)

            resultadoAutenticacion.fold(
                onSuccess = { datosUsuario ->
                    // 1. REGLA DE JORNADA ACTIVA: Consultar si el trabajador tiene un turno activo en la base de datos
                    val resultadoJornada = GestorSupabase.verificarJornadaActivaUsuario(
                        datosUsuario.idUsuario,
                        datosUsuario.tokenAcceso
                    )
                    val tieneJornadaActiva = resultadoJornada.getOrDefault(false)

                    if (tieneJornadaActiva) {
                        cambiarEstadoCargando(false)
                        MaterialAlertDialogBuilder(this@LoginActivity)
                            .setTitle("Jornada laboral activa")
                            .setMessage("No se puede iniciar sesión: Esta cuenta tiene una jornada laboral activa en otro dispositivo. Por seguridad en la transmisión GPS, debes contactar al administrador para que finalice tu jornada previa antes de poder ingresar en este teléfono.")
                            .setPositiveButton("Entendido") { dialogo, _ ->
                                dialogo.dismiss()
                            }
                            .show()
                        return@launch
                    }

                    // 2. Obtener perfil del chofer desde Supabase ('perfiles')
                    val resultadoPerfil = GestorSupabase.obtenerPerfil(datosUsuario.idUsuario, datosUsuario.tokenAcceso)
                    val nombreChofer = resultadoPerfil.getOrNull()?.nombreCompleto ?: "Chofer Cautiva"

                    // 3. Registrar el ID único de este dispositivo como la sesión activa actual
                    val idDispositivoLocal = gestorSesion.obtenerIdDispositivoLocal()
                    GestorSupabase.actualizarIdDispositivoPerfil(
                        datosUsuario.idUsuario,
                        idDispositivoLocal,
                        datosUsuario.tokenAcceso
                    )

                    // 4. Guardar sesión local
                    gestorSesion.guardarSesion(
                        idUsuario = datosUsuario.idUsuario,
                        tokenAcceso = datosUsuario.tokenAcceso,
                        tokenRefresco = datosUsuario.tokenRefresco,
                        correo = datosUsuario.correo,
                        nombreChofer = nombreChofer
                    )

                    Toast.makeText(this@LoginActivity, "¡Bienvenido, $nombreChofer!", Toast.LENGTH_SHORT).show()

                    // Navegar a la pantalla de Inicio de Turno (Selección de Vehículo)
                    val intencionNavegacion = Intent(this@LoginActivity, InicioTurnoActivity::class.java)
                    startActivity(intencionNavegacion)
                    finish()
                },
                onFailure = { excepcion ->
                    cambiarEstadoCargando(false)
                    textoMensajeError.text = excepcion.message ?: "Error al autenticar con Supabase"
                    textoMensajeError.visibility = View.VISIBLE
                }
            )
        }
    }

    private fun cambiarEstadoCargando(estaCargando: Boolean) {
        if (estaCargando) {
            indicadorCargando.visibility = View.VISIBLE
            botonIniciarSesion.isEnabled = false
            textoMensajeError.visibility = View.GONE
        } else {
            indicadorCargando.visibility = View.GONE
            botonIniciarSesion.isEnabled = true
        }
    }
}
