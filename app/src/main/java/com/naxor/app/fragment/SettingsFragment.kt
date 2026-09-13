package com.naxor.app.fragment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.naxor.app.LoginActivity
import com.naxor.app.R
import com.naxor.app.Updater
import com.naxor.app.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return try {
            _binding = FragmentSettingsBinding.inflate(inflater, container, false)
            binding.root
        } catch (e: Exception) {
            e.printStackTrace()
            // Vista de fallback en caso de error de inflado
            View(requireContext())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadUserProfile()
        setupListeners()
    }

    private fun loadUserProfile() {
        val user = auth.currentUser
        if (user == null) {
            binding.tvUserNameSettings.text = "Modo Invitado"
            binding.tvUserEmailSettings.text = "Inicia sesión para más funciones"
            binding.tvUserAccountTypeSettings.text = "INVITADO"
            binding.tvProfileInitialSettings.text = "?"
            return
        }

        binding.tvUserEmailSettings.text = user.email
        
        // Cargar desde Firestore para mayor precisión si el usuario editó su perfil
        db.collection("users").document(user.uid).collection("profile").document("info")
            .addSnapshotListener { snapshot, _ ->
                if (_binding == null || snapshot == null) return@addSnapshotListener
                
                val name = snapshot.getString("name") ?: user.displayName ?: "Usuario GoMezMart"
                val photoUrl = snapshot.getString("photoUrl") ?: user.photoUrl?.toString()
                
                binding.tvUserNameSettings.text = name
                binding.tvProfileInitialSettings.text = if (name.isNotEmpty()) name[0].uppercaseChar().toString() else "U"
                
                if (!photoUrl.isNullOrEmpty()) {
                    binding.ivProfileImageSettings.visibility = View.VISIBLE
                    Glide.with(this).load(photoUrl).circleCrop().into(binding.ivProfileImageSettings)
                } else {
                    binding.ivProfileImageSettings.visibility = View.GONE
                }
            }

        // Determinar Tipo de Cuenta
        val bizPrefs = requireContext().getSharedPreferences("BusinessPrefs", Context.MODE_PRIVATE)
        val appPrefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        
        val role = appPrefs.getString("user_role", "BUYER")
        val isCollab = bizPrefs.getBoolean("is_collaborator_mode", false)
        
        val accountType = when {
            isCollab -> "🤝 COLABORADOR"
            role == "SELLER" -> "👑 DUEÑO DE NEGOCIO"
            else -> "🛒 COMPRADOR"
        }
        binding.tvUserAccountTypeSettings.text = accountType
    }

    private fun setupListeners() {
        binding.cardSystemSettings.setOnClickListener {
            startActivity(Intent(requireContext(), com.naxor.app.SystemSettingsActivity::class.java))
        }

        binding.cardCheckUpdates.setOnClickListener {
            checkUpdates()
        }

        binding.cardSupportCreator.setOnClickListener {
            startActivity(Intent(requireContext(), com.naxor.app.SupportCreatorActivity::class.java))
        }

        binding.cardRestartTutorial.setOnClickListener {
            val main = activity as? com.naxor.app.MainActivity
            if (main != null) {
                main.navigateToInicio()
                // Usamos el handler de la ventana para que sea seguro tras el cambio de fragmento
                main.window.decorView.postDelayed({
                    main.startInteractiveTutorial()
                }, 600)
            }
        }

        binding.cardLogout.setOnClickListener {
            showLogoutConfirmation()
        }
    }

    private fun checkUpdates() {
        val updater = Updater(requireContext())
        updater.checkAndDownload()
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Cerrar Sesión")
            .setMessage("¿Estás seguro de que deseas salir de tu cuenta?")
            .setPositiveButton("Salir") { _, _ ->
                // Limpiar configuración de rubro para obligar selección en el próximo login
                requireContext().getSharedPreferences("BusinessPrefs", android.content.Context.MODE_PRIVATE)
                    .edit().remove("business_type").apply()

                FirebaseAuth.getInstance().signOut()
                startActivity(Intent(requireContext(), LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                activity?.finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
