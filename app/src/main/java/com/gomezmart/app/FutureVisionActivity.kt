package com.gomezmart.app

import android.os.Bundle
import com.gomezmart.app.databinding.ActivityFutureVisionBinding

class FutureVisionActivity : BaseActivity() {

    private lateinit var binding: ActivityFutureVisionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFutureVisionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsetsMargins(binding.appBarVision, applyTop = true, applyBottom = false)
        applyWindowInsets(binding.root, applyTop = false, applyBottom = true)

        binding.toolbarVision.setNavigationOnClickListener { finish() }
    }
}
