package com.naxor.app.data

import androidx.room.*

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY nombre ASC")
    suspend fun getAllCustomers(): List<CustomerEntity>

    @Insert
    suspend fun insert(customer: CustomerEntity)

    @Update
    suspend fun update(customer: CustomerEntity)

    @Delete
    suspend fun delete(customer: CustomerEntity)
}
