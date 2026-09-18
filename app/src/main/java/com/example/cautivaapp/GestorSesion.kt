package com.example.cautivaapp

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import java.util.UUID

class GestorSesion(private val contexto: Context) {

    private val preferencias: SharedPreferences =
        contexto.getSharedPreferences(NOMBRE_PREFERENCIAS, Context.MODE_PRIVATE)

    companion object {
        private const val NOMBRE_PREFERENCIAS = "cautiva_sesion"
        private const val CLAVE_TOKEN_ACCESO = "token_acceso"
        private const val CLAVE_TOKEN_REFRESCO = "token_refresco"
        private const val CLAVE_ID_USUARIO = "id_usuario"
        private const val CLAVE_NOMBRE_CHOFER = "nombre_chofer"
        private const val CLAVE_CORREO = "correo"
        private const val CLAVE_JORNADA_ACTIVA = "jornada_activa"
        private const val CLAVE_ID_TURNO = "id_turno"
        private const val CLAVE_ID_VEHICULO = "id_vehiculo"
        private const val CLAVE_PLACA_VEHICULO = "placa_vehiculo"
        private const val CLAVE_ID_DISPOSITIVO = "id_dispositivo"
    }

    fun obtenerIdDispositivoLocal(): String {
        var idDisp = preferencias.getString(CLAVE_ID_DISPOSITIVO, null)
        if (idDisp.isNullOrEmpty()) {
            val androidId = try {
                Settings.Secure.getString(contexto.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (_: Exception) {
                null
            }
            idDisp = if (!androidId.isNullOrEmpty()) androidId else UUID.randomUUID().toString()
            preferencias.edit().putString(CLAVE_ID_DISPOSITIVO, idDisp).apply()
        }
        return idDisp
    }

    fun guardarSesion(
        idUsuario: String,
        tokenAcceso: String,
        tokenRefresco: String? = null,
        correo: String,
        nombreChofer: String,
    ) {
        preferencias.edit().apply {
            putString(CLAVE_ID_USUARIO, idUsuario)
            putString(CLAVE_TOKEN_ACCESO, tokenAcceso)
            if (!tokenRefresco.isNullOrEmpty()) {
                putString(CLAVE_TOKEN_REFRESCO, tokenRefresco)
            }
            putString(CLAVE_CORREO, correo)
            putString(CLAVE_NOMBRE_CHOFER, nombreChofer)
            apply()
        }
    }

    fun actualizarTokens(tokenAcceso: String, tokenRefresco: String? = null) {
        preferencias.edit().apply {
            putString(CLAVE_TOKEN_ACCESO, tokenAcceso)
            if (!tokenRefresco.isNullOrEmpty()) {
                putString(CLAVE_TOKEN_REFRESCO, tokenRefresco)
            }
            apply()
        }
    }

    fun establecerEstadoJornada(
        activa: Boolean,
        idTurno: String? = null,
        idVehiculo: String? = null,
        placaVehiculo: String? = null,
    ) {
        preferencias.edit().apply {
            putBoolean(CLAVE_JORNADA_ACTIVA, activa)
            putString(CLAVE_ID_TURNO, idTurno)
            putString(CLAVE_ID_VEHICULO, idVehiculo)
            putString(CLAVE_PLACA_VEHICULO, placaVehiculo)
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
    fun obtenerTokenRefresco(): String? = preferencias.getString(CLAVE_TOKEN_REFRESCO, null)
    fun obtenerIdUsuario(): String? = preferencias.getString(CLAVE_ID_USUARIO, null)
    fun obtenerNombreChofer(): String? = preferencias.getString(CLAVE_NOMBRE_CHOFER, "Chofer")
    fun obtenerCorreo(): String? = preferencias.getString(CLAVE_CORREO, null)
    fun obtenerIdTurno(): String? = preferencias.getString(CLAVE_ID_TURNO, null)
    fun obtenerIdVehiculo(): String? = preferencias.getString(CLAVE_ID_VEHICULO, null)
    fun obtenerPlacaVehiculo(): String? = preferencias.getString(CLAVE_PLACA_VEHICULO, null)

    fun cerrarSesion() {
        val idDispositivoGuardado = preferencias.getString(CLAVE_ID_DISPOSITIVO, null)
        preferencias.edit().clear().apply()
        if (!idDispositivoGuardado.isNullOrEmpty()) {
            preferencias.edit().putString(CLAVE_ID_DISPOSITIVO, idDispositivoGuardado).apply()
        }
    }
}
