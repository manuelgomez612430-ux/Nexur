package com.gomezmart.app.util

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.gomezmart.app.data.MarketplaceProduct

object FavoritesManager {
    private const val PREFS_NAME = "FavoritesPrefs"
    private const val KEY_FAVORITES = "favorites_list"

    fun toggleFavorite(context: Context, product: MarketplaceProduct) {
        val favorites = getFavorites(context).toMutableList()
        val isFav = isFavorite(context, product.id)
        
        if (isFav) {
            favorites.removeAll { it.id == product.id }
        } else {
            favorites.add(0, product)
        }
        
        saveFavorites(context, favorites)
        syncWithFirebase(favorites)
    }

    fun isFavorite(context: Context, productId: String): Boolean {
        return getFavorites(context).any { it.id == productId }
    }

    fun getFavorites(context: Context): List<MarketplaceProduct> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<MarketplaceProduct>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveFavorites(context: Context, list: List<MarketplaceProduct>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Gson().toJson(list)
        prefs.edit().putString(KEY_FAVORITES, json).apply()
    }

    /**
     * Sincroniza la lista local con Firebase Firestore.
     */
    private fun syncWithFirebase(list: List<MarketplaceProduct>) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val db = FirebaseFirestore.getInstance()
        
        val data = mapOf("items" to list)
        
        db.collection("users").document(user.uid)
            .collection("profile").document("favorites")
            .set(data, SetOptions.merge())
            .addOnFailureListener { e ->
                Log.e("FavoritesManager", "Error syncing favorites: ${e.message}")
            }
    }

    /**
     * Carga los favoritos desde Firebase (útil al iniciar sesión o cambiar de dispositivo).
     */
    fun loadFromFirebase(context: Context, onComplete: (() -> Unit)? = null) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val db = FirebaseFirestore.getInstance()
        
        db.collection("users").document(user.uid)
            .collection("profile").document("favorites")
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    try {
                        val items = doc.get("items") as? List<Map<String, Any>>
                        if (items != null) {
                            val gson = Gson()
                            val json = gson.toJson(items)
                            val type = object : TypeToken<List<MarketplaceProduct>>() {}.type
                            val list: List<MarketplaceProduct> = gson.fromJson(json, type)
                            saveFavorites(context, list)
                        }
                    } catch (e: Exception) {
                        Log.e("FavoritesManager", "Error parsing favorites from cloud: ${e.message}")
                    }
                }
                onComplete?.invoke()
            }
    }
}
