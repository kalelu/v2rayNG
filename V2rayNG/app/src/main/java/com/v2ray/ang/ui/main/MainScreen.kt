package com.v2ray.ang.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.lite.LiteEnergyPoint
import com.v2ray.ang.lite.LiteEnergySummary
import com.v2ray.ang.ui.compose.LocalDarkTheme
import java.util.Locale
import kotlin.math.max

private enum class LiteMainPage {
    Dashboard,
    Nodes,
}

private data class LitePalette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceMuted: Color,
    val text: Color,
    val textMuted: Color,
    val border: Color,
    val accent: Color,
    val accentSoft: Color,
    val success: Color,
    val danger: Color,
)

@Composable
private fun litePalette(): LitePalette {
    val dark = LocalDarkTheme.current
    return remember(dark) {
        if (dark) {
            LitePalette(
                background = Color(0xFF071019),
                surface = Color(0xFF0E1823),
                surfaceRaised = Color(0xFF142130),
                surfaceMuted = Color(0xFF192838),
                text = Color(0xFFF5F7FA),
                textMuted = Color(0xFF8E9AAA),
                border = Color(0xFF243446),
                accent = Color(0xFF5F88FF),
                accentSoft = Color(0xFF1B315E),
                success = Color(0xFF2DD47B),
                danger = Color(0xFFFF6B72),
            )
        } else {
            LitePalette(
                background = Color(0xFFF4F7FB),
                surface = Color(0xFFFFFFFF),
                surfaceRaised = Color(0xFFFFFFFF),
                surfaceMuted = Color(0xFFF0F4F9),
                text = Color(0xFF111A26),
                textMuted = Color(0xFF697689),
                border = Color(0xFFE3E9F1),
                accent = Color(0xFF315FEF),
                accentSoft = Color(0xFFE6ECFF),
                success = Color(0xFF13A963),
                danger = Color(0xFFD1343C),
            )
        }
    }
}

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val palette = litePalette()
    var page by rememberSaveable { mutableStateOf(LiteMainPage.Dashboard) }

    Scaffold(
        containerColor = palette.background,
        bottomBar = {
            LiteBottomNavigation(
                selectedPage = page,
                palette = palette,
                onSelect = { page = it },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .background(palette.background),
        ) {
            when (page) {
                LiteMainPage.Dashboard -> DashboardPage(
                    uiState = uiState,
                    statusText = mainViewModel.formatStatus(uiState.status),
                    palette = palette,
                    onAction = onAction,
                    onNavigate = onNavigate,
                )

                LiteMainPage.Nodes -> NodesPage(
                    mainViewModel = mainViewModel,
                    uiState = uiState,
                    isLoading = isLoading,
                    palette = palette,
                    onAction = onAction,
                )
            }

            AnimatedVisibility(
                visible = isLoading,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = palette.accent,
                    trackColor = Color.Transparent,
                )
            }
        }
    }
}

@Composable
private fun DashboardPage(
    uiState: MainUiState,
    statusText: String,
    palette: LitePalette,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ProxyHeroCard(
                uiState = uiState,
                statusText = statusText,
                palette = palette,
                onToggle = { onAction(MainAction.ToggleService) },
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MetricTile(
                    label = "当前节点",
                    value = uiState.selectedServerName.ifBlank { "未选择" },
                    palette = palette,
                    modifier = Modifier.weight(1.35f),
                )
                MetricTile(
                    label = "实时延迟",
                    value = formatDelay(uiState.selectedServerDelayMillis),
                    valueColor = delayColor(uiState.selectedServerDelayMillis, palette),
                    palette = palette,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "候选节点",
                    value = "${uiState.candidateGuids.size} 个",
                    palette = palette,
                    modifier = Modifier.weight(0.9f),
                )
            }
        }

        item {
            OptimizeCard(
                uiState = uiState,
                palette = palette,
                onOptimize = { onAction(MainAction.OptimizeCandidates) },
                onAutoOptimizeChange = { onAction(MainAction.SetAutoOptimize(it)) },
            )
        }

        item {
            EnergyCard(summary = uiState.energySummary, palette = palette)
        }

        item {
            SectionTitle(title = "管理", palette = palette)
            Spacer(Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
            ) {
                QuickEntry(
                    iconRes = R.drawable.ic_subscriptions_24dp,
                    title = "订阅管理",
                    subtitle = "添加、编辑与更新订阅来源",
                    palette = palette,
                    onClick = { onNavigate(MainDestination.Subscriptions) },
                )
                HorizontalDivider(color = palette.border, modifier = Modifier.padding(horizontal = 18.dp))
                QuickEntry(
                    iconRes = R.drawable.ic_settings_24dp,
                    title = "高级设置",
                    subtitle = "路由、应用代理与连接参数",
                    palette = palette,
                    onClick = { onNavigate(MainDestination.Settings) },
                )
            }
        }
    }
}

