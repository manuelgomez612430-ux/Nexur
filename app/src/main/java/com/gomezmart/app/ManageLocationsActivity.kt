package com.gomezmart.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.gomezmart.app.data.UserLocation
import com.gomezmart.app.databinding.ActivityManageLocationsBinding
import com.gomezmart.app.databinding.ItemUserLocationBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.*

class ManageLocationsActivity : BaseActivity() {

    private lateinit var binding: ActivityManageLocationsBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var adapter: LocationsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageLocationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manejar insets para evitar obstrucción por la barra de estado
        applyWindowInsets(binding.appBarManageLocations, applyTop = true, applyBottom = false)

        binding.btnBackManageLocations.setOnClickListener { finish() }
        
        setupRecyclerView()
        loadLocations()

        binding.btnAddLocation.setOnClickListener {
            startActivity(Intent(this, AddLocationActivity::class.java))
        }
    }

    private fun setupRecyclerView() {
        adapter = LocationsAdapter(
            onSetPrimary = { setAsPrimaryLocation(it) },
            onDelete = { showDeleteConfirmation(it) }
        )
        binding.rvUserLocations.layoutManager = LinearLayoutManager(this)
        binding.rvUserLocations.adapter = adapter
    }

    private fun loadLocations() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).collection("locations")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                val list = snapshot.documents.mapNotNull { it.toObject(UserLocation::class.java) }
                // Prioridad absoluta a la ubicación por defecto al inicio de la lista
                val sortedList = list.sortedWith(
                    compareByDescending<UserLocation> { it.isDefault }
                        .thenByDescending { it.timestamp }
                )
                adapter.submitList(sortedList)
            }
    }

    private fun setAsPrimaryLocation(location: UserLocation) {
        val userId = auth.currentUser?.uid ?: return

        lifecycleScope.launch {
            try {
                // 1. Marcar como por defecto en Firestore la seleccionada y desmarcar las demás
                val colRef = db.collection("users").document(userId).collection("locations")
                val allDocs = colRef.get().await()

                val batch = db.batch()
                for (doc in allDocs.documents) {
                    val isTarget = doc.id == location.id
                    batch.update(doc.reference, "isDefault", isTarget)
                }
                batch.commit().await()

                // 2. Geocodificar para obtener ciudad y código postal precisos
                var city = ""
                var postalCode = ""
                
                if (location.latitude != 0.0 && location.longitude != 0.0) {
                    withContext(Dispatchers.IO) {
                        try {
                            val geocoder = Geocoder(this@ManageLocationsActivity, Locale.getDefault())
                            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                            if (!addresses.isNullOrEmpty()) {
                                val addr = addresses[0]
                                city = addr.locality ?: addr.subAdminArea ?: ""
                                postalCode = addr.postalCode ?: ""
                            }
                        } catch (e: Exception) {}
                    }
                }

                // 3. Guardar en BuyerPrefs para actualizar el Mercado inmediatamente
                val prefs = getSharedPreferences("BuyerPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putString("buyer_city", city)
                    .putString("buyer_postal_code", postalCode)
                    .putString("buyer_address", location.address)
                    .apply()

                Toast.makeText(this@ManageLocationsActivity, "Ubicación principal establecida 📍", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Toast.makeText(this@ManageLocationsActivity, "Error al actualizar prioridad", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteConfirmation(location: UserLocation) {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar ubicación?")
            .setMessage("¿Estás seguro de eliminar '${location.title}'?")
            .setPositiveButton("Eliminar") { _, _ ->
                val userId = auth.currentUser?.uid ?: return@setPositiveButton
                db.collection("users").document(userId).collection("locations").document(location.id).delete()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    inner class LocationsAdapter(
        private val onSetPrimary: (UserLocation) -> Unit,
        private val onDelete: (UserLocation) -> Unit
    ) : RecyclerView.Adapter<LocationsAdapter.ViewHolder>() {
        private var items = listOf<UserLocation>()

        fun submitList(newItems: List<UserLocation>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemUserLocationBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemUserLocationBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.b) {
                tvLocationTitle.text = item.title
                tvLocationAddress.text = item.address
                
                if (item.reference.isNotEmpty()) {
                    tvLocationReference.visibility = View.VISIBLE
                    tvLocationReference.text = "Ref: ${item.reference}"
                } else {
                    tvLocationReference.visibility = View.GONE
                }

                if (item.isDefault) {
                    tvDefaultBadge.visibility = View.VISIBLE
                    btnSetPriority.visibility = View.GONE
                    cardLocationItem.strokeColor = Color.parseColor("#7C3AED")
                } else {
                    tvDefaultBadge.visibility = View.GONE
                    btnSetPriority.visibility = View.VISIBLE
                    cardLocationItem.strokeColor = Color.parseColor("#E2E8F0")
                }

                btnSetPriority.setOnClickListener { onSetPrimary(item) }
                root.setOnClickListener { onSetPrimary(item) }
                btnDeleteLocation.setOnClickListener { onDelete(item) }
            }
        }

        override fun getItemCount() = items.size
    }
}