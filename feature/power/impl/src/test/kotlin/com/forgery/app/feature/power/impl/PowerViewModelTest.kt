package com.forgery.app.feature.power.impl

import com.forgery.app.core.common.Result
import com.forgery.app.core.data.PowerRepository
import com.forgery.app.core.data.PowerService
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakePowerRepository(
    var failNext: String? = null,
) : PowerRepository {
    val calls = mutableListOf<String>()
    private fun result(label: String): Result<String> {
        calls += label
        return failNext?.let { Result.Error(it) } ?: Result.Success("$label ok")
    }
    override suspend fun wakeAll(): Result<String> = result("wake")
    override suspend fun powerOff(): Result<String> = result("off")
    override suspend fun setService(service: PowerService, on: Boolean): Result<String> =
        result("${service.name.lowercase()} $on")
}

class PowerViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private suspend fun StateFlow<PowerUiState>.success(): PowerUiState.Success =
        first { it is PowerUiState.Success } as PowerUiState.Success

    @Test
    fun `wake reports gateway response`() = runTest {
        val repo = FakePowerRepository()
        val vm = PowerViewModel(repo)
        vm.uiState.success()
        vm.onAction(PowerAction.Wake)
        val state = vm.uiState.first {
            it is PowerUiState.Success && it.message != null && !it.busy
        } as PowerUiState.Success
        assertEquals(listOf("wake"), repo.calls)
        assertEquals("wake ok", state.message)
    }

    @Test
    fun `service toggle and error mapping`() = runTest {
        val repo = FakePowerRepository(failNext = "conn refused")
        val vm = PowerViewModel(repo)
        vm.uiState.success()
        vm.onAction(PowerAction.SetService(PowerService.COMFY, on = false))
        val state = vm.uiState.first {
            it is PowerUiState.Success && it.message != null && !it.busy
        } as PowerUiState.Success
        assertEquals(listOf("comfy false"), repo.calls)
        assertTrue(state.isError)
        assertEquals("conn refused", state.message)
    }
}
