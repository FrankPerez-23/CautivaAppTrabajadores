package com.example.cautivaapp

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UbicacionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarUbicacion(ubicacion: UbicacionLocal): Long

    @Query("SELECT * FROM ubicaciones_pendientes WHERE sincronizado = 0 ORDER BY fechaGps ASC")
    suspend fun obtenerUbicacionesPendientes(): List<UbicacionLocal>

    @Query("DELETE FROM ubicaciones_pendientes WHERE id IN (:ids)")
    suspend fun eliminarUbicacionesSincronizadas(ids: List<Int>)

    @Query("DELETE FROM ubicaciones_pendientes")
    suspend fun limpiarTodo()
}
