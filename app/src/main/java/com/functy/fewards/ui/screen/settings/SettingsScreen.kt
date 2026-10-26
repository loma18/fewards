package com.functy.fewards.ui.screen.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.functy.fewards.ui.navigation.Navigator
import com.functy.fewards.ui.navigation.Route
import com.functy.fewards.ui.viewmodel.SettingsViewModel

@Composable
fun SettingPager(
    navigator: Navigator,
    bottomInnerPadding: Dp
) {
    val activity = LocalActivity.current as? ComponentActivity
    val viewModel = if (activity != null) {
        viewModel<SettingsViewModel>(viewModelStoreOwner = activity)
    } else {
        viewModel<SettingsViewModel>()
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val actions = SettingsScreenActions(
        onOpenTheme = { navigator.push(Route.ColorPalette) },
        onSetWbMaster = viewModel::setWbMasterEnabled,
        onSetWbGrowth = viewModel::setWbGrowthEnabled,
        onClaimGrowth = viewModel::claimGrowth,
        onSetWbAutoRefresh = viewModel::setWbAutoRefresh,
        onCheckRefreshCredentials = viewModel::checkAndRefreshCredentials,
        onSetWbSyncEndpoint = viewModel::setWbSyncEndpoint,
        onSetWbSyncPairCode = viewModel::setWbSyncPairCode,
        onSetWbSyncForcePush = viewModel::setWbSyncForcePush,
        onToggleWbSyncAccount = viewModel::toggleWbSyncAccount,
        onSelectAllWbSync = viewModel::selectAllWbSync,
        onSyncWbAccounts = viewModel::syncSelectedAccounts,
        onRefreshWbAccounts = viewModel::refreshSelectedAccounts,
        onPullWbCredentials = viewModel::pullCredentialsFromComputer,
        onSetTaskNotification = viewModel::setTaskNotification,
        onSetOverviewAutoDismiss = viewModel::setOverviewAutoDismiss,
        onSetOverviewHoldSeconds = viewModel::setOverviewHoldSeconds,
    )

    SettingPagerMiuix(uiState, actions, bottomInnerPadding)
}
