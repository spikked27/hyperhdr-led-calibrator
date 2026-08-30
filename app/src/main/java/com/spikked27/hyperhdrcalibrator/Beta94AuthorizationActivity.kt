package com.spikked27.hyperhdrcalibrator

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Beta 9.4 authorization gate.
 *
 * HyperHDR protects getconfig/setconfig with admin authorization. The password entered here is kept
 * only in process memory. When the user selects a HyperHDR instance, HyperHdrClient exchanges the
 * password for HyperHDR's user token and immediately discards the password. The token is likewise
 * memory-only and is used to authenticate each short-lived admin JSON session.
 */
class Beta94AuthorizationActivity : ComponentActivity() {
    private var password by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HyperHdrAdminCredentialStore.clear()
        setContent { Beta94Theme { AuthorizationScreen() } }
    }

    @Composable
    private fun AuthorizationScreen() {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("HyperHDR LED Calibrator • Beta 9.4", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Authorize HyperHDR", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Closed-loop validation must read and restore your current ICE calibration, and Commit must save it. HyperHDR requires admin authorization for those configuration operations."
                        )
                        Text(
                            "Your password is not saved. After you select the HyperHDR server, the app exchanges it for HyperHDR's user token and discards the password. The token remains only in memory until the app process ends."
                        )
                    }
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HyperHDR admin password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("HyperHDR requires at least 8 characters.") },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = password.length >= 8,
                    onClick = {
                        runCatching {
                            HyperHdrAdminCredentialStore.setPassword(password)
                            password = ""
                            startActivity(Intent(this@Beta94AuthorizationActivity, Beta93CalibrationActivity::class.java))
                        }.onFailure { error = it.message ?: "Could not prepare HyperHDR authorization" }
                    },
                ) { Text("Continue to calibrator") }
            }
        }
    }
}

@Composable
private fun Beta94Theme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
