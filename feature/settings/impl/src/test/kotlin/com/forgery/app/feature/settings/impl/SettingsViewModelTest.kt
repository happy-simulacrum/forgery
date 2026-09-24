package com.forgery.app.feature.settings.impl

import com.forgery.app.core.data.ConnectionRepository
import androidx.compose.ui.text.input.TextFieldValue
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.UiPrefs
import com.forgery.app.core.network.ForgeApiFactory
import com.forgery.app.core.network.ForgeHeadersInterceptor
import com.forgery.app.core.network.ForgeService
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeConnectionRepository : ConnectionRepository {
    private val config = MutableStateFlow(ConnectionConfig())
    private val ui = MutableStateFlow(UiPrefs())
    var savedConfigs = mutableListOf<ConnectionConfig>()
    var savedUiPrefs = mutableListOf<UiPrefs>()
    var resetCalls = 0

    override fun observe(): Flow<ConnectionConfig> = config.asStateFlow()
    override suspend fun save(c: ConnectionConfig) {
        savedConfigs += c
        config.value = c
    }
    override fun observeUiPrefs(): Flow<UiPrefs> = ui.asStateFlow()
    override suspend fun saveUiPrefs(prefs: UiPrefs) {
        savedUiPrefs += prefs
        ui.value = prefs
    }
    override suspend fun reset() {
        resetCalls++
        config.value = ConnectionConfig()
    }

    fun currentUi(): UiPrefs = ui.value
}

private data class ControlCall(
    val baseUrl: String,
    val cfClientId: String,
    val cfClientSecret: String,
)

/** Minimal ForgeService fake: only sdModels is wired, the rest are inert. */
private class FakeCheckService(
    var models: List<JsonObject> = List(2) { i ->
        buildJsonObject { put("model_name", "m$i.safetensors") }
    },
    var failure: Throwable? = null,
    var gate: CompletableDeferred<Unit>? = null,
) : ForgeService {
    var calls = 0

    override suspend fun sdModels(): List<JsonObject> {
        calls++
        gate?.await()
        failure?.let { throw it }
        return models
    }

    override suspend fun sdModules(): List<JsonObject> = emptyList()
    override suspend fun samplers(): List<JsonObject> = emptyList()
    override suspend fun upscalers(): List<JsonObject> = emptyList()
    override suspend fun schedulers(): List<JsonObject> = emptyList()
    override suspend fun loras(): List<JsonObject> = emptyList()
    override suspend fun options(): JsonObject = buildJsonObject {}
    override suspend fun setOptions(body: JsonObject): ResponseBody =
        "{}".toResponseBody("application/json".toMediaType())
    override suspend fun progress(): JsonObject = buildJsonObject {}
    override suspend fun txt2img(body: JsonObject): JsonObject = buildJsonObject {}
    override suspend fun img2img(body: JsonObject): JsonObject = buildJsonObject {}
    override suspend fun unloadCheckpoint(body: Map<String, String>): JsonObject =
        buildJsonObject {}
    override suspend fun interrupt(): ResponseBody =
        "{}".toResponseBody("application/json".toMediaType())
    override suspend fun promptStyles(): List<JsonObject> = emptyList()
    override suspend fun file(url: String): JsonObject = buildJsonObject {}
}

class SettingsViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val repository = FakeConnectionRepository()

    private fun viewModel(
        repo: FakeConnectionRepository = repository,
    ) = SettingsViewModel(
        connectionRepository = repo,
        forgeApiFactory = ForgeApiFactory(ForgeHeadersInterceptor()),
    )

    @Test
    fun `uiState reflects stored config`() = runTest {
        val vm = viewModel()
        val state = vm.uiState.first { it is SettingsUiState.Success } as SettingsUiState.Success
        assertEquals("192.168.1.100", state.draft.baseIp)
        assertFalse(state.isDirty)
    }

    @Test
    fun `editing marks dirty and save persists`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is SettingsUiState.Success }

        vm.onAction(SettingsAction.ConfigChanged(ConnectionConfig(baseIp = "10.0.0.5")))

        val dirty = vm.uiState.first {
            it is SettingsUiState.Success && it.isDirty
        } as SettingsUiState.Success
        assertEquals("10.0.0.5", dirty.draft.baseIp)

        vm.onAction(SettingsAction.Save)

        val clean = vm.uiState.first {
            it is SettingsUiState.Success && !it.isDirty && it.draft.baseIp == "10.0.0.5"
        } as SettingsUiState.Success
        assertEquals(1, repository.savedConfigs.size)
        assertEquals("10.0.0.5", repository.savedConfigs.first().baseIp)
        assertTrue(clean.draft.isConfigured)
    }

    @Test
    fun `reset clears draft`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is SettingsUiState.Success }

        vm.onAction(SettingsAction.ConfigChanged(ConnectionConfig(baseIp = "10.0.0.5")))
        vm.uiState.first { it is SettingsUiState.Success && it.isDirty }
        vm.onAction(SettingsAction.Reset)

        val state = vm.uiState.first {
            it is SettingsUiState.Success && !it.isDirty
        } as SettingsUiState.Success
        assertEquals(1, repository.resetCalls)
        assertEquals("192.168.1.100", state.draft.baseIp)
    }

    @Test
    fun `raw keystrokes commit on focus loss, invalid port keeps domain`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is SettingsUiState.Success }

        vm.onAction(SettingsAction.BaseIpChanged(TextFieldValue("10.0.0.9")))
        vm.onAction(SettingsAction.PortWebUiChanged(TextFieldValue("4321")))

        // Raw visible immediately, domain untouched until commit.
        val typing = vm.uiState.first {
            it is SettingsUiState.Success && it.texts.baseIp.text == "10.0.0.9"
        } as SettingsUiState.Success
        assertEquals("192.168.1.100", typing.draft.baseIp)

        vm.onAction(SettingsAction.CommitInputs)

        val committed = vm.uiState.first {
            it is SettingsUiState.Success && it.draft.baseIp == "10.0.0.9"
        } as SettingsUiState.Success
        assertEquals(4321, committed.draft.portWebUi)

        // Invalid raw keeps the previous domain value.
        vm.onAction(SettingsAction.PortWebUiChanged(TextFieldValue("abc")))
        vm.onAction(SettingsAction.CommitInputs)
        val kept = vm.uiState.first {
            it is SettingsUiState.Success && it.texts.portWebUi.text == "abc"
        } as SettingsUiState.Success
        assertEquals(4321, kept.draft.portWebUi)
    }

    @Test
    fun `save parses raw text`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is SettingsUiState.Success }

        vm.onAction(SettingsAction.PortWebUiChanged(TextFieldValue("9999")))
        vm.uiState.first {
            it is SettingsUiState.Success && it.texts.portWebUi.text == "9999"
        }
        vm.onAction(SettingsAction.Save)

        val clean = vm.uiState.first {
            it is SettingsUiState.Success && !it.isDirty && it.draft.portWebUi == 9999
        } as SettingsUiState.Success
        assertEquals(9999, repository.savedConfigs.last().portWebUi)
        assertTrue(clean.draft.isConfigured)
    }

    @Test
    fun `UiPrefsChanged persists immediately without dirty SAVE`() = runTest {
        val repo = FakeConnectionRepository()
        val vm = viewModel(repo)
        vm.uiState.first { it is SettingsUiState.Success }

        vm.onAction(SettingsAction.UiPrefsChanged(UiPrefs(darkTheme = false)))

        assertEquals(1, repo.savedUiPrefs.size)
        assertEquals(false, repo.savedUiPrefs.first().darkTheme)
        assertEquals(false, repo.currentUi().darkTheme)

        val state = vm.uiState.first {
            it is SettingsUiState.Success && !it.draftUi.darkTheme
        } as SettingsUiState.Success
        assertFalse(state.draftUi.darkTheme)
        assertFalse(state.isDirty)
    }

    @Test
    fun `CHECK uses createControl with creds and maps Ok`() = runTest {
        val repo = FakeConnectionRepository()
        val vm = viewModel(repo)
        vm.uiState.first { it is SettingsUiState.Success }

        val calls = mutableListOf<ControlCall>()
        val service = FakeCheckService(
            models = List(3) { i -> buildJsonObject { put("model_name", "m$i") } },
        )
        vm.checkServiceProvider = { url, id, secret ->
            calls += ControlCall(url, id, secret)
            service
        }

        vm.onAction(
            SettingsAction.ConfigChanged(
                ConnectionConfig(
                    baseIp = "10.0.0.5",
                    portWebUi = 7860,
                    cfClientId = "id1",
                    cfClientSecret = "s1",
                ),
            ),
        )
        vm.onAction(SettingsAction.CheckConnection)

        val state = vm.uiState.first {
            it is SettingsUiState.Success && it.check is CheckState.Ok
        } as SettingsUiState.Success
        assertEquals(3, (state.check as CheckState.Ok).models)
        assertEquals(1, calls.size)
        assertEquals("http://10.0.0.5:7860", calls.first().baseUrl)
        assertEquals("id1", calls.first().cfClientId)
        assertEquals("s1", calls.first().cfClientSecret)
        assertEquals(1, service.calls)
    }

    @Test
    fun `CHECK maps failure`() = runTest {
        val repo = FakeConnectionRepository()
        val vm = viewModel(repo)
        vm.uiState.first { it is SettingsUiState.Success }

        val service = FakeCheckService(failure = RuntimeException("boom"))
        vm.checkServiceProvider = { _, _, _ -> service }

        vm.onAction(SettingsAction.CheckConnection)

        val state = vm.uiState.first {
            it is SettingsUiState.Success && it.check is CheckState.Failed
        } as SettingsUiState.Success
        assertEquals("boom", (state.check as CheckState.Failed).message)
    }

    @Test
    fun `repeated CHECK cancels previous`() = runTest {
        val repo = FakeConnectionRepository()
        val vm = viewModel(repo)
        vm.uiState.first { it is SettingsUiState.Success }

        val gate = CompletableDeferred<Unit>()
        var providerCalls = 0
        vm.checkServiceProvider = { _, _, _ ->
            providerCalls++
            val callIndex = providerCalls
            object : ForgeService by FakeCheckService() {
                override suspend fun sdModels(): List<JsonObject> {
                    if (callIndex == 1) gate.await()
                    return List(2) { i -> buildJsonObject { put("model_name", "m$i") } }
                }
            }
        }

        vm.onAction(SettingsAction.CheckConnection)
        vm.uiState.first {
            it is SettingsUiState.Success && it.check is CheckState.Checking
        }

        vm.onAction(SettingsAction.CheckConnection)
        val final = vm.uiState.first {
            it is SettingsUiState.Success && it.check is CheckState.Ok
        } as SettingsUiState.Success
        assertEquals(2, (final.check as CheckState.Ok).models)
        assertEquals(2, providerCalls)
        assertFalse(gate.isCompleted)
    }

    @Test
    fun `dismiss cancels check without Failed`() = runTest {
        val repo = FakeConnectionRepository()
        val vm = viewModel(repo)
        vm.uiState.first { it is SettingsUiState.Success }

        val gate = CompletableDeferred<Unit>()
        val service = FakeCheckService(gate = gate)
        vm.checkServiceProvider = { _, _, _ -> service }

        vm.onAction(SettingsAction.CheckConnection)
        vm.uiState.first {
            it is SettingsUiState.Success && it.check is CheckState.Checking
        }

        vm.onAction(SettingsAction.DismissCheck)
        val idle = vm.uiState.first {
            it is SettingsUiState.Success && it.check is CheckState.Idle
        } as SettingsUiState.Success
        assertTrue(idle.check is CheckState.Idle)
        assertFalse(gate.isCompleted)
        // Cancelled job must not surface as Failed: still Idle.
        assertTrue((vm.uiState.value as SettingsUiState.Success).check is CheckState.Idle)
    }
}
