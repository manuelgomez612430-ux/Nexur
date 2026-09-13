package com.naxor.app.fragment

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ListenerRegistration
import com.naxor.app.*
import com.naxor.app.data.AppDatabase
import com.naxor.app.data.UserLocation
import com.naxor.app.databinding.FragmentBuyerAccountBinding
import com.naxor.app.network.SupabaseManager
import com.naxor.app.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class BuyerAccountFragment : Fragment() {

    private var _binding: FragmentBuyerAccountBinding? = null
    private val binding get() = _binding!!
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private var profileRegistration: ListenerRegistration? = null
    private var locationRegistration: ListenerRegistration? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadProfileImage(it) }
    }

    private var currentPhotoPath: String? = null
    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            currentPhotoPath?.let { path ->
                uploadProfileImage(Uri.fromFile(File(path)))
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBuyerAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadUserData()
        setupListeners()
    }

    private fun loadUserData() {
        val user = auth.currentUser
        if (user == null) {
            binding.tvProfileName.text = "Modo Invitado"
            binding.tvProfileEmail.text = "Inicia sesión para una mejor experiencia"
            binding.tvProfileInitial.text = "?"
            binding.tvUserAccountType.text = "COMPRADOR"
            return
        }

        binding.tvProfileEmail.text = user.email

        // Escuchar cambios en tiempo real del perfil en Firestore
        profileRegistration?.remove()
        profileRegistration = db.collection("users").document(user.uid).collection("profile").document("info")
            .addSnapshotListener { snapshot, _ ->
                if (_binding == null || snapshot == null) return@addSnapshotListener

                val firestoreName = snapshot.getString("name")
                val firestorePhoto = snapshot.getString("photoUrl")

                // Prioridad: 1. Firestore, 2. Google/Auth, 3. Default
                val finalName = firestoreName ?: user.displayName ?: "Usuario GoMezMart"
                val finalPhoto = firestorePhoto ?: user.photoUrl?.toString()

                binding.tvProfileName.text = finalName
                binding.tvProfileInitial.text = if (finalName.isNotEmpty()) finalName[0].uppercaseChar().toString() else "?"

                if (!finalPhoto.isNullOrEmpty()) {
                    binding.ivProfileImage.visibility = View.VISIBLE
                    Glide.with(this).load(finalPhoto).circleCrop().into(binding.ivProfileImage)
                } else {
                    binding.ivProfileImage.visibility = View.GONE
                }
            }

        // --- NUEVA LÓGICA: Determinar Tipo de Cuenta (Rol) ---
        lifecycleScope.launch {
            try {
                // 1. Verificar si es Dueño (si tiene algún setting guardado)
                val settingsSnap = db.collection("users").document(user.uid).collection("settings").get().await()
                val isOwner = settingsSnap.documents.any { it.id != "business" && (it.getString("business_name")?.isNotEmpty() == true || it.getString("${it.id}_name")?.isNotEmpty() == true) }

                // 2. Verificar si es Colaborador
                val email = user.email?.lowercase() ?: ""
                val collabSnap = db.collection("access_keys").whereEqualTo("email", email).get().await()
                val isCollab = !collabSnap.isEmpty

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    val roleText = when {
                        isOwner && isCollab -> "👑 DUEÑO Y COLABORADOR 🤝"
                        isOwner -> "👑 DUEÑO DE NEGOCIO"
                        isCollab -> "🤝 COLABORADOR"
                        else -> "🛒 COMPRADOR"
                    }
                    binding.tvUserAccountType.text = roleText
                    
                    // Mostrar opción de revertir solo si no es un comprador puro
                    val canRevert = isOwner || isCollab
                    binding.btnRevertToBuyer.visibility = if (canRevert) View.VISIBLE else View.GONE
                    binding.dividerSwitchRole.visibility = if (canRevert) View.VISIBLE else View.GONE
                    
                    // Si ya es dueño/colab, ocultamos el botón de "Vender en GoMezMart" para no duplicar
                    binding.btnSwitchToSeller.visibility = if (isOwner) View.GONE else View.VISIBLE
                }
            } catch (e: Exception) {
                binding.tvUserAccountType.text = "🛒 COMPRADOR"
            }
        }
    }

    private fun setupListeners() {
        binding.btnLogout.setOnClickListener { showLogoutDialog() }
        
        binding.btnSwitchToSeller.setOnClickListener {
            startActivity(Intent(requireContext(), MyBusinessesActivity::class.java))
        }

        binding.btnSetLocation.setOnClickListener {
            startActivity(Intent(requireContext(), ManageLocationsActivity::class.java))
        }

        binding.btnCollabWithBusiness.setOnClickListener {
            startActivity(Intent(requireContext(), SearchBusinessActivity::class.java))
        }

        binding.cardProfileImage.setOnClickListener {
            showPhotoOptionsDialog()
        }

        binding.btnChangePhotoBadge.setOnClickListener {
            showPhotoOptionsDialog()
        }

        binding.btnEditProfile.setOnClickListener {
            showEditProfileDialog()
        }

        binding.btnRevertToBuyer.setOnClickListener {
            showRevertToBuyerDialog()
        }
    }

    private fun showRevertToBuyerDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("¿Cambiar a modo Comprador?")
            .setMessage("Se restablecerá tu cuenta al perfil de comprador. Si tienes configuraciones de negocio incompletas, se limpiarán para que tu perfil sea 100% personal otra vez.\n\nNota: Esto ocultará las opciones de gestión administrativa.")
            .setPositiveButton("Sí, Restablecer") { _, _ -> resetToBuyerMode() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun resetToBuyerMode() {
        val userId = auth.currentUser?.uid ?: return
        val loading = AlertDialog.Builder(requireContext()).setMessage("Restableciendo perfil...").setCancelable(false).show()

        lifecycleScope.launch {
            try {
                // 1. Limpiar documentos de settings en Firestore (Esto quita el estado de 'Dueño')
                val settingsSnap = db.collection("users").document(userId).collection("settings").get().await()
                for (doc in settingsSnap.documents) {
                    doc.reference.delete().await()
                }

                // 2. Limpiar preferencias locales
                val bizPrefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
                bizPrefs.edit().clear().apply()

                val collabPrefs = requireContext().getSharedPreferences("CollabPermissions", Context.MODE_PRIVATE)
                collabPrefs.edit().clear().apply()

                val appPrefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                appPrefs.edit().putString("user_role", "BUYER").apply()

                withContext(Dispatchers.Main) {
                    loading.dismiss()
                    Toast.makeText(requireContext(), "Cuenta restablecida a modo Comprador 🛒", Toast.LENGTH_LONG).show()
                    
                    // Notificar a la actividad para que refresque el menú inferior de inmediato
                    (activity as? BuyerHomeActivity)?.let { 
                        // Necesitamos una forma de que la actividad refresque su UI
                        it.recreate() 
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loading.dismiss()
                    Toast.makeText(requireContext(), "Error al restablecer perfil", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showPhotoOptionsDialog() {
        val options = arrayOf("📸 Tomar Foto", "🖼️ Seleccionar de Galería")
        AlertDialog.Builder(requireContext())
            .setTitle("Cambiar Foto de Perfil")
            .setItems(options) { _, which ->
                if (which == 0) launchCamera()
                else pickImageLauncher.launch("image/*")
            }
            .show()
    }

    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val photoFile = File(requireContext().getExternalFilesDir(null), "profile_${System.currentTimeMillis()}.jpg")
        currentPhotoPath = photoFile.absolutePath
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", photoFile)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        takePhotoLauncher.launch(intent)
    }

    private fun showEditProfileDialog() {
        val user = auth.currentUser ?: return
        
        lifecycleScope.launch {
            try {
                val doc = db.collection("users").document(user.uid).collection("profile").document("info").get().await()
                
                val currentName = doc.getString("name") ?: user.displayName ?: ""
                val currentDni = doc.getString("dni") ?: ""

                val etName = EditText(requireContext()).apply {
                    hint = "Nombre Completo"
                    setText(currentName)
                }
                val etDni = EditText(requireContext()).apply {
                    hint = "DNI"
                    setText(currentDni)
                }
                
                val layout = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(60, 40, 60, 0)
                    addView(etName)
                    addView(etDni)
                }

                AlertDialog.Builder(requireContext())
                    .setTitle("Editar mi Información")
                    .setView(layout)
                    .setPositiveButton("Guardar") { _, _ ->
                        val newName = etName.text.toString().trim()
                        val newDni = etDni.text.toString().trim()
                        if (newName.isNotEmpty()) {
                            saveProfileInfo(newName, newDni)
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()

            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error al cargar datos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveProfileInfo(name: String, dni: String) {
        val userId = auth.currentUser?.uid ?: return
        val data = mapOf(
            "name" to name,
            "dni" to dni,
            "updatedAt" to System.currentTimeMillis()
        )

        lifecycleScope.launch {
            try {
                db.collection("users").document(userId).collection("profile").document("info")
                    .set(data, SetOptions.merge()).await()
                Toast.makeText(requireContext(), "¡Información actualizada! ✨", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error al guardar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun uploadProfileImage(uri: Uri) {
        val userId = auth.currentUser?.uid ?: return
        val loading = AlertDialog.Builder(requireContext()).setMessage("Actualizando foto de perfil...").setCancelable(false).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = saveUriToFile(uri)
                if (file != null) {
                    val path = "users/$userId/profile_avatar.jpg"
                    val downloadUrl = SupabaseManager.uploadImage("gomezmart-imagenes-usuarios", path, file)
                    
                    if (downloadUrl != null) {
                        db.collection("users").document(userId).collection("profile").document("info")
                            .set(mapOf("photoUrl" to downloadUrl), SetOptions.merge()).await()
                        
                        withContext(Dispatchers.Main) {
                            loading.dismiss()
                            Toast.makeText(requireContext(), "Foto de perfil actualizada ✅", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        throw Exception("Upload failed")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loading.dismiss()
                    Toast.makeText(requireContext(), "Error al subir la imagen", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveUriToFile(uri: Uri): File? {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val file = File(requireContext().cacheDir, "profile_tmp.jpg")
            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun showCollaborationsDialog() {
        lifecycleScope.launch {
            try {
                val email = auth.currentUser?.email?.lowercase() ?: return@launch
                val accessKeys = db.collection("access_keys").whereEqualTo("email", email).get().await()
                
                if (accessKeys.isEmpty) {
                    Toast.makeText(requireContext(), "No tienes invitaciones activas", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val items = accessKeys.documents.map { doc -> 
                    "${doc.getString("businessName")} (${doc.getString("role")})" 
                }.toTypedArray()
                
                AlertDialog.Builder(requireContext())
                    .setTitle("Tus Colaboraciones")
                    .setItems(items) { _, which ->
                        val doc = accessKeys.documents[which]
                        switchToCollaborativeEnvironment(
                            doc.getString("ownerId") ?: "", 
                            doc.getString("businessType") ?: "HOTEL"
                        )
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error al cargar colaboraciones", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchToCollaborativeEnvironment(ownerId: String, businessType: String) {
        val prefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(requireContext()).clearAllTables()
            }
            
            prefs.edit().apply {
                putBoolean("is_collaborator_mode", true)
                putString("collaborator_owner_id", ownerId)
                putString("business_type", businessType)
                apply()
            }
            
            requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                .edit().putString("user_role", "SELLER").apply()

            val intent = Intent(requireContext(), MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            activity?.finish()
        }
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Cerrar Sesión")
            .setMessage("¿Deseas salir del Mercado?")
            .setPositiveButton("Salir") { _, _ ->
                lifecycleScope.launch {
                    SessionManager.clearSessionData(requireContext())
                    val intent = Intent(requireContext(), WelcomeRoleActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    activity?.finish()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        profileRegistration?.remove()
        locationRegistration?.remove()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
