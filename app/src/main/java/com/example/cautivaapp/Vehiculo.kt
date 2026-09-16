package com.example.cautivaapp

import org.json.JSONObject

data class Vehiculo(
    val id: String,
    val placa: String,
    val marca: String?,
    val modelo: String?,
    val anio: Int?,
    val estado: String
) {
    companion object {
        fun desdeJson(objetoJson: JSONObject): Vehiculo {
            return Vehiculo(
                id = objetoJson.getString("id"),
                placa = objetoJson.getString("placa"),
                marca = if (objetoJson.has("marca") && !objetoJson.isNull("marca")) objetoJson.getString("marca") else null,
                modelo = if (objetoJson.has("modelo") && !objetoJson.isNull("modelo")) objetoJson.getString("modelo") else null,
                anio = if (objetoJson.has("anio") && !objetoJson.isNull("anio")) objetoJson.getInt("anio") else null,
                estado = objetoJson.optString("estado", "ACTIVO")
            )
        }
    }

    override fun toString(): String {
        val descripcionMarcaModelo = listOfNotNull(marca, modelo).joinToString(" ").trim()
        return if (descripcionMarcaModelo.isNotEmpty()) {
            "$placa - $descripcionMarcaModelo"
        } else {
            placa
        }
    }
}
