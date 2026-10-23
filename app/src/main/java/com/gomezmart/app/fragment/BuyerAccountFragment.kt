package com.gomezmart.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.gomezmart.app.BaseActivity
import com.gomezmart.app.R
import com.gomezmart.app.databinding.FragmentBuyerAccountBinding

class BuyerAccountFragment : Fragment() {

    private var _binding: FragmentBuyerAccountBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBuyerAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? BaseActivity)?.applyWindowInsets(binding.toolbarAccount, applyTop = true, applyBottom = false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}