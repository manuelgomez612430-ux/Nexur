package com.naxor.app

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.data.CollaboratorEntity
import com.naxor.app.databinding.ActivityCollabAccountBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CollabAccountActivity : BaseActivity() {

    private lateinit var binding: ActivityCollabAccountBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollabAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarCollabAccount.setNavigationOnClickListener { finish() }
        
        loadCollabData()
    }

    private fun loadCollabData() {
        val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
        val ownerId = bizPrefs.getString("collaborator_owner_id", null) ?: return
        val userEmail = auth.currentUser?.email?.lowercase() ?: ""

        db.collection("users").document(ownerId)
            .collection("collaborators").document(userEmail)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                
                val collab = snapshot.toObject(CollaboratorEntity::class.java)
                collab?.let { updateUI(it, ownerId) }
            }
    }

    private fun updateUI(collab: CollaboratorEntity, ownerId: String) {
        binding.tvCollabName.text = collab.name
        binding.tvCollabEmail.text = collab.email
        binding.tvCollabInitial.text = if (collab.name.isNotEmpty()) collab.name[0].uppercaseChar().toString() else "?"
        binding.tvCollabRoles.text = if (collab.roles.isNotEmpty()) collab.roles.joinToString(", ") else collab.role

        // Renderizar permisos
        binding.layoutPermissionsList.removeAllViews()
        
        val permissionNames = linkedMapOf(
            "can_manage_rooms" to "Gestión de Habitaciones (Check-in/out) 🏨",
            "can_edit_room_info" to "Editar Precios/Números de Hab. ✏️",
            "can_manage_bookings" to "Gestionar Reservas 🛎️",
            "can_view_history" to "Ver Historial de Huéspedes 📋",
            "can_manage_failures" to "Reportar/Gestionar Averías 🛠️",
            "can_manage_tools" to "Gestionar Herramientas 🧰",
            "can_manage_debts" to "Gestionar Cuentas por Pagar 💸",
            "can_view_metrics" to "Ver Metas y Rendimiento 📊",
            "can_view_cash" to "Ver Arqueo de Caja 💰",
            "can_edit_map" to "Editar Plano/Mapa del Hotel 🗺️",
            "can_edit_store_info" to "Editar Tienda Virtual (Logo, Perfil, Online) 🌐"
        )

        permissionNames.forEach { (key, label) ->
            val hasPermission = collab.permissions[key] ?: false
            val tv = TextView(this).apply {
                text = if (hasPermission) "✅ $label" else "❌ $label"
                setTextColor(if (hasPermission) getColor(R.color.emerald_600) else getColor(R.color.slate_400))
                textSize = 14f
                setPadding(0, 8, 0, 8)
                alpha = if (hasPermission) 1.0f else 0.6f
            }
            binding.layoutPermissionsList.addView(tv)
        }

        binding.btnEditProfile.setOnClickListener {
            showEditProfileDialog(collab, ownerId)
        }
    }

    private fun showEditProfileDialog(collab: CollaboratorEntity, ownerId: String) {
        val etName = EditText(this).apply {
            hint = "Nombre Completo"
            setText(collab.name)
        }
        val etDni = EditText(this).apply {
            hint = "DNI"
            setText(collab.dni)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 0)
            addView(etName)
            addView(etDni)
        }

        AlertDialog.Builder(this)
            .setTitle("Editar mi Perfil")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val newName = etName.text.toString().trim()
                val newDni = etDni.text.toString().trim()
                if (newName.isNotEmpty()) {
                    updateProfile(collab.copy(name = newName, dni = newDni), ownerId)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateProfile(updatedCollab: CollaboratorEntity, ownerId: String) {
        lifecycleScope.launch {
            try {
                db.collection("users").document(ownerId)
                    .collection("collaborators").document(updatedCollab.email)
                    .set(updatedCollab).await()
                Toast.makeText(this@CollabAccountActivity, "Perfil actualizado", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@CollabAccountActivity, "Error al actualizar", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
