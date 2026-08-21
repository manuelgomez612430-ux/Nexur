package com.naxor.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HotelDao {
    // --- ROOMS ---
    @Query("SELECT * FROM hotel_rooms WHERE isDeleted = 0")
    fun getAllRooms(): Flow<List<HotelRoomEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoom(room: HotelRoomEntity)

    @Update
    suspend fun updateRoom(room: HotelRoomEntity)

    @Query("UPDATE hotel_rooms SET status = :status WHERE id = :roomId")
    suspend fun updateRoomStatus(roomId: String, status: String)

    @Query("SELECT * FROM hotel_rooms WHERE id = :id")
    suspend fun getRoomById(id: String): HotelRoomEntity?

    // --- BOOKINGS ---
    @Query("SELECT * FROM hotel_bookings WHERE isDeleted = 0")
    fun getAllBookings(): Flow<List<HotelBookingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooking(booking: HotelBookingEntity)

    @Update
    suspend fun updateBooking(booking: HotelBookingEntity)

    @Query("SELECT * FROM hotel_bookings WHERE roomId = :roomId AND status = 'CHECKED_IN' LIMIT 1")
    suspend fun getActiveBookingForRoom(roomId: String): HotelBookingEntity?
    
    @Query("SELECT * FROM hotel_bookings WHERE checkInDate >= :startOfDay AND checkInDate <= :endOfDay")
    fun getArrivalsForDay(startOfDay: Long, endOfDay: Long): Flow<List<HotelBookingEntity>>

    // --- LAYOUTS ---
    @Query("SELECT * FROM hotel_room_layouts WHERE isDeleted = 0")
    fun getAllLayouts(): Flow<List<HotelRoomLayoutEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLayout(layout: HotelRoomLayoutEntity)

    @Update
    suspend fun updateLayout(layout: HotelRoomLayoutEntity)

    @Delete
    suspend fun deleteLayout(layout: HotelRoomLayoutEntity)
}
