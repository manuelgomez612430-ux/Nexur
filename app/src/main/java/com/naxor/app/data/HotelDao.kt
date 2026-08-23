package com.naxor.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HotelDao {
    // --- ROOMS ---
    @Query("SELECT * FROM hotel_rooms WHERE isDeleted = 0 ORDER BY floor ASC, CAST(number AS INTEGER) ASC, number ASC")
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

    // --- CHARGES & PAYMENTS ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharge(charge: HotelChargeEntity)

    @Query("SELECT * FROM hotel_charges WHERE bookingId = :bookingId AND isDeleted = 0")
    fun getChargesForBooking(bookingId: String): Flow<List<HotelChargeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: HotelPaymentEntity)

    @Query("SELECT * FROM hotel_payments WHERE bookingId = :bookingId AND isDeleted = 0")
    fun getPaymentsForBooking(bookingId: String): Flow<List<HotelPaymentEntity>>

    @Query("SELECT SUM(amount) FROM hotel_charges WHERE bookingId = :bookingId AND isDeleted = 0")
    suspend fun getTotalCharges(bookingId: String): Double?

    @Query("SELECT SUM(amount) FROM hotel_payments WHERE bookingId = :bookingId AND isDeleted = 0")
    suspend fun getTotalPayments(bookingId: String): Double?

    // --- MAINTENANCE ---
    @Query("SELECT * FROM hotel_maintenance WHERE isDeleted = 0")
    fun getAllMaintenanceReports(): Flow<List<HotelMaintenanceEntity>>

    @Query("SELECT * FROM hotel_maintenance WHERE roomId = :roomId AND status = 'PENDING' AND isDeleted = 0")
    fun getPendingMaintenanceForRoom(roomId: String): Flow<List<HotelMaintenanceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMaintenanceReport(report: HotelMaintenanceEntity)

    @Update
    suspend fun updateMaintenanceReport(report: HotelMaintenanceEntity)

    // --- LAYOUTS ---
    @Query("SELECT * FROM hotel_room_layouts WHERE isDeleted = 0")
    fun getAllLayouts(): Flow<List<HotelRoomLayoutEntity>>

    @Query("SELECT * FROM hotel_room_layouts WHERE isDeleted = 0 AND floorId = :floorId")
    fun getLayoutsByFloor(floorId: Int): Flow<List<HotelRoomLayoutEntity>>

    @Query("SELECT DISTINCT floorId FROM hotel_room_layouts WHERE isDeleted = 0 ORDER BY floorId ASC")
    fun getAvailableFloors(): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLayout(layout: HotelRoomLayoutEntity)

    @Update
    suspend fun updateLayout(layout: HotelRoomLayoutEntity)

    @Delete
    suspend fun deleteLayout(layout: HotelRoomLayoutEntity)
}