@Composable
private fun ProxyHeroCard(
    uiState: MainUiState,
    statusText: String,
    palette: LitePalette,
    onToggle: () -> Unit,
) {
    val activeAmount by animateFloatAsState(
        targetValue = if (uiState.isRunning) 1f else 0f,
        label = "proxy-card-active",
    )
    val startColor = blend(palette.surfaceRaised, Color(0xFF133E35), activeAmount * 0.72f)
    val endColor = blend(palette.surface, Color(0xFF102A44), activeAmount * 0.56f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(Brush.linearGradient(listOf(startColor, endColor)))
            .border(1.dp, if (uiState.isRunning) palette.success.copy(alpha = 0.35f) else palette.border, RoundedCornerShape(30.dp))
            .padding(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "代理开关",
                        color = palette.textMuted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (uiState.isRunning) "连接已保护" else "代理已暂停",
                        color = palette.text,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = statusText.substringBefore('\n').ifBlank {
                            if (uiState.isRunning) "运行中" else "等待开启"
                        },
                        color = palette.textMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = uiState.isRunning,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = palette.success,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = palette.textMuted,
                        uncheckedTrackColor = palette.surfaceMuted,
                        uncheckedBorderColor = palette.border,
                    ),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = if (LocalDarkTheme.current) 0.055f else 0.66f))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (uiState.isRunning) palette.success.copy(alpha = 0.18f) else palette.surfaceMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (uiState.isRunning) palette.success else palette.textMuted),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "正在使用",
                        color = palette.textMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = uiState.selectedServerName.ifBlank { "尚未选择节点" },
                        color = palette.text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = formatDelay(uiState.selectedServerDelayMillis),
                    color = delayColor(uiState.selectedServerDelayMillis, palette),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    palette: LitePalette,
    modifier: Modifier = Modifier,
    valueColor: Color = palette.text,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 15.dp)) {
            Text(
                text = label,
                color = palette.textMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = value,
                color = valueColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OptimizeCard(
    uiState: MainUiState,
    palette: LitePalette,
    onOptimize: () -> Unit,
    onAutoOptimizeChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(26.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "智能优选",
                        color = palette.text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "测试候选节点，切换到更快且稳定的连接",
                        color = palette.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.accentSoft)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "${uiState.candidateGuids.size} 个候选",
                        color = palette.accent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onOptimize,
                enabled = !uiState.isOptimizing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = Color.White,
                    disabledContainerColor = palette.accent.copy(alpha = 0.5f),
                    disabledContentColor = Color.White.copy(alpha = 0.8f),
                ),
            ) {
                if (uiState.isOptimizing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(9.dp))
                    Text("正在优选…", fontWeight = FontWeight.SemiBold)
                } else {
                    Text("立即优选", fontWeight = FontWeight.Bold)
                }
            }

            AnimatedVisibility(visible = uiState.optimizationMessage.isNotBlank()) {
                Text(
                    text = uiState.optimizationMessage,
                    color = palette.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = palette.border,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "自动优选",
                        color = palette.text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "每 6 小时后台检查，当前节点失效时也会自动切换",
                        color = palette.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = uiState.autoOptimizeEnabled,
                    onCheckedChange = onAutoOptimizeChange,
                    colors = liteSwitchColors(palette),
                )
            }
        }
    }
}

