package com.gemmory.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.gemmory.ui.theme.GemmaBackdrop
import com.gemmory.ui.theme.GemmoryTheme
import com.gemmory.vault.presentation.VaultGraphScreen
import com.gemmory.vault.presentation.VaultGraphViewModel

class VaultGraphActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val viewModel = ViewModelProvider(
            this,
            VaultGraphViewModel.factory(application.container.vaultRepository),
        )[VaultGraphViewModel::class.java]

        setContent {
            GemmoryTheme {
                GemmaBackdrop {
                    VaultGraphScreen(viewModel = viewModel, onClose = ::finish)
                }
            }
        }
    }
}
