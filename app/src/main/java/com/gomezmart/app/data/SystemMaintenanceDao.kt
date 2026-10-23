package com.gomezmart.app.data

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

    @Query("DELETE FROM loan_budgets")
    suspend fun clearLoanBudgets()

    // --- GENERAL ---
    @Query("DELETE FROM commitments")
    suspend fun clearCommitments()

    @Query("DELETE FROM movement_logs")
    suspend fun clearLogs()

    @Query("DELETE FROM calculations")
    suspend fun clearCalculations()

    @Query("DELETE FROM providers")
    suspend fun clearProviders()

    @Query("DELETE FROM customers")
    suspend fun clearCustomers()

    @Query("DELETE FROM business_debts")
    suspend fun clearBusinessDebts()

    @Query("DELETE FROM cash_sessions")
    suspend fun clearCashSessions()
}
