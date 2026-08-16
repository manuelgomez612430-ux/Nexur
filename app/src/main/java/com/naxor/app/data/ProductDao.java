package com.naxor.app.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ProductEntity product);

    @Update
    void update(ProductEntity product);

    @Delete
    void delete(ProductEntity product);

    @Query("SELECT * FROM products WHERE isDeleted = 0 ORDER BY nombre ASC")
    List<ProductEntity> getAllProducts();

    @Query("SELECT * FROM products WHERE (nombre LIKE :searchQuery OR categoria LIKE :searchQuery OR codigo LIKE :searchQuery) AND isDeleted = 0 ORDER BY nombre ASC")
    List<ProductEntity> searchProducts(String searchQuery);

    @Query("SELECT * FROM products WHERE categoria = :categoria AND isDeleted = 0 ORDER BY nombre ASC")
    List<ProductEntity> getProductsByCategory(String categoria);

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    ProductEntity getProductById(String id);

    @Query("SELECT * FROM products WHERE stock <= 0 AND isDeleted = 0")
    List<ProductEntity> getAgotados();

    @Query("SELECT * FROM products WHERE stock > 0 AND stock <= 5 AND isDeleted = 0")
    List<ProductEntity> getPocoStock();

    @Query("SELECT DISTINCT categoria FROM products WHERE isDeleted = 0 ORDER BY categoria ASC")
    List<String> getUniqueCategories();

    @Query("SELECT * FROM products WHERE nombre = :nombre AND isDeleted = 0 LIMIT 1")
    ProductEntity getProductByName(String nombre);

    @Query("SELECT * FROM products WHERE ((',' || codigo || ',') LIKE ('%,' || :codigo || ',%') OR codigo = :codigo) AND isDeleted = 0 LIMIT 1")
    ProductEntity getProductByCode(String codigo);

    @Query("SELECT * FROM products WHERE isSynced = 0 AND isDeleted = 0")
    List<ProductEntity> getAllUnsyncedProducts();

    @Query("SELECT * FROM products WHERE isSynced = 0 AND isDeleted = 1")
    List<ProductEntity> getDeletedProducts();

    @Query("SELECT COUNT(*) FROM products WHERE isSynced = 0")
    androidx.lifecycle.LiveData<Integer> getUnsyncedCount();
}

