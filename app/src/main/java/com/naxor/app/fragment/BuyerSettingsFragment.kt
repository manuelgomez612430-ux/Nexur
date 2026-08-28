package com.naxor.app.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.naxor.app.databinding.FragmentBuyerSettingsBinding
import com.naxor.app.SupportCreatorActivity
import com.naxor.app.Updater

class BuyerSettingsFragment : Fragment() {

    private var _binding: FragmentBuyerSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBuyerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
    }

    private fun setupListeners() {
        binding.btnCheckUpdates.setOnClickListener {
            Updater(requireContext()).checkAndDownload()
        }

        binding.btnDonate.setOnClickListener {
            startActivity(Intent(requireContext(), SupportCreatorActivity::class.java))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
