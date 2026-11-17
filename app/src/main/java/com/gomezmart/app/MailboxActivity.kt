package com.gomezmart.app

import android.os.Bundle
import com.gomezmart.app.databinding.ActivityMailboxBinding

class MailboxActivity : BaseActivity() {

    private lateinit var binding: ActivityMailboxBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMailboxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.root, applyTop = false, applyBottom = true)
    }
}
