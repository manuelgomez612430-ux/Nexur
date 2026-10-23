package com.gomezmart.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.gomezmart.app.adapter.CollaboratorAdapter
import com.gomezmart.app.data.CollaboratorEntity
import com.gomezmart.app.databinding.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class HotelManageTeamActivity : BaseActivity() {

    private lateinit var binding: ActivityHotelManageTeamBinding
    private lateinit var adapter: CollaboratorAdapter
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val registrations = mutableListOf<ListenerRegistration>()

    private val availableRoles = listOf("RECEPCIONISTA", "LIMPIADOR", "SEGURIDAD", "ADMINISTRACIÓN", "CONTADOR")
    private var currentRequests = listOf<Map<String, Any>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHotelManageTeamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupListeners()
        loadCollaborators()
        loadRequests()
    }

    private fun setupToolbar() {
        binding.toolbarTeam.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = CollaboratorAdapter(
            onDelete = { collab -> showDeleteConfirmation(collab) },
            onEdit = { collab -> showCollabOptionsDialog(collab) }
        )
        binding.rvCollaborators.layoutManager = LinearLayoutManager(this)
        binding.rvCollaborators.adapter = adapter
    }

    private fun setupListeners() {
        binding.fabAddCollaborator.setOnClickListener { showAddCollabDialog() }
        binding.btnViewRequests.setOnClickListener { showRequestsBottomSheet() }
        binding.btnManageRequests.setOnClickListener { showRequestsBottomSheet() }
    }

    private fun loadRequests() {
        val userId = auth.currentUser?.uid ?: return
        val reg = db.collection("users").document(userId)
            .collection("collaborator_requests")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                lifecycleScope.launch(Dispatchers.IO) {
                    currentRequests = snapshot.documents.mapNotNull { it.data }
                    
                    withContext(Dispatchers.Main) {
                        val hasRequests = currentRequests.isNotEmpty()
                        binding.cardPendingRequests.visibility = if (hasRequests) View.VISIBLE else View.GONE
                        binding.btnViewRequests.visibility = if (hasRequests) View.VISIBLE else View.GONE
                        
                        if (hasRequests) {
                            binding.tvPendingRequestsCount.text = "Tienes ${currentRequests.size} solicitudes nuevas"
                            binding.btnViewRequests.text = "Solicitudes (${currentRequests.size})"
                        }
                    }
                }
            }
        registrations.add(reg)
    }

    private fun showRequestsBottomSheet() {
        val dialog = BottomSheetDialog(this, R.style.Theme_Gomezmart_Dialog)
        val bsBinding = LayoutBottomSheetRequestsBinding.inflate(layoutInflater)
        dialog.setContentView(bsBinding.root)

        bsBinding.tvRequestsTotal.text = "${currentRequests.size} pendientes"
        bsBinding.rvRequestsList.layoutManager = LinearLayoutManager(this)
        
        val requestAdapter = RequestAdapter(
            items = currentRequests,
            onAccept = { req -> 
                dialog.dismiss()
                showAcceptRequestDialog(req) 
            },
            onReject = { req -> 
                val requesterId = req["requesterId"] as? String ?: ""
                rejectCollaborator(requesterId)
            }
        )
        bsBinding.rvRequestsList.adapter = requestAdapter
        
        bsBinding.tvEmptyRequests.visibility = if (currentRequests.isEmpty()) View.VISIBLE else View.GONE

        dialog.show()
    }

    private fun showAcceptRequestDialog(request: Map<String, Any>) {
        val dialogBinding = DialogAcceptCollaboratorBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this, R.style.Theme_Gomezmart_Dialog)
            .setView(dialogBinding.root)
            .create()

        val name = request["requesterName"] as? String ?: ""
        val email = request["requesterEmail"] as? String ?: ""
        val dni = request["requesterDni"] as? String ?: ""
        val requesterId = request["requesterId"] as? String ?: ""

        dialogBinding.tvCollabInfoSummary.text = "👤 Colaborador: $name\n🆔 DNI: $dni\n📧 Email: $email"

        // ROLES
        val selectedRoles = mutableSetOf("RECEPCIONISTA")
        availableRoles.forEach { roleName ->
            val cb = MaterialCheckBox(this).apply {
                text = roleName
                isChecked = roleName == "RECEPCIONISTA"
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedRoles.add(roleName) else selectedRoles.remove(roleName)
                }
            }
            dialogBinding.layoutRolesContainer.addView(cb)
        }

        // PERMISOS
        val currentPermissions = mutableMapOf(
            "can_manage_rooms" to true,
            "can_edit_room_info" to false,
            "can_manage_bookings" to true,
            "can_view_history" to true,
            "can_manage_failures" to true,
            "can_manage_tools" to true,
            "can_manage_debts" to false,
            "can_view_metrics" to false,
            "can_view_cash" to false,
            "can_edit_map" to false,
            "can_edit_store_info" to false
        )

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
            "can_edit_store_info" to "Editar Tienda Virtual 🌐"
        )

        permissionNames.forEach { (key, label) ->
            val switch = MaterialSwitch(this).apply {
                text = label
                isChecked = currentPermissions[key] ?: false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 8) }
                setOnCheckedChangeListener { _, isChecked -> currentPermissions[key] = isChecked }
            }
            dialogBinding.layoutPermissionsContainer.addView(switch)
        }

        dialogBinding.btnCancelAccept.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConfirmAccept.setOnClickListener {
            acceptCollaborator(requesterId, email, name, dni, selectedRoles.toList(), currentPermissions)
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun acceptCollaborator(requesterId: String, email: String, name: String, dni: String, roles: List<String>, permissions: Map<String, Boolean>) {
        val ownerId = auth.currentUser?.uid ?: return
        val collabEmail = email.lowercase().trim()
        lifecycleScope.launch {
            try {
                val primaryRole = if (roles.isNotEmpty()) roles[0] else "RECEPCIONISTA"
                val collab = CollaboratorEntity(
                    id = requesterId, email = collabEmail, name = name, dni = dni,
                    role = primaryRole, roles = roles, status = "ACTIVE", permissions = permissions
                )
                db.collection("users").document(ownerId).collection("collaborators").document(collabEmail).set(collab).await()
                
                // Borrar solicitud
                db.collection("users").document(ownerId).collection("collaborator_requests").document(requesterId).delete().await()

                // Crear Access Key
                val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
                val bType = bizPrefs.getString("business_type", "HOTEL") ?: "HOTEL"
                val accessKey = mapOf(
                    "ownerId" to ownerId, "email" to collabEmail, "role" to primaryRole,
                    "businessType" to bType, "businessName" to bizPrefs.getString("${bType}_name", "Mi Negocio")
                )
                db.collection("access_keys").document(collabEmail).set(accessKey).await()

                Toast.makeText(this@HotelManageTeamActivity, "Colaborador aceptado", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@HotelManageTeamActivity, "Error al aceptar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun rejectCollaborator(requesterId: String) {
        val ownerId = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                db.collection("users").document(ownerId)
                    .collection("collaborator_requests").document(requesterId)
                    .delete().await()
                Toast.makeText(this@HotelManageTeamActivity, "Solicitud rechazada", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {}
        }
    }

    private fun loadCollaborators() {
        val userId = auth.currentUser?.uid ?: return
        val reg = db.collection("users").document(userId).collection("collaborators")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                lifecycleScope.launch(Dispatchers.IO) {
                    val list = snapshot.documents.mapNotNull { it.toObject(CollaboratorEntity::class.java) }
                    withContext(Dispatchers.Main) {
                        adapter.submitList(list)
                        binding.layoutEmptyTeam.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        registrations.add(reg)
    }

    private fun showCollabOptionsDialog(collab: CollaboratorEntity) {
        showEditCollabDialog(collab)
    }

    private fun showEditCollabDialog(collab: CollaboratorEntity) {
        val dialogBinding = DialogAcceptCollaboratorBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this, R.style.Theme_Gomezmart_Dialog)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.tvCollabInfoSummary.text = "👤 Colaborador: ${collab.name}\n🆔 DNI: ${collab.dni}\n📧 Email: ${collab.email}"

        // ROLES
        val selectedRoles = collab.roles.toMutableSet()
        availableRoles.forEach { roleName ->
            val cb = MaterialCheckBox(this).apply {
                text = roleName
                isChecked = selectedRoles.contains(roleName)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedRoles.add(roleName) else selectedRoles.remove(roleName)
                }
            }
            dialogBinding.layoutRolesContainer.addView(cb)
        }

        // PERMISOS
        val currentPermissions = collab.permissions.toMutableMap()
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
            "can_edit_store_info" to "Editar Tienda Virtual 🌐"
        )

        permissionNames.forEach { (key, label) ->
            val switch = MaterialSwitch(this).apply {
                text = label
                isChecked = currentPermissions[key] ?: false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 8) }
                setOnCheckedChangeListener { _, isChecked -> currentPermissions[key] = isChecked }
            }
            dialogBinding.layoutPermissionsContainer.addView(switch)
        }

        dialogBinding.btnConfirmAccept.text = "GUARDAR CAMBIOS"
        dialogBinding.btnCancelAccept.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConfirmAccept.setOnClickListener {
            val updated = collab.copy(roles = selectedRoles.toList(), permissions = currentPermissions)
            updateCollaborator(updated)
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun updateCollaborator(collab: CollaboratorEntity, silent: Boolean = false) {
        val ownerId = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                db.collection("users").document(ownerId).collection("collaborators").document(collab.email).set(collab).await()
                if (!silent) Toast.makeText(this@HotelManageTeamActivity, "Datos actualizados", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {}
        }
    }

    private fun showAddCollabDialog() {
        val dialogBinding = DialogAddCollaboratorBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this, R.style.Theme_Gomezmart_Dialog).setView(dialogBinding.root).create()
        dialogBinding.btnCancelCollab.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnSaveCollab.setOnClickListener {
            val email = dialogBinding.etCollabEmail.text.toString().trim().lowercase()
            val name = dialogBinding.etCollabName.text.toString().trim()
            val role = when(dialogBinding.toggleCollabRole.checkedButtonId) {
                R.id.btnRoleReception -> "RECEPCIONISTA"
                R.id.btnRoleCleaner -> "LIMPIADOR"
                R.id.btnRoleAdmin -> "ADMINISTRACIÓN"
                else -> "RECEPCIONISTA"
            }
            if (email.isNotEmpty() && name.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                inviteCollaborator(email, name, listOf(role))
                dialog.dismiss()
            } else Toast.makeText(this, "Datos inválidos", Toast.LENGTH_SHORT).show()
        }
        dialog.show()
        dialog.window?.let {
            it.setLayout((resources.displayMetrics.widthPixels * 0.95).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            it.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun inviteCollaborator(email: String, name: String, roles: List<String>) {
        val ownerId = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                val primaryRole = if (roles.isNotEmpty()) roles[0] else "RECEPCIONISTA"
                val collab = CollaboratorEntity(email = email, name = name, role = primaryRole, roles = roles, status = "PENDING")
                db.collection("users").document(ownerId).collection("collaborators").document(email).set(collab).await()
                val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
                val bType = bizPrefs.getString("business_type", "HOTEL") ?: "HOTEL"
                val accessKey = mapOf("ownerId" to ownerId, "email" to email, "role" to primaryRole, "businessType" to bType, "businessName" to bizPrefs.getString("${bType}_name", "Mi Negocio"))
                db.collection("access_keys").document(email).set(accessKey).await()
                Toast.makeText(this@HotelManageTeamActivity, "Invitación enviada ✨", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {}
        }
    }

    private fun showDeleteConfirmation(collab: CollaboratorEntity) {
        AlertDialog.Builder(this).setTitle("¿Remover?").setMessage("¿Quitar a ${collab.name}?").setPositiveButton("Sí") { _, _ ->
            lifecycleScope.launch {
                db.collection("users").document(auth.currentUser!!.uid).collection("collaborators").document(collab.email).delete().await()
                db.collection("access_keys").document(collab.email).delete().await()
            }
        }.setNegativeButton("No", null).show()
    }

    override fun onStop() {
        super.onStop()
        registrations.forEach { it.remove() }
        registrations.clear()
    }

    // --- ADAPTER PARA SOLICITUDES ---
    inner class RequestAdapter(
        private val items: List<Map<String, Any>>,
        private val onAccept: (Map<String, Any>) -> Unit,
        private val onReject: (Map<String, Any>) -> Unit
    ) : RecyclerView.Adapter<RequestAdapter.ViewHolder>() {

        inner class ViewHolder(val b: ItemCollaboratorRequestBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = 
            ViewHolder(ItemCollaboratorRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val req = items[position]
            val name = req["requesterName"] as? String ?: "Usuario"
            val email = req["requesterEmail"] as? String ?: ""
            val timestamp = req["timestamp"] as? Long ?: 0L

            with(holder.b) {
                tvReqName.text = name
                tvReqEmail.text = email
                tvReqInitial.text = if (name.isNotEmpty()) name[0].uppercaseChar().toString() else "U"
                
                val sdf = SimpleDateFormat("dd/MM", Locale.getDefault())
                tvReqDate.text = if (timestamp > 0) sdf.format(Date(timestamp)) else ""

                btnAcceptReq.setOnClickListener { onAccept(req) }
                btnRejectReq.setOnClickListener { onReject(req) }
            }
        }
        override fun getItemCount() = items.size
    }
}
