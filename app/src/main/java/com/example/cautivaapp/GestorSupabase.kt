package com.example.cautivaapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object GestorSupabase {

    // URL y Clave Anónima de Supabase
    var URL_SUPABASE = "https://zhtufypmgbgaoqnojwas.supabase.co"
    var CLAVE_ANONIMA_SUPABASE = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpodHVmeXBtZ2JnYW9xbm9qd2FzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODkzOTQ2MjMsImV4cCI6MjEwNDk3MDYyM30.9iepsOEdeYu1C1hB7voqBEXqyeY72yrZLfCF9gH2oII"

    private val clienteHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val TIPO_MEDIA_JSON = "application/json; charset=utf-8".toMediaType()

    private fun obtenerMarcaTiempoIsoActual(desfaseHoras: Int = 0): String {
        val formatoFecha = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatoFecha.timeZone = TimeZone.getTimeZone("UTC")
        val tiempoMs = System.currentTimeMillis() + (desfaseHoras * 3600 * 1000L)
        return formatoFecha.format(Date(tiempoMs))
    }

    data class ResultadoInicioSesion(
        val idUsuario: String,
        val tokenAcceso: String,
        val correo: String,
    )

    data class PerfilChofer(
        val id: String,
        val nombreCompleto: String,
        val correo: String,
        val rol: String?,
    )

    /**
     * Autenticación de usuario con Supabase Auth (GoTrue API)
     */
    suspend fun autenticarUsuario(correoEntrada: String, contrasenaEntrada: String): Result<ResultadoInicioSesion> =
        withContext(Dispatchers.IO) {
            try {
                val urlCompleta = "$URL_SUPABASE/auth/v1/token?grant_type=password"
                val cuerpoJson = JSONObject().apply {
                    put("email", correoEntrada.trim())
                    put("password", contrasenaEntrada.trim())
                }.toString()

                val solicitud = Request.Builder()
                    .url(urlCompleta)
                    .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                    .addHeader("Content-Type", "application/json")
                    .post(cuerpoJson.toRequestBody(TIPO_MEDIA_JSON))
                    .build()

                clienteHttp.newCall(solicitud).execute().use { respuesta ->
                    val cuerpoRespuesta = respuesta.body?.string() ?: ""
                    if (respuesta.isSuccessful) {
                        val objetoJson = JSONObject(cuerpoRespuesta)
                        val tokenAcceso = objetoJson.getString("access_token")
                        val objetoUsuario = objetoJson.getJSONObject("user")
                        val idUsuario = objetoUsuario.getString("id")
                        val correo = objetoUsuario.optString("email", correoEntrada)

                        Result.success(ResultadoInicioSesion(idUsuario, tokenAcceso, correo))
                    } else {
                        val mensajeError = try {
                            val jsonError = JSONObject(cuerpoRespuesta)
                            when {
                                jsonError.has("error_description") -> jsonError.getString("error_description")
                                jsonError.has("msg") -> jsonError.getString("msg")
                                jsonError.has("message") -> jsonError.getString("message")
                                jsonError.has("error") -> jsonError.getString("error")
                                else -> "Credenciales inválidas (${respuesta.code})"
                            }
                        } catch (_: Exception) {
                            "Error de inicio de sesión (${respuesta.code})"
                        }
                        Result.failure(Exception(mensajeError))
                    }
                }
            } catch (e: Exception) {
                Result.failure(Exception("Error de conexión: ${e.localizedMessage}"))
            }
        }

    /**
     * Consulta el perfil del chofer en la tabla 'perfiles' (columnas: nombre, apellido, rol)
     */
    suspend fun obtenerPerfil(idUsuario: String, tokenAcceso: String): Result<PerfilChofer> =
        withContext(Dispatchers.IO) {
            try {
                val urlCompleta = "$URL_SUPABASE/rest/v1/perfiles?id=eq.$idUsuario&select=*"
                val solicitud = Request.Builder()
                    .url(urlCompleta)
                    .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                    .addHeader("Authorization", "Bearer $tokenAcceso")
                    .get()
                    .build()

                clienteHttp.newCall(solicitud).execute().use { respuesta ->
                    val cuerpoTexto = respuesta.body?.string() ?: "[]"
                    if (respuesta.isSuccessful) {
                        val arregloJson = JSONArray(cuerpoTexto)
                        if (arregloJson.length() > 0) {
                            val objeto = arregloJson.getJSONObject(0)
                            val nombre = objeto.optString("nombre", "")
                            val apellido = objeto.optString("apellido", "")
                            val nombreCompleto = "$nombre $apellido".trim().ifEmpty { "Chofer Cautiva" }
                            val rol = objeto.optString("rol", "CONDUCTOR")

                            Result.success(PerfilChofer(idUsuario, nombreCompleto, "", rol))
                        } else {
                            Result.success(PerfilChofer(idUsuario, "Chofer Cautiva", "", "CONDUCTOR"))
                        }
                    } else {
                        Result.failure(Exception("Error al consultar perfil (${respuesta.code})"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Registra el inicio de turno en la tabla 'turnos_laborales' (columnas: usuario_id, inicio_programado, fin_programado, inicio_real, estado)
     */
    suspend fun iniciarTurnoLaboral(idChofer: String, tokenAcceso: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val urlCompleta = "$URL_SUPABASE/rest/v1/turnos_laborales"
                val ahoraIso = obtenerMarcaTiempoIsoActual()
                val finProgramadoIso = obtenerMarcaTiempoIsoActual(desfaseHoras = 8)

                val cuerpoJson = JSONObject().apply {
                    put("usuario_id", idChofer)
                    put("inicio_programado", ahoraIso)
                    put("fin_programado", finProgramadoIso)
                    put("inicio_real", ahoraIso)
                    put("estado", "EN_PROGRESO")
                }.toString()

                val solicitud = Request.Builder()
                    .url(urlCompleta)
                    .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                    .addHeader("Authorization", "Bearer $tokenAcceso")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=representation")
                    .post(cuerpoJson.toRequestBody(TIPO_MEDIA_JSON))
                    .build()

                clienteHttp.newCall(solicitud).execute().use { respuesta ->
                    val cuerpoTexto = respuesta.body?.string() ?: ""
                    if (respuesta.isSuccessful) {
                        val arregloJson = JSONArray(cuerpoTexto)
                        val idTurno = if (arregloJson.length() > 0) {
                            arregloJson.getJSONObject(0).getString("id")
                        } else {
                            java.util.UUID.randomUUID().toString()
                        }
                        Result.success(idTurno)
                    } else {
                        Result.failure(Exception("Error al iniciar turno (${respuesta.code})"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Registra el cierre de turno en la tabla 'turnos_laborales' (columnas: fin_real, estado)
     */
    suspend fun finalizarTurnoLaboral(idTurno: String, tokenAcceso: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val urlCompleta = "$URL_SUPABASE/rest/v1/turnos_laborales?id=eq.$idTurno"
                val cuerpoJson = JSONObject().apply {
                    put("fin_real", obtenerMarcaTiempoIsoActual())
                    put("estado", "FINALIZADO")
                }.toString()

                val solicitud = Request.Builder()
                    .url(urlCompleta)
                    .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                    .addHeader("Authorization", "Bearer $tokenAcceso")
                    .addHeader("Content-Type", "application/json")
                    .patch(cuerpoJson.toRequestBody(TIPO_MEDIA_JSON))
                    .build()

                clienteHttp.newCall(solicitud).execute().use { respuesta ->
                    if (respuesta.isSuccessful) {
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Error al finalizar turno (${respuesta.code})"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Actualiza la posición GPS en la tabla 'ubicacion_en_vivo' (columnas: usuario_id, turno_id, latitud, longitud, velocidad_kmh, nivel_bateria, en_movimiento, ultima_actualizacion)
     */
    suspend fun actualizarUbicacionEnVivo(
        idChofer: String,
        idTurno: String?,
        latitud: Double,
        longitud: Double,
        velocidadKmh: Float,
        nivelBateria: Int,
        tokenAcceso: String,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val urlCompleta = "$URL_SUPABASE/rest/v1/ubicacion_en_vivo?on_conflict=usuario_id"
            val cuerpoJson = JSONObject().apply {
                put("usuario_id", idChofer)
                if (!idTurno.isNullOrEmpty()) put("turno_id", idTurno)
                put("latitud", latitud)
                put("longitud", longitud)
                put("velocidad_kmh", velocidadKmh)
                put("nivel_bateria", nivelBateria)
                put("en_movimiento", velocidadKmh > 1.0f)
                put("ultima_actualizacion", obtenerMarcaTiempoIsoActual())
            }.toString()

            val solicitud = Request.Builder()
                .url(urlCompleta)
                .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                .addHeader("Authorization", "Bearer $tokenAcceso")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates")
                .post(cuerpoJson.toRequestBody(TIPO_MEDIA_JSON))
                .build()

            clienteHttp.newCall(solicitud).execute().use { respuesta ->
                if (respuesta.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Error al actualizar ubicacion_en_vivo (${respuesta.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Inserta un registro persistente en la tabla 'historial_ubicaciones' (columnas: usuario_id, turno_id, latitud, longitud, velocidad_kmh, nivel_bateria, fecha_gps)
     */
    suspend fun insertarHistorialUbicacion(
        idChofer: String,
        idTurno: String?,
        latitud: Double,
        longitud: Double,
        velocidadKmh: Float,
        nivelBateria: Int,
        tokenAcceso: String,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val urlCompleta = "$URL_SUPABASE/rest/v1/historial_ubicaciones"
            val cuerpoJson = JSONObject().apply {
                put("usuario_id", idChofer)
                if (!idTurno.isNullOrEmpty()) put("turno_id", idTurno)
                put("latitud", latitud)
                put("longitud", longitud)
                put("velocidad_kmh", velocidadKmh)
                put("nivel_bateria", nivelBateria)
                put("fecha_gps", obtenerMarcaTiempoIsoActual())
            }.toString()

            val solicitud = Request.Builder()
                .url(urlCompleta)
                .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                .addHeader("Authorization", "Bearer $tokenAcceso")
                .addHeader("Content-Type", "application/json")
                .post(cuerpoJson.toRequestBody(TIPO_MEDIA_JSON))
                .build()

            clienteHttp.newCall(solicitud).execute().use { respuesta ->
                if (respuesta.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Error al insertar en historial_ubicaciones (${respuesta.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
