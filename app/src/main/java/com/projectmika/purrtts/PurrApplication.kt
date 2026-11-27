package com.projectmika.purrtts

import android.app.Application
import com.projectmika.purrtts.core.purr.PurrProfileRepository
import com.projectmika.purrtts.core.purr.VramResidencyPolicy
import com.projectmika.purrtts.engine.ModelCatalogManager
import com.projectmika.purrtts.engine.TtsBackendRouter
import com.projectmika.purrtts.engine.audiobook.AudiobookConstructorManager
import com.projectmika.purrtts.service.PurrKeepWarmService
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * ✨ MIKA'S PURR-TTS APPLICATION CONTAINER ✨
 * Hosts singleton repository, dual-runtime backend router, model catalog manager,
 * and resumable audiobook constructor shared between the system-wide TextToSpeechService
 * and the Jetpack Compose Forge UI.
 */
class PurrApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: PurrProfileRepository
        private set

    lateinit var backendRouter: TtsBackendRouter
        private set

    lateinit var modelCatalogManager: ModelCatalogManager
        private set

    lateinit var audiobookManager: AudiobookConstructorManager
        private set

    override fun onCreate() {
        super.onCreate()
        runCatching { PDFBoxResourceLoader.init(applicationContext) }
        repository = PurrProfileRepository(this)
        backendRouter = TtsBackendRouter(this, repository)
        modelCatalogManager = ModelCatalogManager(this, repository)
        audiobookManager = AudiobookConstructorManager(this, repository, backendRouter)

        appScope.launch {
            repository.state
                .map { s ->
                    VramResidencyPolicy.shouldRunForegroundService(
                        config = s.vramResidency,
                        moduleSlotted = VramResidencyPolicy.TOOL_ID in s.slottedCartridges
                    )
                }
                .distinctUntilChanged()
                .collect { shouldRun ->
                    PurrKeepWarmService.syncWithState(this@PurrApplication, shouldRun)
                }
        }
    }
}
