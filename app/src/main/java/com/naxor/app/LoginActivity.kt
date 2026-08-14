package com.naxor.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.naxor.app.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var isLoginMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Verificar si ya hay una sesión activa
        if (auth.currentUser != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnToggleLoginMode.setOnClickListener {
            isLoginMode = !isLoginMode
            updateUI()
        }

        binding.btnContinueGuest.setOnClickListener {
            // Navegar a la pantalla principal sin iniciar sesión
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        binding.btnLoginAction.setOnClickListener {
            val email = binding.etLoginEmail.text.toString().trim()
            val password = binding.etLoginPassword.text.toString().trim()

            if (email.isEmpty() || password.length < 6) {
                Toast.makeText(this, "Ingresa un correo válido y contraseña de 6+ dígitos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isLoginMode) login(email, password)
            else register(email, password)
        }
    }

    private fun updateUI() {
        if (isLoginMode) {
            binding.tvLoginTitle.text = "Bienvenido"
            binding.btnLoginAction.text = "Iniciar Sesión"
            binding.btnToggleLoginMode.text = "¿No tienes cuenta? Regístrate aquí"
        } else {
            binding.tvLoginTitle.text = "Crear Cuenta"
            binding.btnLoginAction.text = "Registrarme"
            binding.btnToggleLoginMode.text = "¿Ya tienes cuenta? Inicia sesión"
        }
    }

    private fun login(e: String, p: String) {
        val loading = AlertDialog.Builder(this)
            .setMessage("Sincronizando con la nube...")
            .setCancelable(false)
            .show()

        auth.signInWithEmailAndPassword(e, p).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                // Descargar datos antes de entrar
                SyncManager(this).downloadEverythingFromCloud {
                    loading.dismiss()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            } else {
                loading.dismiss()
                Toast.makeText(this, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun register(e: String, p: String) {
        val loading = AlertDialog.Builder(this)
            .setMessage("Creando cuenta...")
            .setCancelable(false)
            .show()

        auth.createUserWithEmailAndPassword(e, p).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                // Subir lo que haya local a la nueva cuenta
                SyncManager(this).uploadAllLocalToCloud {
                    loading.dismiss()
                    Toast.makeText(this, "¡Cuenta creada y sincronizada!", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            } else {
                loading.dismiss()
                Toast.makeText(this, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
