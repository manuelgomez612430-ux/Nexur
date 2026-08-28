package com.naxor.app

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        
        // Bloquear escala de fuente al 100% (Ignora configuración del sistema)
        config.fontScale = 1.0f
        
        // Si quisiéramos bloquear también la densidad (tamaño de elementos),
        // pero dp usualmente maneja esto. Lo más común es fontScale.
        
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

    // Asegurar que si la configuración cambia dinámicamente, se mantenga el bloqueo
    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        if (overrideConfiguration != null) {
            overrideConfiguration.fontScale = 1.0f
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }
}
