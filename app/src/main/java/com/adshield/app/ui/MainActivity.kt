package com.adshield.app.ui

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.adshield.app.ui.theme.AdShieldTheme
import com.adshield.app.vpn.AdShieldVpnService

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels { HomeViewModelFactory(application) }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            AdShieldVpnService.start(this)
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AdShieldTheme {
                val state by viewModel.state.collectAsState()
                HomeScreen(
                    state = state,
                    onToggleVpn = ::toggleVpn,
                    onAddDomain = viewModel::addCustomDomain,
                    onRemoveDomain = viewModel::removeCustomDomain,
                    onToggleBlockDoh = viewModel::toggleBlockDoh,
                    onAddAllowDomain = viewModel::addAllowDomain,
                    onRemoveAllowDomain = viewModel::removeAllowDomain,
                    onDismissError = viewModel::dismissError,
                    onClearMessage = viewModel::clearMessage,
                )
            }
        }
    }

    private fun toggleVpn(enable: Boolean) {
        if (!enable) {
            AdShieldVpnService.stop(this)
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            AdShieldVpnService.start(this)
        }
    }
}