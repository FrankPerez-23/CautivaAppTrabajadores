package com.example.cautivaapp

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [UbicacionLocal::class], version = 1, exportSchema = false)
abstract class BaseDatosLocal : RoomDatabase() {

    abstract fun ubicacionDao(): UbicacionDao

    companion object {
        @Volatile
        private var INSTANCIA: BaseDatosLocal? = null

        fun obtenerInstancia(contexto: Context): BaseDatosLocal {
            return INSTANCIA ?: synchronized(this) {
                val instancia = Room.databaseBuilder(
                    contexto.applicationContext,
                    BaseDatosLocal::class.java,
                    "cautiva_local.db"
                ).build()
                INSTANCIA = instancia
                instancia
            }
        }
    }
}
