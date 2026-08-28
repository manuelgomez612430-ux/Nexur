package com.naxor.app

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
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.naxor.app.adapter.CollaboratorAdapter
import com.naxor.app.data.CollaboratorEntity
import com.naxor.app.databinding.ActivityHotelManageTeamBinding
import com.naxor.app.databinding.DialogAddCollaboratorBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class HotelManageTeamActivity : BaseActivity() {

    private lateinit var binding: ActivityHotelManageTeamBinding
    private lateinit var adapter: CollaboratorAdapter
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val registrations = mutableListOf<ListenerRegistration>()

    private val availableRoles = listOf("RECEPCIONISTA", "LIMPIADOR", "SEGURIDAD", "ADMINISTRACIÓN", "CONTADOR")

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

    private fun showCollabOptionsDialog(collab: CollaboratorEntity) {
        showEditCollabDialog(collab)
    }

    private fun showEditCollabDialog(collab: CollaboratorEntity) {
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }
        scrollView.addView(container)
        
        val etName = EditText(this).apply {
            hint = "Nombre Completo"
            setText(collab.name)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        val etDni = EditText(this).apply {
            hint = "DNI"
            setText(collab.dni)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        
        container.addView(etName)
        container.addView(etDni)

        // --- SECCIÓN DE ROLES ---
        container.addView(TextView(this).apply {
            text = "ROLES ASIGNADOS"
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.purple_600))
            textSize = 12f
            setPadding(0, 20, 0, 10)
        })

        val selectedRoles = collab.roles.toMutableSet()
        availableRoles.forEach { roleName ->
            val cb = MaterialCheckBox(this).apply {
                text = roleName
                isChecked = selectedRoles.contains(roleName)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedRoles.add(roleName) else selectedRoles.remove(roleName)
                }
            }
            container.addView(cb)
        }

        // --- SECCIÓN DE PERMISOS ---
        container.addView(TextView(this).apply {
            text = "PERMISOS DE ACCESO"
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.purple_600))
            textSize = 12f
            setPadding(0, 20, 0, 10)
        })

        val currentPermissions = collab.permissions.toMutableMap()
        val permissionsLayout = createPermissionsLayout(currentPermissions) { key, value ->
            currentPermissions[key] = value
        }
        container.addView(permissionsLayout)

        val btnRemove = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "Remover del Equipo ❌"
            setTextColor(Color.RED)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { 
                showDeleteConfirmation(collab)
            }
        }
        container.addView(btnRemove)

        AlertDialog.Builder(this)
            .setTitle("Configurar Colaborador")
            .setView(scrollView)
            .setPositiveButton("Guardar") { _, _ ->
                val updated = collab.copy(
                    name = etName.text.toString().trim(),
                    dni = etDni.text.toString().trim(),
                    roles = selectedRoles.toList(),
                    permissions = currentPermissions
                )
                updateCollaborator(updated)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateCollaborator(collab: CollaboratorEntity, silent: Boolean = false) {
        val ownerId = auth.currentUser?.uid ?: return
        val collabEmail = collab.email.lowercase().trim()
        lifecycleScope.launch {
            try {
                // Asegurar que el objeto interno tenga el email en minúsculas
                val fixedCollab = collab.copy(email = collabEmail)
                
                db.collection("users").document(ownerId)
                    .collection("collaborators").document(collabEmail)
                    .set(fixedCollab).await()
                
                // Actualizar Access Key con el rol principal
                val primaryRole = if (fixedCollab.roles.isNotEmpty()) fixedCollab.roles[0] else "COLABORADOR"
                db.collection("access_keys").document(collabEmail)
                    .update("role", primaryRole).await()

                if (!silent) Toast.makeText(this@HotelManageTeamActivity, "Datos actualizados", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (!silent) Toast.makeText(this@HotelManageTeamActivity, "Error al sincronizar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupListeners() {
        binding.fabAddCollaborator.setOnClickListener { showAddCollabDialog() }
        binding.btnViewRequests.setOnClickListener { showRequestsDialog() }
        binding.btnManageRequests.setOnClickListener { showRequestsDialog() }
    }

    private var currentRequests = listOf<Map<String, Any>>()

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

    private fun showRequestsDialog() {
        val items = currentRequests.map { "${it["requesterName"]} (${it["requesterEmail"]})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Solicitudes de Colaboración")
            .setItems(items) { _, which ->
                val request = currentRequests[which]
                showAcceptRequestDialog(request)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showAcceptRequestDialog(request: Map<String, Any>) {
        val name = request["requesterName"] as? String ?: ""
        val email = request["requesterEmail"] as? String ?: ""
        val dni = request["requesterDni"] as? String ?: ""
        val requesterId = request["requesterId"] as? String ?: ""

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }
        scrollView.addView(container)

        val tvInfo = TextView(this).apply {
            val info = "👤 Colaborador: $name\n🆔 DNI: $dni\n📧 Email: $email\n\nConfigura los permisos para este colaborador:"
            text = info
            textSize = 14f
            setTextColor(getColor(R.color.slate_700))
        }
        container.addView(tvInfo)

        // --- SECCIÓN DE ROLES ---
        container.addView(TextView(this).apply {
            text = "ROLES"
            setTypeface(null, Typeface.BOLD)
            textSize = 12f
            setPadding(0, 20, 0, 10)
        })

        val selectedRoles = mutableSetOf("RECEPCIONISTA")
        availableRoles.forEach { roleName ->
            val cb = MaterialCheckBox(this).apply {
                text = roleName
                isChecked = roleName == "RECEPCIONISTA"
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedRoles.add(roleName) else selectedRoles.remove(roleName)
                }
            }
            container.addView(cb)
        }

        // --- SECCIÓN DE PERMISOS ---
        container.addView(TextView(this).apply {
            text = "PERMISOS"
            setTypeface(null, Typeface.BOLD)
            textSize = 12f
            setPadding(0, 20, 0, 10)
        })

        val currentPermissions = mutableMapOf(
            "can_view_metrics" to false,
            "can_manage_debts" to false,
            "can_manage_rooms" to true,
            "can_edit_room_info" to false,
            "can_delete_rooms" to false,
            "can_view_history" to true,
            "can_manage_bookings" to true,
            "can_manage_failures" to true,
            "can_manage_tools" to true,
            "can_edit_map" to false,
            "can_view_cash" to false,
            "can_edit_store_info" to false
        )

        val permissionsLayout = createPermissionsLayout(currentPermissions) { key, value ->
            currentPermissions[key] = value
        }
        container.addView(permissionsLayout)

        AlertDialog.Builder(this)
            .setTitle("Aceptar Colaborador")
            .setView(scrollView)
            .setPositiveButton("Aceptar") { _, _ ->
                acceptCollaborator(requesterId, email, name, dni, selectedRoles.toList(), currentPermissions)
            }
            .setNegativeButton("Rechazar") { _, _ -> rejectCollaborator(requesterId) }
            .show()
    }

    private fun createPermissionsLayout(permissions: MutableMap<String, Boolean>, onUpdate: (String, Boolean) -> Unit): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 10)
        }

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
            val switch = MaterialSwitch(this).apply {
                text = label
                isChecked = permissions[key] ?: false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 6, 0, 6) }
                
                setOnCheckedChangeListener { _, isChecked ->
                    onUpdate(key, isChecked)
                }
            }
            layout.addView(switch)
        }

        return layout
    }

    private fun acceptCollaborator(requesterId: String, email: String, name: String, dni: String, roles: List<String>, permissions: Map<String, Boolean>) {
        val ownerId = auth.currentUser?.uid ?: return
        val collabEmail = email.lowercase().trim()
        lifecycleScope.launch {
            try {
                val primaryRole = if (roles.isNotEmpty()) roles[0] else "RECEPCIONISTA"
                val collab = CollaboratorEntity(
                    id = requesterId,
                    email = collabEmail,
                    name = name,
                    dni = dni,
                    role = primaryRole,
                    roles = roles,
                    status = "ACTIVE",
                    permissions = permissions
                )
                
                // 1. Añadir a colaboradores usando el email como ID (Unificado)
                db.collection("users").document(ownerId)
                    .collection("collaborators").document(collabEmail)
                    .set(collab).await()

                // Borrar cualquier duplicado antiguo que pudiera existir con el requesterId (UID)
                if (requesterId != collabEmail) {
                    db.collection("users").document(ownerId)
                        .collection("collaborators").document(requesterId)
                        .delete().await()
                }

                // 2. Borrar solicitud
                db.collection("users").document(ownerId)
                    .collection("collaborator_requests").document(requesterId)
                    .delete().await()

                // 3. Crear Access Key
                val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
                val businessType = bizPrefs.getString("business_type", "HOTEL") ?: "HOTEL"
                val accessKey = mapOf(
                    "ownerId" to ownerId,
                    "email" to collabEmail,
                    "role" to primaryRole,
                    "businessType" to businessType,
                    "businessName" to bizPrefs.getString("${businessType}_name", "Mi Negocio")
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
            db.collection("users").document(ownerId)
                .collection("collaborator_requests").document(requesterId)
                .delete().await()
            Toast.makeText(this@HotelManageTeamActivity, "Solicitud rechazada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadCollaborators() {
        val userId = auth.currentUser?.uid ?: return
        
        val reg = db.collection("users").document(userId)
            .collection("collaborators")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                lifecycleScope.launch(Dispatchers.IO) {
                    val list = snapshot.documents.mapNotNull { it.toObject(CollaboratorEntity::class.java) }
                    
                    // --- LIMPIEZA DE SEGURIDAD: Borrar duplicados antiguos que no usen el email como ID ---
                    snapshot.documents.forEach { doc ->
                        if (!doc.id.contains("@")) {
                            db.collection("users").document(userId)
                                .collection("collaborators").document(doc.id)
                                .delete()
                        }
                    }

                    withContext(Dispatchers.Main) {
                        adapter.submitList(list)
                        binding.layoutEmptyTeam.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        registrations.add(reg)
    }


    override fun onStop() {
        super.onStop()
        registrations.forEach { it.remove() }
        registrations.clear()
    }

    private fun showAddCollabDialog() {
        val dialogBinding = DialogAddCollaboratorBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this, R.style.Theme_GoMezMart_Dialog)
            .setView(dialogBinding.root)
            .create()

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

            if (email.isEmpty() || name.isEmpty()) {
                Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Correo inválido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            inviteCollaborator(email, name, listOf(role))
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.let {
            val width = (resources.displayMetrics.widthPixels * 0.95).toInt()
            it.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            it.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun inviteCollaborator(email: String, name: String, roles: List<String>) {
        val ownerId = auth.currentUser?.uid ?: return
        val collabEmail = email.lowercase().trim()
        
        lifecycleScope.launch {
            try {
                val primaryRole = if (roles.isNotEmpty()) roles[0] else "RECEPCIONISTA"
                val collab = CollaboratorEntity(
                    email = collabEmail,
                    name = name,
                    role = primaryRole,
                    roles = roles,
                    status = "PENDING"
                )
                
                // 1. Guardar en el perfil del dueño usando el email como ID
                db.collection("users").document(ownerId)
                    .collection("collaborators").document(collabEmail)
                    .set(collab).await()

                // 2. Registrar en la colección global de llaves de acceso (Access Keys)
                val bizPrefs = getSharedPreferences("BusinessPrefs", MODE_PRIVATE)
                val businessType = bizPrefs.getString("business_type", "HOTEL") ?: "HOTEL"
                val accessKey = mapOf(
                    "ownerId" to ownerId,
                    "email" to collabEmail,
                    "role" to primaryRole,
                    "businessType" to businessType,
                    "businessName" to bizPrefs.getString("${businessType}_name", "Mi Negocio")
                )
                
                db.collection("access_keys").document(collabEmail).set(accessKey).await()
                
                Toast.makeText(this@HotelManageTeamActivity, "Invitación enviada ✨", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@HotelManageTeamActivity, "Error al invitar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteConfirmation(collab: CollaboratorEntity) {
        AlertDialog.Builder(this)
            .setTitle("¿Remover colaborador?")
            .setMessage("¿Estás seguro de quitar a ${collab.name} del equipo? Perderá el acceso de inmediato.")
            .setPositiveButton("Remover") { _, _ ->
                val ownerId = auth.currentUser?.uid ?: return@setPositiveButton
                val collabEmail = collab.email.lowercase().trim()
                lifecycleScope.launch {
                    db.collection("users").document(ownerId)
                        .collection("collaborators").document(collabEmail)
                        .delete().await()
                    
                    db.collection("access_keys").document(collabEmail).delete().await()
                    Toast.makeText(this@HotelManageTeamActivity, "Colaborador removido", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
