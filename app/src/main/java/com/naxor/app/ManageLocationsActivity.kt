package com.naxor.app

import android.content.Intent
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
import com.naxor.app.data.UserLocation
import com.naxor.app.databinding.ActivityManageLocationsBinding
import com.naxor.app.databinding.ItemUserLocationBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ManageLocationsActivity : BaseActivity() {

    private lateinit var binding: ActivityManageLocationsBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var adapter: LocationsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageLocationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarManageLocations.setNavigationOnClickListener { finish() }
        
        setupRecyclerView()
        loadLocations()

        binding.btnAddLocation.setOnClickListener {
            startActivity(Intent(this, AddLocationActivity::class.java))
        }
    }

    private fun setupRecyclerView() {
        adapter = LocationsAdapter(
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
                adapter.submitList(list.sortedByDescending { it.timestamp })
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

    inner class LocationsAdapter(private val onDelete: (UserLocation) -> Unit) : RecyclerView.Adapter<LocationsAdapter.ViewHolder>() {
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

                btnDeleteLocation.setOnClickListener { onDelete(item) }
            }
        }

        override fun getItemCount() = items.size
    }
}
