package com.functy.fewards.ui.screen.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.functy.fewards.ui.viewmodel.AccountViewModel

@Composable
fun AccountPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val accountViewModel = viewModel<AccountViewModel>()

    /**
     * 切回本页时重读一次账号库。
     *
     * 三页是同一个 pager 里的三个常驻页面，切页**不会**重建 ViewModel、也不会触发
     * Activity 的 resume，所以设置页「从电脑同步凭据」刚写进库的账号，在这一页会一直是空列表。
     * 以 `isCurrentPage` 为 key 做一次轻量重读（账号列表 + 积分缓存命中直接复用），
     * 代价只有一次 SharedPreferences 读取。
     */
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) accountViewModel.refresh()
    }

    AccountPagerMiuix(
        accountViewModel = accountViewModel,
        bottomInnerPadding = bottomInnerPadding,
        onRefresh = accountViewModel::refresh,
    )
}
