package com.naxor.app.data

import androidx.room.*

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY nombre ASC")
    suspend fun getAllCustomers(): List<CustomerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(customer: CustomerEntity)

    @Update
    suspend fun update(customer: CustomerEntity)

    @Delete
    suspend fun delete(customer: CustomerEntity)

    @Query("SELECT * FROM customers WHERE isSynced = 0")
    suspend fun getAllUnsyncedCustomers(): List<CustomerEntity>
}
