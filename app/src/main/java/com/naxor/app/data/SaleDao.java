package com.naxor.app.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface SaleDao {
    @Insert
    void insert(SaleEntity sale);

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
}

