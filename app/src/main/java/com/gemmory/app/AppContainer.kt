package com.gemmory.app

import android.app.Application
import android.content.Context
import com.gemmory.chat.data.ChatDatabase
import com.gemmory.chat.domain.ChatRepository
import com.gemmory.chat.domain.ContextPolicy
import com.gemmory.chat.domain.RoomChatRepository
import com.gemmory.core.dispatchers.AppDispatchers
import com.gemmory.core.dispatchers.DefaultAppDispatchers
import com.gemmory.core.filesystem.RealFileSystem
import com.gemmory.inference.EngineController
import com.gemmory.inference.InferenceConfig
import com.gemmory.inference.LiteRtLlmEngine
import com.gemmory.inference.LocalLlmEngine
import com.gemmory.modelinstall.AndroidNetworkStatusProvider
import com.gemmory.modelinstall.ContentResolverModelFileImporter
import com.gemmory.modelinstall.DefaultModelInstaller
import com.gemmory.modelinstall.ModelCatalog
import com.gemmory.modelinstall.ModelDescriptor
import com.gemmory.modelinstall.ModelIntegrityVerifier
import com.gemmory.modelinstall.ModelInstaller
import com.gemmory.modelinstall.ModelStorage
import com.gemmory.modelinstall.OkHttpModelDownloader
import com.gemmory.privacy.NetworkAccessAuditor
import com.gemmory.settings.DataStoreSettingsRepository
import com.gemmory.settings.SettingsRepository
import com.gemmory.vault.data.KnowledgeDatabase
import com.gemmory.vault.data.RoomVaultRepository
import com.gemmory.vault.domain.VaultRepository
import com.gemmory.vault.storage.MarkdownVaultStorage
import com.gemmory.vaultagent.LocalLlmVaultAnswerGenerator
import com.gemmory.vaultagent.LocalLlmVaultNoteProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency container.
 *
 * A DI framework would add build complexity without buying anything for a graph
 * this small, and everything here is a singleton bound to the process lifetime.
 */
class AppContainer(private val application: Application) {

    val dispatchers: AppDispatchers = DefaultAppDispatchers()

    /** Outlives every ViewModel: model loading must survive configuration changes. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository(application) }

    val descriptor: ModelDescriptor = ModelCatalog.default

    private val fileSystem = RealFileSystem()

    val modelStorage: ModelStorage by lazy { ModelStorage(application.filesDir, fileSystem) }

    val modelInstaller: ModelInstaller by lazy {
        DefaultModelInstaller(
            descriptor = descriptor,
            downloadUrlProvider = { settingsRepository.settings.first().modelDownloadUrl },
            storage = modelStorage,
            downloader = OkHttpModelDownloader(
                NetworkAccessAuditor.wrap(OkHttpModelDownloader.defaultClient()),
            ),
            importer = ContentResolverModelFileImporter(application.contentResolver),
            verifier = ModelIntegrityVerifier(),
            network = AndroidNetworkStatusProvider(application),
            scope = appScope,
            dispatchers = dispatchers,
        )
    }

    private val engine: LocalLlmEngine by lazy {
        LiteRtLlmEngine(
            context = application,
            configProvider = {
                InferenceConfig(
                    backendPreference = settingsRepository.settings.first().backendPreference,
                )
            },
            dispatchers = dispatchers,
        )
    }

    val engineController: EngineController by lazy { EngineController(engine, appScope) }

    private val database: ChatDatabase by lazy { ChatDatabase.create(application) }

    val chatRepository: ChatRepository by lazy {
        RoomChatRepository(database.conversationDao(), database.messageDao(), dispatchers)
    }

    private val knowledgeDatabase: KnowledgeDatabase by lazy { KnowledgeDatabase.create(application) }

    private val vaultStorage: MarkdownVaultStorage by lazy {
        MarkdownVaultStorage(application.filesDir.resolve("vault"))
    }

    val vaultRepository: VaultRepository by lazy {
        RoomVaultRepository(
            database = knowledgeDatabase,
            dao = knowledgeDatabase.knowledgeDao(),
            storage = vaultStorage,
            dispatchers = dispatchers,
            answerGenerator = LocalLlmVaultAnswerGenerator(engineController),
            noteProcessor = LocalLlmVaultNoteProcessor(engineController),
        )
    }

    val contextPolicy: ContextPolicy by lazy {
        ContextPolicy(InferenceConfig.DEFAULT_CONTEXT_BUDGET_TOKENS)
    }

    /** Startup housekeeping that must not block the main thread. */
    fun warmUp() {
        appScope.launch {
            modelStorage.prepareDirectories()
            vaultStorage.prepare()
            modelStorage.cleanupOrphanTempFiles(descriptor)
            chatRepository.repairUnfinishedMessages()
        }
    }
}

val Context.container: AppContainer
    get() = (applicationContext as GemmoryApp).container
