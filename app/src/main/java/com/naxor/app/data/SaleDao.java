package com.naxor.app.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface SaleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(SaleEntity sale);

    @Update
    void update(SaleEntity sale);

    @Delete
    void delete(SaleEntity sale);

    @Query("SELECT * FROM sales WHERE id = :id LIMIT 1")
    SaleEntity getSaleById(String id);

    @Query("SELECT * FROM sales WHERE isDeleted = 0 ORDER BY timestamp DESC")
    List<SaleEntity> getAllSales();

    @Query("SELECT SUM(total) FROM sales WHERE isDeleted = 0")
    double getTotalSalesAmount();

    @Query("SELECT SUM(total) FROM sales WHERE timestamp >= :startTime AND isDeleted = 0")
    double getSalesAmountFrom(long startTime);

    @Query("SELECT SUM(total - (costoUnitario * cantidad)) FROM sales WHERE timestamp >= :startTime AND isDeleted = 0")
    double getProfitFrom(long startTime);

    @Query("SELECT SUM(total) FROM sales WHERE timestamp >= :startTime AND timestamp <= :endTime AND isDeleted = 0")
    double getSalesAmountInRange(long startTime, long endTime);

    @Query("SELECT SUM(total - (costoUnitario * cantidad)) FROM sales WHERE timestamp >= :startTime AND timestamp <= :endTime AND isDeleted = 0")
    double getProfitInRange(long startTime, long endTime);

    @Query("SELECT * FROM sales WHERE timestamp >= :startTime AND timestamp <= :endTime AND isDeleted = 0 ORDER BY timestamp DESC")
    List<SaleEntity> getSalesInRange(long startTime, long endTime);

    @Query("UPDATE sales SET isDeleted = 1, isSynced = 0")
    void deleteAllSales();

    @Query("SELECT * FROM sales WHERE isSynced = 0 AND isDeleted = 0")
    List<SaleEntity> getAllUnsyncedSales();

    @Query("SELECT * FROM sales WHERE isSynced = 0 AND isDeleted = 1")
    List<SaleEntity> getDeletedSales();
}

