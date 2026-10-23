package com.gomezmart.app

import android.content.Intent
import android.os.Bundle

class EditProfileActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, AccountSettingsActivity::class.java))
        finish()
    }
}