@Composable
private fun EnergyCard(
    summary: LiteEnergySummary,
    palette: LitePalette,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(26.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "24 小时估算",
                        color = palette.text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (summary.hasData) "依据系统能耗与网络统计" else "正在积累首批设备数据",
                        color = palette.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.success.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "低功耗",
                        color = palette.success,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                EnergyValue(
                    label = "应用估算耗电",
                    value = formatMah(summary.estimatedMah),
                    unit = "mAh",
                    palette = palette,
                )
                EnergyValue(
                    label = "应用流量",
                    value = formatTraffic(summary.trafficBytes),
                    unit = "",
                    palette = palette,
                )
            }
            Spacer(Modifier.height(20.dp))
            EnergyLineChart(
                points = summary.points,
                hasData = summary.hasData,
                palette = palette,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "24 小时前",
                    color = palette.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "现在",
                    color = palette.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun EnergyValue(
    label: String,
    value: String,
    unit: String,
    palette: LitePalette,
) {
    Column {
        Text(label, color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = palette.text,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
            )
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    color = palette.textMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun EnergyLineChart(
    points: List<LiteEnergyPoint>,
    hasData: Boolean,
    palette: LitePalette,
    modifier: Modifier = Modifier,
) {
    val chartPoints = remember(points) { points.sortedBy { it.timestamp } }
    Canvas(modifier = modifier) {
        val horizontalPadding = 3.dp.toPx()
        val verticalPadding = 10.dp.toPx()
        val chartWidth = size.width - horizontalPadding * 2f
        val chartHeight = size.height - verticalPadding * 2f

        repeat(3) { index ->
            val y = verticalPadding + chartHeight * index / 2f
            drawLine(
                color = palette.border.copy(alpha = 0.72f),
                start = Offset(horizontalPadding, y),
                end = Offset(size.width - horizontalPadding, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        if (!hasData || chartPoints.isEmpty()) {
            val y = verticalPadding + chartHeight * 0.72f
            drawLine(
                color = palette.accent.copy(alpha = 0.3f),
                start = Offset(horizontalPadding, y),
                end = Offset(size.width - horizontalPadding, y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            return@Canvas
        }

        val endTime = chartPoints.maxOf { it.timestamp }
        val startTime = endTime - 24L * 60L * 60L * 1000L
        val maxValue = max(chartPoints.maxOf { it.estimatedMah }, 0.001)
        val coordinates = chartPoints.map { point ->
            val xFraction = ((point.timestamp - startTime).toDouble() / (endTime - startTime).toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
            val yFraction = (point.estimatedMah / maxValue).toFloat().coerceIn(0f, 1f)
            Offset(
                x = horizontalPadding + chartWidth * xFraction,
                y = verticalPadding + chartHeight * (1f - yFraction * 0.84f),
            )
        }

        val linePath = Path().apply {
            moveTo(coordinates.first().x, coordinates.first().y)
            coordinates.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val areaPath = Path().apply {
            moveTo(coordinates.first().x, verticalPadding + chartHeight)
            lineTo(coordinates.first().x, coordinates.first().y)
            coordinates.drop(1).forEach { lineTo(it.x, it.y) }
            lineTo(coordinates.last().x, verticalPadding + chartHeight)
            close()
        }
        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(palette.accent.copy(alpha = 0.28f), Color.Transparent),
                startY = verticalPadding,
                endY = verticalPadding + chartHeight,
            ),
        )
        drawPath(
            path = linePath,
            color = palette.accent,
            style = Stroke(
                width = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        drawCircle(
            color = palette.surface,
            radius = 4.5.dp.toPx(),
            center = coordinates.last(),
        )
        drawCircle(
            color = palette.accent,
            radius = 2.7.dp.toPx(),
            center = coordinates.last(),
        )
    }
}

@Composable
private fun NodesPage(
    mainViewModel: MainViewModel,
    uiState: MainUiState,
    isLoading: Boolean,
    palette: LitePalette,
    onAction: (MainAction) -> Unit,
) {
    val selectedGroupId = uiState.selectedGroupId
    val serversFlow = remember(selectedGroupId) { mainViewModel.serversForGroup(selectedGroupId) }
    val servers by serversFlow.collectAsStateWithLifecycle()
    var pendingDeleteGuid by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteAllConfirm by rememberSaveable { mutableStateOf(false) }
    val deleteAllDismissFocusRequester = remember { FocusRequester() }
    val deleteTarget = servers.firstOrNull { it.guid == pendingDeleteGuid }

    LaunchedEffect(showDeleteAllConfirm) {
        if (showDeleteAllConfirm) deleteAllDismissFocusRequester.requestFocus()
    }

    LaunchedEffect(uiState.groups, selectedGroupId) {
        if (uiState.groups.isNotEmpty() && uiState.groups.none { it.id == selectedGroupId }) {
            onAction(MainAction.SelectGroup(uiState.groups.first().id))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeader(
                eyebrow = "连接资源",
                title = "节点",
                supportingText = "选择节点，并决定哪些参与智能优选",
                palette = palette,
                trailing = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(13.dp))
                            .background(palette.surfaceMuted)
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = "${servers.size} 个",
                            color = palette.textMuted,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
            )
        }

        item {
            NodeActions(
                palette = palette,
                isLoading = isLoading,
                hasNodes = uiState.totalNodeCount > 0,
                onImportClipboard = { onAction(MainAction.ImportClipboard) },
                onUpdateSubscriptions = { onAction(MainAction.UpdateSubscriptions) },
                onDeleteAll = { showDeleteAllConfirm = true },
            )
        }

        if (uiState.groups.isNotEmpty()) {
            item {
                GroupSelector(
                    groups = uiState.groups,
                    selectedGroupId = selectedGroupId,
                    palette = palette,
                    onSelect = { onAction(MainAction.SelectGroup(it)) },
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(title = "可用节点", palette = palette)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "已选 ${uiState.candidateGuids.size} 个参与优选",
                    color = palette.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (servers.isEmpty()) {
            item {
                EmptyNodesCard(
                    hasGroups = uiState.groups.isNotEmpty(),
                    isLoading = isLoading,
                    palette = palette,
                )
            }
        } else {
            items(items = servers, key = { it.guid }) { server ->
                NodeCard(
                    server = server,
                    selected = server.guid == uiState.selectedGuid,
                    candidate = server.guid in uiState.candidateGuids,
                    enabled = !isLoading,
                    palette = palette,
                    onSelect = { onAction(MainAction.SelectServer(server.guid)) },
                    onCandidateChange = { onAction(MainAction.ToggleCandidate(server.guid)) },
                    onDelete = { pendingDeleteGuid = server.guid },
                )
            }
        }
    }

    if (pendingDeleteGuid != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteGuid = null },
            containerColor = palette.surfaceRaised,
            titleContentColor = palette.text,
            textContentColor = palette.textMuted,
            title = { Text("删除节点？", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "将删除“${deleteTarget?.profile?.remarks?.ifBlank { "未命名节点" } ?: "该节点"}”。如果它来自订阅，后续更新不会重新导入相同配置；供应商更换密码或连接参数时会视为新节点。"
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteGuid = null }) {
                    Text("取消", color = palette.textMuted)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteGuid?.let { onAction(MainAction.RemoveServer(it)) }
                        pendingDeleteGuid = null
                    },
                ) {
                    Text("删除", color = palette.danger, fontWeight = FontWeight.Bold)
                }
            },
        )
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            containerColor = palette.surfaceRaised,
            titleContentColor = palette.text,
            textContentColor = palette.textMuted,
            title = {
                Text(
                    text = stringResource(R.string.lite_delete_all_nodes_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.lite_delete_all_nodes_message),
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllConfirm = false },
                    modifier = Modifier.focusRequester(deleteAllDismissFocusRequester),
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = palette.textMuted,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isLoading && uiState.totalNodeCount > 0,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = palette.danger,
                        disabledContentColor = palette.danger.copy(alpha = 0.38f),
                    ),
                    onClick = {
                        showDeleteAllConfirm = false
                        onAction(MainAction.DeleteAllNodes)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.lite_action_delete_all_nodes),
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
        )
    }
}

@Composable
private fun NodeActions(
    palette: LitePalette,
    isLoading: Boolean,
    hasNodes: Boolean,
    onImportClipboard: () -> Unit,
    onUpdateSubscriptions: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CompactActionButton(
                iconRes = R.drawable.ic_copy,
                label = "粘贴链接导入",
                palette = palette,
                modifier = Modifier.weight(1f),
                enabled = !isLoading,
                onClick = onImportClipboard,
            )
            CompactActionButton(
                iconRes = R.drawable.ic_cloud_download_24dp,
                label = if (isLoading) "正在更新" else "更新订阅",
                palette = palette,
                modifier = Modifier.weight(1f),
                enabled = !isLoading,
                onClick = onUpdateSubscriptions,
            )
        }
        CompactActionButton(
            iconRes = R.drawable.ic_delete_24dp,
            label = stringResource(R.string.lite_action_delete_all_nodes),
            palette = palette,
            modifier = Modifier.fillMaxWidth(),
            enabled = hasNodes && !isLoading,
            dangerous = true,
            onClick = onDeleteAll,
        )
    }
}

@Composable
private fun CompactActionButton(
    iconRes: Int,
    label: String,
    palette: LitePalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dangerous: Boolean = false,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.55f
    val iconColor = if (dangerous) palette.danger else palette.accent
    val labelColor = if (dangerous) palette.danger else palette.text
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(palette.surface)
            .border(1.dp, palette.border, RoundedCornerShape(17.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = iconColor.copy(alpha = alpha),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = labelColor.copy(alpha = alpha),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun GroupSelector(
    groups: List<GroupMapItem>,
    selectedGroupId: String,
    palette: LitePalette,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(
            text = "节点分组",
            color = palette.textMuted,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(9.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items = groups, key = { it.id }) { group ->
                val selected = group.id == selectedGroupId
                val background by animateColorAsState(
                    targetValue = if (selected) palette.accent else palette.surface,
                    label = "group-background",
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(background)
                        .border(
                            width = 1.dp,
                            color = if (selected) Color.Transparent else palette.border,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable { onSelect(group.id) }
                        .padding(horizontal = 15.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = group.remarks.ifBlank { "默认分组" },
                        color = if (selected) Color.White else palette.text,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeCard(
    server: ServersCache,
    selected: Boolean,
    candidate: Boolean,
    enabled: Boolean,
    palette: LitePalette,
    onSelect: () -> Unit,
    onCandidateChange: () -> Unit,
    onDelete: () -> Unit,
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) palette.accent.copy(alpha = 0.72f) else palette.border,
        label = "node-border",
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) palette.accentSoft.copy(alpha = 0.54f) else palette.surface,
        label = "node-background",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(start = 15.dp, top = 14.dp, end = 10.dp, bottom = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selected,
                    enabled = enabled,
                    onClick = onSelect,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = palette.accent,
                        unselectedColor = palette.textMuted,
                    ),
                )
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.profile.remarks.ifBlank { "未命名节点" },
                        color = palette.text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = nodeDescription(server),
                        color = palette.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DelayBadge(delay = server.testDelayMillis, palette = palette)
                IconButton(enabled = enabled, onClick = onDelete) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete_24dp),
                        contentDescription = "删除节点",
                        tint = palette.danger.copy(alpha = 0.82f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(start = 48.dp, top = 11.dp, bottom = 10.dp),
                color = palette.border.copy(alpha = 0.72f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 49.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "参与优选",
                        color = palette.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (candidate) "已加入候选池" else "不参与自动测速",
                        color = palette.textMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(
                    checked = candidate,
                    enabled = enabled,
                    onCheckedChange = { onCandidateChange() },
                    colors = liteSwitchColors(palette),
                )
            }
        }
    }
}

@Composable
private fun DelayBadge(delay: Long, palette: LitePalette) {
    val color = delayColor(delay, palette)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text = formatDelay(delay),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyNodesCard(
    hasGroups: Boolean,
    isLoading: Boolean,
    palette: LitePalette,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .border(1.dp, palette.border, RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = 38.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(palette.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_subscriptions_24dp),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (isLoading) "正在读取节点" else if (hasGroups) "这个分组还没有节点" else "还没有可用节点",
            color = palette.text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "导入后仅保留名称含“日本”或“东京”的节点",
            color = palette.textMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PageHeader(
    eyebrow: String,
    title: String,
    supportingText: String,
    palette: LitePalette,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = eyebrow.uppercase(Locale.ROOT),
                color = palette.accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = title,
                color = palette.text,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = supportingText,
                color = palette.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun SectionTitle(title: String, palette: LitePalette) {
    Text(
        text = title,
        color = palette.text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun QuickEntry(
    iconRes: Int,
    title: String,
    subtitle: String,
    palette: LitePalette,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = palette.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = "›",
            color = palette.textMuted,
            fontSize = 25.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun LiteBottomNavigation(
    selectedPage: LiteMainPage,
    palette: LitePalette,
    onSelect: (LiteMainPage) -> Unit,
) {
    NavigationBar(
        containerColor = palette.surface,
        tonalElevation = 0.dp,
    ) {
        NavigationBarItem(
            selected = selectedPage == LiteMainPage.Dashboard,
            onClick = { onSelect(LiteMainPage.Dashboard) },
            icon = {
                DashboardGlyph(
                    selected = selectedPage == LiteMainPage.Dashboard,
                    palette = palette,
                )
            },
            label = { Text("首页", fontWeight = FontWeight.SemiBold) },
            colors = liteNavigationColors(palette),
        )
        NavigationBarItem(
            selected = selectedPage == LiteMainPage.Nodes,
            onClick = { onSelect(LiteMainPage.Nodes) },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_subscriptions_24dp),
                    contentDescription = "节点",
                    modifier = Modifier.size(22.dp),
                )
            },
            label = { Text("节点", fontWeight = FontWeight.SemiBold) },
            colors = liteNavigationColors(palette),
        )
    }
}

@Composable
private fun DashboardGlyph(selected: Boolean, palette: LitePalette) {
    val tint = if (selected) palette.accent else palette.textMuted
    Canvas(modifier = Modifier.size(22.dp)) {
        val gap = 3.dp.toPx()
        val cell = (size.width - gap) / 2f
        val radius = 2.4.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset.Zero,
            size = androidx.compose.ui.geometry.Size(cell, cell),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(cell + gap, 0f),
            size = androidx.compose.ui.geometry.Size(cell, cell),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, cell + gap),
            size = androidx.compose.ui.geometry.Size(cell, cell),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(cell + gap, cell + gap),
            size = androidx.compose.ui.geometry.Size(cell, cell),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
    }
}

@Composable
private fun liteSwitchColors(palette: LitePalette) = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = palette.accent,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = palette.textMuted,
    uncheckedTrackColor = palette.surfaceMuted,
    uncheckedBorderColor = palette.border,
)

@Composable
private fun liteNavigationColors(palette: LitePalette) = NavigationBarItemDefaults.colors(
    selectedIconColor = palette.accent,
    selectedTextColor = palette.accent,
    indicatorColor = palette.accentSoft,
    unselectedIconColor = palette.textMuted,
    unselectedTextColor = palette.textMuted,
)

private fun delayColor(delay: Long, palette: LitePalette): Color = when {
    delay < 0L -> palette.danger
    delay == 0L -> palette.textMuted
    delay <= 300L -> palette.success
    else -> Color(0xFFF0A43A)
}

private fun formatDelay(delay: Long): String = when {
    delay < 0L -> "不可用"
    delay == 0L -> "未测速"
    else -> "$delay ms"
}

private fun formatMah(value: Double): String = when {
    value >= 100.0 -> String.format(Locale.US, "%.0f", value)
    value >= 10.0 -> String.format(Locale.US, "%.1f", value)
    else -> String.format(Locale.US, "%.2f", value)
}

private fun formatTraffic(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L).toDouble()
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    return when {
        safeBytes >= gib -> String.format(Locale.US, "%.2f GB", safeBytes / gib)
        safeBytes >= mib -> String.format(Locale.US, "%.1f MB", safeBytes / mib)
        safeBytes >= kib -> String.format(Locale.US, "%.1f KB", safeBytes / kib)
        else -> "${safeBytes.toLong()} B"
    }
}

private fun nodeDescription(server: ServersCache): String {
    val type = server.profile.configType.name
    val address = server.profile.server.orEmpty().trim()
    return if (address.isBlank()) type else "$type · $address"
}

private fun blend(start: Color, end: Color, fraction: Float): Color {
    val amount = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * amount,
        green = start.green + (end.green - start.green) * amount,
        blue = start.blue + (end.blue - start.blue) * amount,
        alpha = start.alpha + (end.alpha - start.alpha) * amount,
    )
}
