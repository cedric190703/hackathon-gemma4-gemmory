package com.gemmory.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.gemmory.chat.presentation.ChatViewModel
import com.gemmory.ui.theme.GemmoryTheme
import com.gemmory.vault.presentation.KnowledgeViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = application.container
        val viewModel = ViewModelProvider(
            this,
            ChatViewModel.factory(
                repository = container.chatRepository,
                installer = container.modelInstaller,
                engineController = container.engineController,
                settingsRepository = container.settingsRepository,
                contextPolicy = container.contextPolicy,
            ),
        )[ChatViewModel::class.java]
        val knowledgeViewModel = ViewModelProvider(
            this,
            KnowledgeViewModel.factory(container.vaultRepository),
        )[KnowledgeViewModel::class.java]

        setContent {
            GemmoryTheme {
                GemmoryNavHost(viewModel = viewModel, knowledgeViewModel = knowledgeViewModel)
            }
        }
    }
}
