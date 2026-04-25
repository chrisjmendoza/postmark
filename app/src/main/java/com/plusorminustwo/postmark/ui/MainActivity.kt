package com.plusorminustwo.postmark.ui

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.plusorminustwo.postmark.ui.navigation.AppNavigation
import com.plusorminustwo.postmark.ui.theme.PostmarkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* role result handled; app rerenders accordingly */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestDefaultSmsRoleIfNeeded()
        setContent {
            PostmarkTheme {
                AppNavigation()
            }
        }
    }

    private fun requestDefaultSmsRoleIfNeeded() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (!roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
            roleRequestLauncher.launch(intent)
        }
    }
}
