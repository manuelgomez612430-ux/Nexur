package com.naxor.app.data

import androidx.room.*

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY nombre ASC")
    suspend fun getAllProviders(): List<ProviderEntity>

    @Insert
    suspend fun insert(provider: ProviderEntity)

    @Update
    suspend fun update(provider: ProviderEntity)

    @Delete
    suspend fun delete(provider: ProviderEntity)

    @Query("SELECT * FROM providers WHERE categoria = :categoria")
    suspend fun getProvidersByCategory(categoria: String): List<ProviderEntity>
}
