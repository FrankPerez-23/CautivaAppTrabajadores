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
        val tokenRefresco: String?,
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
                        val tokenRefresco = if (objetoJson.has("refresh_token")) objetoJson.getString("refresh_token") else null
                        val objetoUsuario = objetoJson.getJSONObject("user")
                        val idUsuario = objetoUsuario.getString("id")
                        val correo = objetoUsuario.optString("email", correoEntrada)

                        Result.success(ResultadoInicioSesion(idUsuario, tokenAcceso, tokenRefresco, correo))
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
                val tokenAUsar = if (tokenAcceso.isNotBlank()) tokenAcceso else CLAVE_ANONIMA_SUPABASE
                val urlCompleta = "$URL_SUPABASE/rest/v1/perfiles?id=eq.$idUsuario&select=*"
                
                val ejecutarPeticion = { token: String ->
                    Request.Builder()
                        .url(urlCompleta)
                        .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                }

                var respuesta = clienteHttp.newCall(ejecutarPeticion(tokenAUsar)).execute()
                var cuerpoTexto = respuesta.body?.string() ?: "[]"

                if (respuesta.code == 401 && tokenAUsar != CLAVE_ANONIMA_SUPABASE) {
                    respuesta.close()
                    respuesta = clienteHttp.newCall(ejecutarPeticion(CLAVE_ANONIMA_SUPABASE)).execute()
                    cuerpoTexto = respuesta.body?.string() ?: "[]"
                }

                respuesta.use { resp ->
                    if (resp.isSuccessful) {
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
                        Result.failure(Exception("Error al consultar perfil (${resp.code})"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Consulta los vehículos activos en la tabla 'vehiculos' (estado = 'ACTIVO')
     */
    suspend fun obtenerVehiculosActivos(tokenAcceso: String): Result<List<Vehiculo>> =
        withContext(Dispatchers.IO) {
            try {
                val tokenAUsar = if (tokenAcceso.isNotBlank()) tokenAcceso else CLAVE_ANONIMA_SUPABASE
                val urlCompleta = "$URL_SUPABASE/rest/v1/vehiculos?estado=eq.ACTIVO&select=*"
                
                val ejecutarPeticion = { token: String ->
                    Request.Builder()
                        .url(urlCompleta)
                        .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                }

                var respuesta = clienteHttp.newCall(ejecutarPeticion(tokenAUsar)).execute()
                var cuerpoTexto = respuesta.body?.string() ?: "[]"

                if (respuesta.code == 401 && tokenAUsar != CLAVE_ANONIMA_SUPABASE) {
                    respuesta.close()
                    respuesta = clienteHttp.newCall(ejecutarPeticion(CLAVE_ANONIMA_SUPABASE)).execute()
                    cuerpoTexto = respuesta.body?.string() ?: "[]"
                }

                respuesta.use { resp ->
                    if (resp.isSuccessful) {
                        val arregloJson = JSONArray(cuerpoTexto)
                        val listaVehiculos = mutableListOf<Vehiculo>()
                        for (i in 0 until arregloJson.length()) {
                            listaVehiculos.add(Vehiculo.desdeJson(arregloJson.getJSONObject(i)))
                        }
                        Result.success(listaVehiculos)
                    } else {
                        Result.failure(Exception("Error al consultar vehículos (${resp.code})"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Registra el inicio de turno en la tabla 'turnos_laborales' (columnas: usuario_id, vehiculo_id, inicio_programado, fin_programado, inicio_real, estado)
     */
    suspend fun iniciarTurnoLaboral(idChofer: String, vehiculoId: String?, tokenAcceso: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val urlCompleta = "$URL_SUPABASE/rest/v1/turnos_laborales"
                val ahoraIso = obtenerMarcaTiempoIsoActual()
                val finProgramadoIso = obtenerMarcaTiempoIsoActual(desfaseHoras = 8)

                val cuerpoJson = JSONObject().apply {
                    put("usuario_id", idChofer)
                    if (!vehiculoId.isNullOrEmpty()) {
                        put("vehiculo_id", vehiculoId)
                    } else {
                        put("vehiculo_id", JSONObject.NULL)
                    }
                    put("inicio_programado", ahoraIso)
                    put("fin_programado", finProgramadoIso)
                    put("inicio_real", ahoraIso)
                    put("estado", "EN_PROGRESO")
                }.toString()

                val tokenAUsar = if (tokenAcceso.isNotBlank()) tokenAcceso else CLAVE_ANONIMA_SUPABASE

                val construirSolicitud = { token: String ->
                    Request.Builder()
                        .url(urlCompleta)
                        .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Prefer", "return=representation")
                        .post(cuerpoJson.toRequestBody(TIPO_MEDIA_JSON))
                        .build()
                }

                var respuesta = clienteHttp.newCall(construirSolicitud(tokenAUsar)).execute()
                var cuerpoTexto = respuesta.body?.string() ?: ""

                // Si el token JWT de la sesión expiró (HTTP 401), reintentar con la clave anónima respaldada en la ACL
                if (respuesta.code == 401 && tokenAUsar != CLAVE_ANONIMA_SUPABASE) {
                    respuesta.close()
                    respuesta = clienteHttp.newCall(construirSolicitud(CLAVE_ANONIMA_SUPABASE)).execute()
                    cuerpoTexto = respuesta.body?.string() ?: ""
                }

                respuesta.use { resp ->
                    if (resp.isSuccessful) {
                        val arregloJson = JSONArray(cuerpoTexto)
                        val idTurno = if (arregloJson.length() > 0) {
                            arregloJson.getJSONObject(0).getString("id")
                        } else {
                            java.util.UUID.randomUUID().toString()
                        }
                        Result.success(idTurno)
                    } else {
                        Result.failure(Exception("Error al iniciar turno (${resp.code})"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun iniciarTurnoLaboral(idChofer: String, tokenAcceso: String): Result<String> =
        iniciarTurnoLaboral(idChofer, null, tokenAcceso)

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

                val tokenAUsar = if (tokenAcceso.isNotBlank()) tokenAcceso else CLAVE_ANONIMA_SUPABASE

                val construirSolicitud = { token: String ->
                    Request.Builder()
                        .url(urlCompleta)
                        .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Content-Type", "application/json")
                        .patch(cuerpoJson.toRequestBody(TIPO_MEDIA_JSON))
                        .build()
                }

                var respuesta = clienteHttp.newCall(construirSolicitud(tokenAUsar)).execute()

                if (respuesta.code == 401 && tokenAUsar != CLAVE_ANONIMA_SUPABASE) {
                    respuesta.close()
                    respuesta = clienteHttp.newCall(construirSolicitud(CLAVE_ANONIMA_SUPABASE)).execute()
                }

                respuesta.use { resp ->
                    if (resp.isSuccessful) {
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Error al finalizar turno (${resp.code})"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Actualiza la posición GPS en la tabla 'ubicacion_en_vivo' (columnas: usuario_id, turno_id, latitud, longitud, velocidad_kmh, direccion, nivel_bateria, en_movimiento, ultima_actualizacion)
     */
    suspend fun actualizarUbicacionEnVivo(
        idChofer: String,
        idTurno: String?,
        latitud: Double,
        longitud: Double,
        velocidadKmh: Float,
        direccion: Double? = null,
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
                if (direccion != null) put("direccion", direccion)
                put("nivel_bateria", nivelBateria)
                put("en_movimiento", velocidadKmh > 1.0f)
                put("ultima_actualizacion", obtenerMarcaTiempoIsoActual())
            }.toString()

            val tokenAUsar = if (tokenAcceso.isNotBlank()) tokenAcceso else CLAVE_ANONIMA_SUPABASE

            val construirSolicitud = { token: String ->
                Request.Builder()
                    .url(urlCompleta)
                    .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "resolution=merge-duplicates")
                    .post(cuerpoJson.toRequestBody(TIPO_MEDIA_JSON))
                    .build()
            }

            var respuesta = clienteHttp.newCall(construirSolicitud(tokenAUsar)).execute()

            if (respuesta.code == 401 && tokenAUsar != CLAVE_ANONIMA_SUPABASE) {
                respuesta.close()
                respuesta = clienteHttp.newCall(construirSolicitud(CLAVE_ANONIMA_SUPABASE)).execute()
            }

            respuesta.use { resp ->
                if (resp.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Error al actualizar ubicacion_en_vivo (${resp.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Inserta un registro persistente en la tabla 'historial_ubicaciones' (columnas: usuario_id, turno_id, latitud, longitud, velocidad_kmh, direccion, nivel_bateria, fecha_gps)
     */
    suspend fun insertarHistorialUbicacion(
        idChofer: String,
        idTurno: String?,
        latitud: Double,
        longitud: Double,
        velocidadKmh: Float,
        direccion: Double? = null,
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
                if (direccion != null) put("direccion", direccion)
                put("nivel_bateria", nivelBateria)
                put("fecha_gps", obtenerMarcaTiempoIsoActual())
            }.toString()

            val tokenAUsar = if (tokenAcceso.isNotBlank()) tokenAcceso else CLAVE_ANONIMA_SUPABASE

            val construirSolicitud = { token: String ->
                Request.Builder()
                    .url(urlCompleta)
                    .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(cuerpoJson.toRequestBody(TIPO_MEDIA_JSON))
                    .build()
            }

            var respuesta = clienteHttp.newCall(construirSolicitud(tokenAUsar)).execute()

            if (respuesta.code == 401 && tokenAUsar != CLAVE_ANONIMA_SUPABASE) {
                respuesta.close()
                respuesta = clienteHttp.newCall(construirSolicitud(CLAVE_ANONIMA_SUPABASE)).execute()
            }

            respuesta.use { resp ->
                if (resp.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Error al insertar en historial_ubicaciones (${resp.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Elimina el registro de la tabla 'ubicacion_en_vivo' al finalizar turno (Política de Privacidad)
     */
    suspend fun eliminarUbicacionEnVivo(idUsuario: String, tokenAcceso: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val urlCompleta = "$URL_SUPABASE/rest/v1/ubicacion_en_vivo?usuario_id=eq.$idUsuario"
                val tokenAUsar = if (tokenAcceso.isNotBlank()) tokenAcceso else CLAVE_ANONIMA_SUPABASE

                val construirSolicitud = { token: String ->
                    Request.Builder()
                        .url(urlCompleta)
                        .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Content-Type", "application/json")
                        .delete()
                        .build()
                }

                var respuesta = clienteHttp.newCall(construirSolicitud(tokenAUsar)).execute()

                if (respuesta.code == 401 && tokenAUsar != CLAVE_ANONIMA_SUPABASE) {
                    respuesta.close()
                    respuesta = clienteHttp.newCall(construirSolicitud(CLAVE_ANONIMA_SUPABASE)).execute()
                }

                respuesta.use { resp ->
                    if (resp.isSuccessful) {
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Error al eliminar ubicación en vivo (${resp.code})"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Revisa si el trabajador tiene un turno activo en la tabla 'turnos_laborales' (estado = 'EN_PROGRESO')
     */
    suspend fun verificarJornadaActivaUsuario(idUsuario: String, tokenAcceso: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val tokenAUsar = if (tokenAcceso.isNotBlank()) tokenAcceso else CLAVE_ANONIMA_SUPABASE
                val urlCompleta = "$URL_SUPABASE/rest/v1/turnos_laborales?usuario_id=eq.$idUsuario&estado=eq.EN_PROGRESO&select=id"
                
                val construirSolicitud = { token: String ->
                    Request.Builder()
                        .url(urlCompleta)
                        .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                }

                var respuesta = clienteHttp.newCall(construirSolicitud(tokenAUsar)).execute()
                var cuerpoTexto = respuesta.body?.string() ?: "[]"

                if (respuesta.code == 401 && tokenAUsar != CLAVE_ANONIMA_SUPABASE) {
                    respuesta.close()
                    respuesta = clienteHttp.newCall(construirSolicitud(CLAVE_ANONIMA_SUPABASE)).execute()
                    cuerpoTexto = respuesta.body?.string() ?: "[]"
                }

                respuesta.use { resp ->
                    if (resp.isSuccessful) {
                        val arregloJson = JSONArray(cuerpoTexto)
                        Result.success(arregloJson.length() > 0)
                    } else {
                        Result.success(false)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Registra el identificador único del dispositivo activo en la columna 'metadatos' de la tabla 'perfiles'
     */
    suspend fun actualizarIdDispositivoPerfil(
        idUsuario: String,
        idDispositivo: String,
        tokenAcceso: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val tokenAUsar = if (tokenAcceso.isNotBlank()) tokenAcceso else CLAVE_ANONIMA_SUPABASE
            val urlCompleta = "$URL_SUPABASE/rest/v1/perfiles?id=eq.$idUsuario"
            
            val metadatosJson = JSONObject().apply {
                put("id_dispositivo", idDispositivo)
                put("ultimo_login", obtenerMarcaTiempoIsoActual())
            }
            val cuerpoJson = JSONObject().apply {
                put("metadatos", metadatosJson)
            }.toString()

            val construirSolicitud = { token: String ->
                Request.Builder()
                    .url(urlCompleta)
                    .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .patch(cuerpoJson.toRequestBody(TIPO_MEDIA_JSON))
                    .build()
            }

            var respuesta = clienteHttp.newCall(construirSolicitud(tokenAUsar)).execute()

            if (respuesta.code == 401 && tokenAUsar != CLAVE_ANONIMA_SUPABASE) {
                respuesta.close()
                respuesta = clienteHttp.newCall(construirSolicitud(CLAVE_ANONIMA_SUPABASE)).execute()
            }

            respuesta.use { resp ->
                if (resp.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Error al actualizar dispositivo (${resp.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Consulta el 'id_dispositivo' registrado actualmente en los metadatos del perfil en Supabase
     */
    suspend fun obtenerIdDispositivoRegistrado(idUsuario: String, tokenAcceso: String): Result<String?> =
        withContext(Dispatchers.IO) {
            try {
                val tokenAUsar = if (tokenAcceso.isNotBlank()) tokenAcceso else CLAVE_ANONIMA_SUPABASE
                val urlCompleta = "$URL_SUPABASE/rest/v1/perfiles?id=eq.$idUsuario&select=metadatos"

                val construirSolicitud = { token: String ->
                    Request.Builder()
                        .url(urlCompleta)
                        .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                }

                var respuesta = clienteHttp.newCall(construirSolicitud(tokenAUsar)).execute()
                var cuerpoTexto = respuesta.body?.string() ?: "[]"

                if (respuesta.code == 401 && tokenAUsar != CLAVE_ANONIMA_SUPABASE) {
                    respuesta.close()
                    respuesta = clienteHttp.newCall(construirSolicitud(CLAVE_ANONIMA_SUPABASE)).execute()
                    cuerpoTexto = respuesta.body?.string() ?: "[]"
                }

                respuesta.use { resp ->
                    if (resp.isSuccessful) {
                        val arregloJson = JSONArray(cuerpoTexto)
                        if (arregloJson.length() > 0) {
                            val metadatos = arregloJson.getJSONObject(0).optJSONObject("metadatos")
                            val idDisp = if (metadatos != null && metadatos.has("id_dispositivo")) metadatos.getString("id_dispositivo") else null
                            Result.success(idDisp)
                        } else {
                            Result.success(null)
                        }
                    } else {
                        Result.success(null)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Finaliza cualquier jornada laboral activa/huérfana para un usuario en 'turnos_laborales'
     */
    suspend fun finalizarJornadasActivasDeUsuario(idUsuario: String, tokenAcceso: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val tokenAUsar = if (tokenAcceso.isNotBlank()) tokenAcceso else CLAVE_ANONIMA_SUPABASE
                val urlCompleta = "$URL_SUPABASE/rest/v1/turnos_laborales?usuario_id=eq.$idUsuario&estado=eq.EN_PROGRESO"
                val cuerpoJson = JSONObject().apply {
                    put("fin_real", obtenerMarcaTiempoIsoActual())
                    put("estado", "FINALIZADO")
                }.toString()

                val construirSolicitud = { token: String ->
                    Request.Builder()
                        .url(urlCompleta)
                        .addHeader("apikey", CLAVE_ANONIMA_SUPABASE)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Content-Type", "application/json")
                        .patch(cuerpoJson.toRequestBody(TIPO_MEDIA_JSON))
                        .build()
                }

                var respuesta = clienteHttp.newCall(construirSolicitud(tokenAUsar)).execute()

                if (respuesta.code == 401 && tokenAUsar != CLAVE_ANONIMA_SUPABASE) {
                    respuesta.close()
                    respuesta = clienteHttp.newCall(construirSolicitud(CLAVE_ANONIMA_SUPABASE)).execute()
                }

                respuesta.use { resp ->
                    if (resp.isSuccessful) {
                        eliminarUbicacionEnVivo(idUsuario, tokenAcceso)
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Error al finalizar jornada previa (${resp.code})"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

