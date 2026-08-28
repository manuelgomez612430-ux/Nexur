package com.naxor.app

import android.os.Bundle
import com.naxor.app.databinding.ActivityFutureVisionBinding

class FutureVisionActivity : BaseActivity() {

    private lateinit var binding: ActivityFutureVisionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFutureVisionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarVision.setNavigationOnClickListener { finish() }
    }
}
