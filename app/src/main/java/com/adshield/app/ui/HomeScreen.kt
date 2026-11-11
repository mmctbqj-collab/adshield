package com.adshield.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adshield.app.data.db.BlockEventEntity
import com.adshield.app.data.db.DomainAgg
import com.adshield.app.data.db.PackageAgg
import com.adshield.app.data.db.PopupEventEntity
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    state: HomeUiState,
    onToggleVpn: (Boolean) -> Unit,
    onAddDomain: (String) -> Unit,
    onRemoveDomain: (String) -> Unit,
    onToggleBlockDoh: (Boolean) -> Unit,
    onAddAllowDomain: (String) -> Unit,
    onRemoveAllowDomain: (String) -> Unit,
    onOpenA11ySettings: () -> Unit,
    onDismissError: () -> Unit,
    onClearMessage: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var allowInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(2200)
            onClearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "AdShield",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "本地 DNS 过滤 · 仅作用于本机，不经过任何服务器",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(state = state, onToggle = onToggleVpn, onOpenA11ySettings = onOpenA11ySettings)

        state.error?.let { error ->
            NoticeCard(
                title = "提示",
                body = error,
                actionLabel = "知道了",
                onAction = onDismissError,
                emphasize = true,
            )
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        StatsCard(state = state)
        DomainRankCard(state = state)
        PackageRankCard(state = state)
        RecentPopupCard(state = state)
        RecentCard(state = state)
        DohCard(state = state, onToggle = onToggleBlockDoh)
        CustomRuleCard(
            state = state,
            input = input,
            onInputChange = { input = it },
            onSubmit = {
                val value = input
                input = ""
                onAddDomain(value)
            },
            onRemove = onRemoveDomain,
        )

        AllowCard(
            state = state,
            input = allowInput,
            onInputChange = { allowInput = it },
            onSubmit = {
                val value = allowInput
                allowInput = ""
                onAddAllowDomain(value)
            },
            onRemove = onRemoveAllowDomain,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = "能力边界：私人 DNS（DoT）与 App 内置 DoH 可能绕过过滤；" +
                "广告与业务同域时无法拦截；已缓存的 DNS 需重启目标 App 才会失效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "版本 0.1.0 · M1（请求拦截 + 统计）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun StatusCard(
    state: HomeUiState,
    onToggle: (Boolean) -> Unit,
    onOpenA11ySettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.vpnRunning) "请求拦截已开启" else "请求拦截未开启",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (state.vpnRunning) {
                            "所有 App 的 DNS 查询都会经过本机规则过滤"
                        } else {
                            "开启需授予 VPN 权限；数据不出本机，不转发到服务器"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(checked = state.vpnRunning, onCheckedChange = onToggle)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.a11yEnabled) "界面跳过已开启" else "界面跳过未开启",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "自动点击开屏/弹窗里的“跳过、关闭广告”。仅在 App 前台 8 秒内动作，" +
                            "且永不点击支付、开通、同意、下载类按钮。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (state.a11yEnabled) {
                    Text(
                        text = "已开启",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(onClick = onOpenA11ySettings) { Text("去开启") }
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    emphasize: Boolean = false,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (emphasize) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = body, style = MaterialTheme.typography.bodySmall)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun StatsCard(state: HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "今日",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.todayTotal.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "次广告域名请求被拦截",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "自动跳过 ${state.todaySkipped} 次界面广告",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "规则 ${state.ruleCount} 条 · 自定义 ${state.customCount} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val unattributed = state.snapshot?.unattributed ?: 0L
            if (unattributed > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "其中 $unattributed 次未能归因到具体 App（系统限制）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DomainRankCard(state: HomeUiState) {
    val domains = state.snapshot?.topDomains.orEmpty()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("拦截最多的域名")
            if (domains.isEmpty()) {
                EmptyHint("还没有记录。开启拦截后，被拦下的域名会出现在这里。")
            } else {
                domains.forEach { item -> DomainRow(item) }
            }
        }
    }
}

@Composable
private fun DomainRow(item: DomainAgg) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.domain,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            text = "${item.total} 次",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PackageRankCard(state: HomeUiState) {
    val packages = state.snapshot?.topPackages.orEmpty()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("拦截最多的 App")
            if (packages.isEmpty()) {
                EmptyHint("暂无可归因的 App 记录。部分设备不支持按 App 归因。")
            } else {
                packages.forEach { item -> PackageRow(item, state.appLabels) }
            }
        }
    }
}

@Composable
private fun PackageRow(item: PackageAgg, labels: Map<String, String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = labels[item.pkg] ?: item.pkg,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            text = "${item.total} 次",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentPopupCard(state: HomeUiState) {
    val items = state.popupSnapshot?.recent.orEmpty().take(10)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("最近跳过")
            if (items.isEmpty()) {
                EmptyHint("暂无记录。开启“界面跳过”后，被自动关掉的广告会出现在这里。")
            } else {
                items.forEach { item -> PopupRow(item, state.appLabels) }
            }
        }
    }
}

@Composable
private fun PopupRow(item: PopupEventEntity, labels: Map<String, String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = labels[item.pkg] ?: item.pkg,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                text = buildString {
                    append(formatTime(item.ts))
                    append(" · ")
                    append(outcomeLabel(item.outcome))
                    item.nodeText?.let {
                        append(" · ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun outcomeLabel(outcome: String): String = when (outcome) {
    "SKIPPED" -> "已跳过"
    "FAILED" -> "已点击未生效"
    "SAFETY_BLOCKED" -> "安全阀拦下"
    else -> outcome
}

@Composable
private fun RecentCard(state: HomeUiState) {
    val recent = state.snapshot?.recent.orEmpty().take(15)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("最近拦截")
            if (recent.isEmpty()) {
                EmptyHint("暂无记录。")
            } else {
                recent.forEach { item -> RecentRow(item, state.appLabels) }
            }
        }
    }
}

@Composable
private fun RecentRow(item: BlockEventEntity, labels: Map<String, String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.domain,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                text = buildString {
                    append(formatTime(item.ts))
                    append(" · ")
                    append(labels[item.pkg] ?: if (item.pkg.isEmpty()) "未归因" else item.pkg)
                    if (item.hitCount > 1) {
                        append(" · 累计 ")
                        append(item.hitCount)
                        append(" 次")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DohCard(state: HomeUiState, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "阻断加密 DNS（DoH）",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "拦截 dns.google、doh.pub 等 DoH 域名，强制浏览器和 App 回退到系统 DNS，" +
                        "否则它们的广告会绕过本地过滤。开启后个别 App 可能短暂无法联网。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.blockDoh) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "已开启：内置 DoH 域名已加入拦截规则",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = state.blockDoh, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun AllowCard(
    state: HomeUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("白名单（放行）")
            Text(
                text = "某个 App 因域名被误拦而功能异常时，在这里放行对应域名；优先于所有黑名单规则。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("放行域名") },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onSubmit) { Text("放行") }
            }
            if (state.allowDomains.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                state.allowDomains.forEach { domain ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = domain,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRemove(domain) }) { Text("取消放行") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomRuleCard(
    state: HomeUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("自定义屏蔽域名")
            Text(
                text = "添加后对其所有子域生效。例如 ads.example.com",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("域名") },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onSubmit) { Text("添加") }
            }
            if (state.customDomains.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                state.customDomains.forEach { domain ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = domain,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRemove(domain) }) { Text("移除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(TIME_FORMAT)