package com.naxor.app

import android.os.Bundle
import com.naxor.app.databinding.ActivityInstruccionesBinding

class InstruccionesActivity : BaseActivity() {

    private lateinit var binding: ActivityInstruccionesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstruccionesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackInstrucciones.setOnClickListener {
            finish()
        }
    }
}
