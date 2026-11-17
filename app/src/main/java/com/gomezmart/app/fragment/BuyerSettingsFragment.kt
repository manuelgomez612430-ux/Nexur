package com.gomezmart.app.fragment

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.gomezmart.app.AccountSettingsActivity
import com.gomezmart.app.BaseActivity
import com.gomezmart.app.BuildConfig
import com.gomezmart.app.ManageLocationsActivity
import com.gomezmart.app.R
import com.gomezmart.app.databinding.FragmentBuyerSettingsBinding

class BuyerSettingsFragment : Fragment() {

    private var _binding: FragmentBuyerSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBuyerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Manejar insets para que la cabecera no sea tapada por la barra de estado
        (activity as? BaseActivity)?.applyWindowInsets(binding.root.findViewById(R.id.toolbarSettings), applyTop = true, applyBottom = false)

        setupVersionText()
        setupListeners()
    }

    private fun setupVersionText() {
        val versionName = try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            pInfo.versionName
        } catch (e: Exception) {
            BuildConfig.VERSION_NAME
        }
        binding.tvAppVersion.text = "GoMezMart v$versionName"
    }

    private fun setupListeners() {
        binding.btnAccountSettings.setOnClickListener {
            val baseActivity = activity as? BaseActivity
            if (baseActivity?.requireLoginForGuest("ver tu perfil y ajustes de cuenta") == true) return@setOnClickListener
            startActivity(Intent(requireContext(), AccountSettingsActivity::class.java))
        }

        binding.btnManageLocations.setOnClickListener {
            val baseActivity = activity as? BaseActivity
            if (baseActivity?.requireLoginForGuest("gestionar tus direcciones de entrega") == true) return@setOnClickListener
            startActivity(Intent(requireContext(), ManageLocationsActivity::class.java))
        }

        binding.btnNotificationSettings.setOnClickListener {
            openNotificationSettings()
        }

        binding.btnPrivacyPolicy.setOnClickListener {
            openPrivacyPolicyDialog()
        }

        binding.btnDownloadedModules.setOnClickListener {
            startActivity(Intent(requireContext(), com.gomezmart.app.DownloadedModulesActivity::class.java))
        }

        binding.btnShareWithFriends.setOnClickListener {
            shareWithFriends()
        }
    }

    private fun openNotificationSettings() {
        startActivity(Intent(requireContext(), com.gomezmart.app.NotificationSettingsActivity::class.java))
    }

    private fun openPrivacyPolicyDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("📄 Política de Privacidad y Datos")
            .setMessage(
                "GoMezMart protege tu privacidad y la seguridad de tus datos conforme a las políticas de Google Play Store.\n\n" +
                "• Recolección de Datos: Solo procesamos tu nombre, teléfono y ubicación para gestionar tus pedidos y envíos.\n" +
                "• Eliminación de Cuenta: Puedes solicitar la eliminación definitiva de tu cuenta y datos en cualquier momento desde 'Perfil y Seguridad de Cuenta'.\n\n" +
                "¿Deseas abrir la Política de Privacidad completa en la web?"
            )
            .setPositiveButton("Ver en la Web") { _, _ ->
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://gomezmart.com/privacy"))
                    startActivity(browserIntent)
                } catch (e: Exception) {}
            }
            .setNegativeButton("Entendido", null)
            .show()
    }

    private fun shareWithFriends() {
        Toast.makeText(requireContext(), "Muy pronto estará disponible el enlace de descarga 🚀✨", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
