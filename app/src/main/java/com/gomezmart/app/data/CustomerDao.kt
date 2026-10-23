package com.gomezmart.app.data

import androidx.room.*

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers WHERE isDeleted = 0 ORDER BY nombre ASC")
    suspend fun getAllCustomers(): List<CustomerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(customer: CustomerEntity)

    @Update
    suspend fun update(customer: CustomerEntity)

    @Delete
    suspend fun delete(customer: CustomerEntity)

    @Query("SELECT * FROM customers WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getAllUnsyncedCustomers(): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE isSynced = 0 AND isDeleted = 1")
    suspend fun getDeletedCustomers(): List<CustomerEntity>

    @Query("UPDATE customers SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)
}
