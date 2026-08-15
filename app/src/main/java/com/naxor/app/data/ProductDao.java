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

    @Query("SELECT * FROM products ORDER BY nombre ASC")
    List<ProductEntity> getAllProducts();

    @Query("SELECT * FROM products WHERE nombre LIKE :searchQuery OR categoria LIKE :searchQuery OR codigo LIKE :searchQuery ORDER BY nombre ASC")
    List<ProductEntity> searchProducts(String searchQuery);

    @Query("SELECT * FROM products WHERE categoria = :categoria ORDER BY nombre ASC")
    List<ProductEntity> getProductsByCategory(String categoria);

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    ProductEntity getProductById(int id);

    @Query("SELECT * FROM products WHERE stock <= 0")
    List<ProductEntity> getAgotados();

    @Query("SELECT * FROM products WHERE stock > 0 AND stock <= 5")
    List<ProductEntity> getPocoStock();

    @Query("SELECT DISTINCT categoria FROM products ORDER BY categoria ASC")
    List<String> getUniqueCategories();

    @Query("SELECT * FROM products WHERE nombre = :nombre LIMIT 1")
    ProductEntity getProductByName(String nombre);

    @Query("SELECT * FROM products WHERE (',' || codigo || ',') LIKE ('%,' || :codigo || ',%') OR codigo = :codigo LIMIT 1")
    ProductEntity getProductByCode(String codigo);
}

