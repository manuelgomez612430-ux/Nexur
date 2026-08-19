package com.naxor.app.fragment

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
import com.google.firebase.auth.FirebaseAuth
import com.naxor.app.LoginActivity
import com.naxor.app.R
import com.naxor.app.SettingsActivity
import com.naxor.app.Updater
import com.naxor.app.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Mostrar el correo del usuario actual
        val user = FirebaseAuth.getInstance().currentUser
        binding.tvUserEmailSettings.text = user?.email ?: "Sin sesión activa"

        binding.cardBusinessSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        binding.cardSystemSettings.setOnClickListener {
            Toast.makeText(requireContext(), "Configuración del Sistema (Próximamente)", Toast.LENGTH_SHORT).show()
        }

        binding.cardActivateSystem.setOnClickListener {
            Toast.makeText(requireContext(), "Método de Pago (Próximamente)", Toast.LENGTH_SHORT).show()
        }

        binding.cardCheckUpdates.setOnClickListener {
            checkUpdates()
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
                FirebaseAuth.getInstance().signOut()
                startActivity(Intent(requireContext(), LoginActivity::class.java))
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
