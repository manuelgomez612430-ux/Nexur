package com.gomezmart.app.data;

import androidx.lifecycle.LiveData;
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ProductEntity> products);

    @Update
    void update(ProductEntity product);

    @Delete
    void delete(ProductEntity product);

    @Query("DELETE FROM products WHERE id = :id")
    void deleteById(String id);

    @Query("SELECT * FROM products WHERE isDeleted = 0 ORDER BY nombre ASC")
    List<ProductEntity> getAllProducts();

    @Query("SELECT * FROM products WHERE (nombre LIKE :searchQuery OR categoria LIKE :searchQuery OR codigo LIKE :searchQuery) AND isDeleted = 0 ORDER BY nombre ASC")
    List<ProductEntity> searchProducts(String searchQuery);

    @Query("SELECT * FROM products WHERE categoria = :categoria AND isDeleted = 0 ORDER BY nombre ASC")
    List<ProductEntity> getProductsByCategory(String categoria);

    @Query("SELECT * FROM products WHERE (nombre LIKE :search OR categoria LIKE :search OR codigo LIKE :search) AND (:cat = 'Todos' OR categoria LIKE :cat) AND isDeleted = 0")
    List<ProductEntity> getFilteredAndSorted(String search, String cat);

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
    LiveData<Integer> getUnsyncedCount();

    @Query("UPDATE products SET isSynced = 1 WHERE id = :id")
    void markSynced(String id);
}

