package com.gomezmart.app.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.gomezmart.app.AccountSettingsActivity
import com.gomezmart.app.BaseActivity
import com.gomezmart.app.BuildConfig
import com.gomezmart.app.R
import com.gomezmart.app.databinding.FragmentBuyerSettingsBinding
import com.gomezmart.app.SupportCreatorActivity
import com.gomezmart.app.Updater

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
        binding.btnCheckUpdates.setOnClickListener {
            Updater(requireContext()).checkAndDownload()
        }

        binding.btnDonate.setOnClickListener {
            startActivity(Intent(requireContext(), SupportCreatorActivity::class.java))
        }

        binding.btnAccountSettings.setOnClickListener {
            startActivity(Intent(requireContext(), AccountSettingsActivity::class.java))
        }

        binding.btnShareWithFriends.setOnClickListener {
            shareWithFriends()
        }
    }

    private fun shareWithFriends() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "¡Únete a GoMezMart! 🚀")
            val shareText = "¡Hola! 👋 Te invito a probar GoMezMart 🚀, la app ideal para comprar, vender y gestionar negocios online.\n\n" +
                    "Descárgala ahora aquí:\n" +
                    "https://gomezmart.com/download\n\n" +
                    "¡Descúbrela hoy! ✨"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(shareIntent, "Compartir GoMezMart con amigos"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
