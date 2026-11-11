package com.adshield.app.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.adshield.app.a11y.A11yState
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
                    onOpenA11ySettings = ::openA11ySettings,
                    onDismissError = viewModel::dismissError,
                    onClearMessage = viewModel::clearMessage,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置里开关无障碍服务，回到前台时以系统状态为准
        A11yState.refresh(this)
    }

    private fun openA11ySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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