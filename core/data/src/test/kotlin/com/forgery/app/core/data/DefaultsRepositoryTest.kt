package com.forgery.app.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.DefaultField
import com.forgery.app.core.model.GenDefaults
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultsRepositoryTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(): DefaultDefaultsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { tmp.newFile("prefs.preferences_pb") },
        )
        return DefaultDefaultsRepository(ForgeryPreferencesDataSource(dataStore))
    }

    @Test
    fun `empty by default`() = runTest {
        val repo = repo()
        assertEquals(GenDefaults(), repo.observeDefaults().first())
    }

    @Test
    fun `round-trip saves and observes all fields`() = runTest {
        val repo = repo()
        repo.saveDefault(DefaultField.PROMPT, "a cat")
        repo.saveDefault(DefaultField.NEGATIVE, "blurry")
        repo.saveDefault(DefaultField.MODEL, "model.safetensors")
        repo.saveDefault(DefaultField.SAMPLER, "Euler")
        repo.saveDefault(DefaultField.SCHEDULER, "Normal")
        repo.saveDefault(DefaultField.UPSCALER, "Latent")
        assertEquals(
            GenDefaults(
                prompt = "a cat",
                negativePrompt = "blurry",
                modelTitle = "model.safetensors",
                sampler = "Euler",
                scheduler = "Normal",
                upscaler = "Latent",
            ),
            repo.observeDefaults().first(),
        )
        // Overwrite of a single field keeps the rest.
        repo.saveDefault(DefaultField.PROMPT, "a dog")
        assertEquals("a dog", repo.observeDefaults().first().prompt)
        assertEquals("Euler", repo.observeDefaults().first().sampler)
    }
}
