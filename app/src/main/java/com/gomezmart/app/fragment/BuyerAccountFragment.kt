package com.gomezmart.app.fragment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.gomezmart.app.BaseActivity
import com.gomezmart.app.LoginActivity
import com.gomezmart.app.databinding.FragmentBuyerAccountBinding

class BuyerAccountFragment : Fragment() {

    private var _binding: FragmentBuyerAccountBinding? = null
    private val binding get() = _binding!!
    private val auth by lazy { FirebaseAuth.getInstance() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBuyerAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? BaseActivity)?.applyWindowInsets(binding.toolbarAccount, applyTop = true, applyBottom = false)

        binding.btnGuestLoginAccount.setOnClickListener {
            val intent = Intent(requireContext(), LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            activity?.finish()
        }

        binding.cardDownloadedModulesAccount.setOnClickListener {
            val intent = Intent(requireContext(), com.gomezmart.app.DownloadedModulesActivity::class.java)
            (activity as? BaseActivity)?.startActivityWithFade(intent)
        }

        binding.cardGuestSettings.setOnClickListener {
            val intent = Intent(requireContext(), com.gomezmart.app.NotificationSettingsActivity::class.java)
            (activity as? BaseActivity)?.startActivityWithFade(intent)
        }

        binding.cardAccountSettings.setOnClickListener {
            val intent = Intent(requireContext(), com.gomezmart.app.AccountSettingsActivity::class.java)
            (activity as? BaseActivity)?.startActivityWithFade(intent)
        }

        updateAccountUI()
    }

    override fun onResume() {
        super.onResume()
        updateAccountUI()
    }

    private fun updateAccountUI() {
        if (_binding == null) return
        val user = auth.currentUser
        val baseActivity = activity as? BaseActivity
        val isGuest = baseActivity?.isGuestUser() == true || user == null || user.isAnonymous

        if (isGuest) {
            binding.tvGuestName.text = "Modo Invitado"
            binding.tvAccountEmail.visibility = View.GONE
            binding.tvAccountBadge.text = "⚡ SESIÓN LOCAL ANÓNIMA"
            binding.tvAccountProfileInitial.text = "👤"
            binding.ivAccountProfilePhoto.visibility = View.GONE
            binding.cardGuestActionPrompt.visibility = View.VISIBLE
        } else {
            binding.cardGuestActionPrompt.visibility = View.GONE

            val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val userName = prefs.getString("user_full_name", user?.displayName ?: "Usuario registrado")
            val userPhoto = prefs.getString("user_photo_url", user?.photoUrl?.toString())
            val userEmail = user?.email ?: prefs.getString("user_email", "") ?: ""

            binding.tvGuestName.text = if (!userName.isNullOrBlank()) userName else "Usuario registrado"

            if (userEmail.isNotEmpty()) {
                binding.tvAccountEmail.visibility = View.VISIBLE
                binding.tvAccountEmail.text = userEmail
            } else {
                binding.tvAccountEmail.visibility = View.GONE
            }

            binding.tvAccountBadge.text = "🛒 COMPRADOR VERIFICADO"

            if (!userPhoto.isNullOrEmpty()) {
                binding.ivAccountProfilePhoto.visibility = View.VISIBLE
                Glide.with(this).load(userPhoto).circleCrop().into(binding.ivAccountProfilePhoto)
            } else {
                binding.ivAccountProfilePhoto.visibility = View.GONE
                val initial = if (!userName.isNullOrEmpty()) userName[0].uppercaseChar().toString() else "👤"
                binding.tvAccountProfileInitial.text = initial
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
