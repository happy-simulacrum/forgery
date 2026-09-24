package com.forgery.app.feature.settings.impl

import com.forgery.app.core.data.ConnectionRepository
import androidx.compose.ui.text.input.TextFieldValue
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.UiPrefs
import com.forgery.app.core.network.ForgeApiFactory
import com.forgery.app.core.network.ForgeHeadersInterceptor
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeConnectionRepository : ConnectionRepository {
    private val config = MutableStateFlow(ConnectionConfig())
    private val ui = MutableStateFlow(UiPrefs())
    var savedConfigs = mutableListOf<ConnectionConfig>()
    var resetCalls = 0

    override fun observe(): Flow<ConnectionConfig> = config.asStateFlow()
    override suspend fun save(c: ConnectionConfig) {
        savedConfigs += c
        config.value = c
    }
    override fun observeUiPrefs(): Flow<UiPrefs> = ui.asStateFlow()
    override suspend fun saveUiPrefs(prefs: UiPrefs) {
        ui.value = prefs
    }
    override suspend fun reset() {
        resetCalls++
        config.value = ConnectionConfig()
    }
}

class SettingsViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val repository = FakeConnectionRepository()

    private fun viewModel() = SettingsViewModel(
        connectionRepository = repository,
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
}
