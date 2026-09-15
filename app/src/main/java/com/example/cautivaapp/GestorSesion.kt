package com.example.cautivaapp

import android.content.Context
import android.content.SharedPreferences

class GestorSesion(contexto: Context) {

    private val preferencias: SharedPreferences =
        contexto.getSharedPreferences(NOMBRE_PREFERENCIAS, Context.MODE_PRIVATE)

    companion object {
        private const val NOMBRE_PREFERENCIAS = "cautiva_sesion"
        private const val CLAVE_TOKEN_ACCESO = "token_acceso"
        private const val CLAVE_ID_USUARIO = "id_usuario"
        private const val CLAVE_NOMBRE_CHOFER = "nombre_chofer"
        private const val CLAVE_CORREO = "correo"
        private const val CLAVE_JORNADA_ACTIVA = "jornada_activa"
        private const val CLAVE_ID_TURNO = "id_turno"
    }

    fun guardarSesion(
        idUsuario: String,
        tokenAcceso: String,
        correo: String,
        nombreChofer: String,
    ) {
        preferencias.edit().apply {
            putString(CLAVE_ID_USUARIO, idUsuario)
            putString(CLAVE_TOKEN_ACCESO, tokenAcceso)
            putString(CLAVE_CORREO, correo)
            putString(CLAVE_NOMBRE_CHOFER, nombreChofer)
            apply()
        }
    }

    fun establecerEstadoJornada(activa: Boolean, idTurno: String? = null) {
        preferencias.edit().apply {
            putBoolean(CLAVE_JORNADA_ACTIVA, activa)
            putString(CLAVE_ID_TURNO, idTurno)
            apply()
        }
    }

    fun sesionIniciada(): Boolean {
        return obtenerTokenAcceso() != null && obtenerIdUsuario() != null
    }

    fun jornadaActiva(): Boolean {
        return preferencias.getBoolean(CLAVE_JORNADA_ACTIVA, false)
    }

    fun obtenerTokenAcceso(): String? = preferencias.getString(CLAVE_TOKEN_ACCESO, null)
    fun obtenerIdUsuario(): String? = preferencias.getString(CLAVE_ID_USUARIO, null)
    fun obtenerNombreChofer(): String? = preferencias.getString(CLAVE_NOMBRE_CHOFER, "Chofer")
    fun obtenerCorreo(): String? = preferencias.getString(CLAVE_CORREO, null)
    fun obtenerIdTurno(): String? = preferencias.getString(CLAVE_ID_TURNO, null)

    fun cerrarSesion() {
        preferencias.edit().clear().apply()
    }
}
