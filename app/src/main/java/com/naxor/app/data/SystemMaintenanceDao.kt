package com.naxor.app.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface SystemMaintenanceDao {
    // --- HOTEL ---
    @Query("DELETE FROM hotel_rooms")
    suspend fun clearHotelRooms()

    @Query("DELETE FROM hotel_bookings")
    suspend fun clearHotelBookings()

    @Query("DELETE FROM hotel_room_layouts")
    suspend fun clearHotelLayouts()

    @Query("DELETE FROM hotel_charges")
    suspend fun clearHotelCharges()

    @Query("DELETE FROM hotel_payments")
    suspend fun clearHotelPayments()

    @Query("DELETE FROM hotel_maintenance")
    suspend fun clearHotelMaintenance()

    @Query("DELETE FROM hotel_tools")
    suspend fun clearHotelTools()

    // --- PRODUCTS ---
    @Query("DELETE FROM products")
    suspend fun clearProducts()

    @Query("DELETE FROM sales")
    suspend fun clearSales()

    @Query("DELETE FROM price_history")
    suspend fun clearPriceHistory()

    @Query("DELETE FROM debtors")
    suspend fun clearFiados()

    @Query("DELETE FROM debts")
    suspend fun clearDebtDetails()

    // --- LOANS ---
    @Query("DELETE FROM loan_clients")
    suspend fun clearLoanClients()

    @Query("DELETE FROM loans")
    suspend fun clearLoans()

    @Query("DELETE FROM loan_installments")
    suspend fun clearLoanInstallments()

    @Query("DELETE FROM loan_expenses")
    suspend fun clearLoanExpenses()

    @Query("DELETE FROM loan_payments")
    suspend fun clearLoanPayments()
}
