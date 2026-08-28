package com.naxor.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HotelDao {
    // --- ROOMS ---
    @Query("SELECT * FROM hotel_rooms WHERE isDeleted = 0 ORDER BY floor ASC, CAST(number AS INTEGER) ASC, number ASC")
    fun getAllRooms(): Flow<List<HotelRoomEntity>>

    @Query("SELECT * FROM hotel_rooms WHERE isDeleted = 0")
    suspend fun getAllRoomsOnce(): List<HotelRoomEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoom(room: HotelRoomEntity)

    @Update
    suspend fun updateRoom(room: HotelRoomEntity)

    @Update
    suspend fun updateRooms(rooms: List<HotelRoomEntity>)

    @Query("UPDATE hotel_rooms SET status = :status WHERE id = :roomId")
    suspend fun updateRoomStatus(roomId: String, status: String)

    @Query("SELECT * FROM hotel_rooms WHERE id = :id")
    suspend fun getRoomById(id: String): HotelRoomEntity?

    @Query("SELECT * FROM hotel_rooms WHERE number = :number AND isDeleted = 0 LIMIT 1")
    suspend fun getRoomByNumber(number: String): HotelRoomEntity?

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

    @Query("SELECT * FROM hotel_payments WHERE isDeleted = 0")
    fun getAllPaymentsFlow(): Flow<List<HotelPaymentEntity>>

    @Query("SELECT * FROM hotel_charges WHERE isDeleted = 0")
    fun getAllChargesFlow(): Flow<List<HotelChargeEntity>>

    // --- MAINTENANCE ---
    @Query("SELECT * FROM hotel_maintenance WHERE isDeleted = 0")
    fun getAllMaintenanceReports(): Flow<List<HotelMaintenanceEntity>>

    @Query("SELECT * FROM hotel_maintenance WHERE roomId = :roomId AND status = 'PENDING' AND isDeleted = 0")
    fun getPendingMaintenanceForRoom(roomId: String): Flow<List<HotelMaintenanceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMaintenanceReport(report: HotelMaintenanceEntity)

    @Update
    suspend fun updateMaintenanceReport(report: HotelMaintenanceEntity)

    @Query("UPDATE hotel_maintenance SET isDeleted = 1")
    suspend fun deleteAllMaintenanceReports()

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

    // --- SYNC HELPERS ---
    @Query("SELECT * FROM hotel_rooms WHERE isSynced = 0")
    suspend fun getUnsyncedRooms(): List<HotelRoomEntity>

    @Query("SELECT * FROM hotel_bookings WHERE isSynced = 0")
    suspend fun getUnsyncedBookings(): List<HotelBookingEntity>

    @Query("SELECT * FROM hotel_charges WHERE isSynced = 0")
    suspend fun getUnsyncedCharges(): List<HotelChargeEntity>

    @Query("SELECT * FROM hotel_payments WHERE isSynced = 0")
    suspend fun getUnsyncedPayments(): List<HotelPaymentEntity>

    @Query("SELECT * FROM hotel_maintenance WHERE isSynced = 0")
    suspend fun getUnsyncedMaintenance(): List<HotelMaintenanceEntity>

    @Query("SELECT * FROM hotel_room_layouts WHERE isSynced = 0")
    suspend fun getUnsyncedLayouts(): List<HotelRoomLayoutEntity>

    @Query("UPDATE hotel_rooms SET isSynced = 1 WHERE id = :id")
    suspend fun markRoomSynced(id: String)

    @Query("UPDATE hotel_bookings SET isSynced = 1 WHERE id = :id")
    suspend fun markBookingSynced(id: String)

    @Query("UPDATE hotel_charges SET isSynced = 1 WHERE id = :id")
    suspend fun markChargeSynced(id: String)

    @Query("UPDATE hotel_payments SET isSynced = 1 WHERE id = :id")
    suspend fun markPaymentSynced(id: String)

    @Query("UPDATE hotel_maintenance SET isSynced = 1 WHERE id = :id")
    suspend fun markMaintenanceSynced(id: String)

    @Query("UPDATE hotel_room_layouts SET isSynced = 1 WHERE id = :id")
    suspend fun markLayoutSynced(id: String)

    @Query("SELECT * FROM hotel_rooms WHERE isDeleted = 1")
    suspend fun getDeletedRooms(): List<HotelRoomEntity>

    @Query("SELECT * FROM hotel_bookings WHERE isDeleted = 1")
    suspend fun getDeletedBookings(): List<HotelBookingEntity>

    @Query("SELECT * FROM hotel_charges WHERE isDeleted = 1")
    suspend fun getDeletedCharges(): List<HotelChargeEntity>

    @Query("SELECT * FROM hotel_payments WHERE isDeleted = 1")
    suspend fun getDeletedPayments(): List<HotelPaymentEntity>

    @Query("SELECT * FROM hotel_maintenance WHERE isDeleted = 1")
    suspend fun getDeletedMaintenance(): List<HotelMaintenanceEntity>

    @Query("SELECT * FROM hotel_room_layouts WHERE isDeleted = 1")
    suspend fun getDeletedLayouts(): List<HotelRoomLayoutEntity>

    @Query("DELETE FROM hotel_rooms WHERE id = :id")
    suspend fun deleteRoomHard(id: String)

    @Query("DELETE FROM hotel_bookings WHERE id = :id")
    suspend fun deleteBookingHard(id: String)

    @Query("DELETE FROM hotel_charges WHERE id = :id")
    suspend fun deleteChargeHard(id: String)

    @Query("DELETE FROM hotel_payments WHERE id = :id")
    suspend fun deletePaymentHard(id: String)

    @Query("DELETE FROM hotel_maintenance WHERE id = :id")
    suspend fun deleteMaintenanceHard(id: String)

    @Query("DELETE FROM hotel_room_layouts WHERE id = :id")
    suspend fun deleteLayoutHard(id: String)
}
