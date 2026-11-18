package com.projectmika.purrtts

import android.app.Application
import com.projectmika.purrtts.core.purr.PurrProfileRepository
import com.projectmika.purrtts.engine.ModelCatalogManager
import com.projectmika.purrtts.engine.TtsBackendRouter

/**
 * ✨ MIKA'S PURR-TTS APPLICATION CONTAINER ✨
 * Hosts singleton repository, dual-runtime backend router, and model catalog manager
 * shared between the system-wide TextToSpeechService and the Jetpack Compose Forge UI.
 */
class PurrApplication : Application() {

    lateinit var repository: PurrProfileRepository
        private set

    lateinit var backendRouter: TtsBackendRouter
        private set

    lateinit var modelCatalogManager: ModelCatalogManager
        private set

    override fun onCreate() {
        super.onCreate()
        repository = PurrProfileRepository(this)
        backendRouter = TtsBackendRouter(this, repository)
        modelCatalogManager = ModelCatalogManager(this, repository)
    }
}
