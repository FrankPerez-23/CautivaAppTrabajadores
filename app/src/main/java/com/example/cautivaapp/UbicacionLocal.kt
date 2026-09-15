package com.example.cautivaapp

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ubicaciones_pendientes")
data class UbicacionLocal(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val usuarioId: String,
    val turnoId: String?,
    val latitud: Double,
    val longitud: Double,
    val velocidadKmh: Double,
    val direccion: Double? = null,
    val nivelBateria: Int,
    val fechaGps: Long = System.currentTimeMillis(),
    val sincronizado: Boolean = false,
)
