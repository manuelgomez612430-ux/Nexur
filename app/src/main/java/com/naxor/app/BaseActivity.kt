package com.naxor.app

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context?) {
        if (newBase == null) {
            super.attachBaseContext(newBase)
            return
        }
        
        try {
            val res = newBase.resources
            if (res != null) {
                val config = Configuration(res.configuration)
                config.fontScale = 1.0f
                val context = newBase.createConfigurationContext(config)
                super.attachBaseContext(context)
            } else {
                super.attachBaseContext(newBase)
            }
        } catch (e: Exception) {
            super.attachBaseContext(newBase)
        }
    }

    // Asegurar que si la configuración cambia dinámicamente, se mantenga el bloqueo
    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        if (overrideConfiguration != null) {
            overrideConfiguration.fontScale = 1.0f
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }
}
