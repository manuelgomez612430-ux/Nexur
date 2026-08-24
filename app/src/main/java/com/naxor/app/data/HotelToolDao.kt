package com.naxor.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HotelToolDao {
    @Query("SELECT * FROM hotel_tools WHERE isDeleted = 0 ORDER BY registrationDate DESC")
    fun getAllTools(): Flow<List<HotelToolEntity>>

    @Query("SELECT * FROM hotel_tools WHERE isDeleted = 0 AND status = 'ACTIVE'")
    suspend fun getAllActiveToolsOnce(): List<HotelToolEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTool(tool: HotelToolEntity)

    @Update
    suspend fun updateTool(tool: HotelToolEntity)

    @Query("SELECT * FROM hotel_tools WHERE code = :code AND isDeleted = 0 LIMIT 1")
    suspend fun getToolByCode(code: String): HotelToolEntity?

    @Query("DELETE FROM hotel_tools WHERE id = :id")
    suspend fun deleteToolHard(id: String)
}
