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

    @Query("SELECT * FROM sales ORDER BY timestamp DESC")
    List<SaleEntity> getAllSales();

    @Query("SELECT SUM(total) FROM sales")
    double getTotalSalesAmount();

    @Query("SELECT SUM(total) FROM sales WHERE timestamp >= :startTime")
    double getSalesAmountFrom(long startTime);

    @Query("SELECT SUM(total - (costoUnitario * cantidad)) FROM sales WHERE timestamp >= :startTime")
    double getProfitFrom(long startTime);

    @Query("DELETE FROM sales")
    void deleteAllSales();

    @Query("SELECT * FROM sales WHERE isSynced = 0")
    List<SaleEntity> getAllUnsyncedSales();
}

