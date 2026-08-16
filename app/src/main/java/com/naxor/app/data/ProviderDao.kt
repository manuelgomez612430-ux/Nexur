package com.naxor.app.data

import androidx.room.*

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers WHERE isDeleted = 0 ORDER BY nombre ASC")
    suspend fun getAllProviders(): List<ProviderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(provider: ProviderEntity)

    @Update
    suspend fun update(provider: ProviderEntity)

    @Delete
    suspend fun delete(provider: ProviderEntity)

    @Query("SELECT * FROM providers WHERE categoria = :categoria AND isDeleted = 0")
    suspend fun getProvidersByCategory(categoria: String): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getAllUnsyncedProviders(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE isSynced = 0 AND isDeleted = 1")
    suspend fun getDeletedProviders(): List<ProviderEntity>

    @Query("SELECT COUNT(*) FROM providers WHERE isSynced = 0")
    fun getUnsyncedCount(): androidx.lifecycle.LiveData<Int>
}
