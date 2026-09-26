package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.ChatView
import com.example.ui.components.DashboardView
import com.example.ui.components.LiveLogsView
import com.example.ui.components.SettingsDialog
import com.example.ui.components.ToolPlaygroundView
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.DroidPilotTheme
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.SpaceBlack
import com.example.ui.theme.SpaceCard
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletNeon
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DroidPilotTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshState()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val isAgentActive by viewModel.isAgentServiceActive.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val recentLogs by viewModel.recentLogs.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = SpaceBlack,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (deviceInfo.accessibilityEnabled) EmeraldGreen else Color.Gray)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "DroidPilot AI",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = SpaceCard
                ),
                actions = {
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.testTag("open_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = CyanNeon
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = SpaceCard,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("الرئيسية", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanNeon,
                        selectedTextColor = CyanNeon,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = SpaceBlack
                    ),
                    modifier = Modifier.testTag("nav_dashboard")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Chat, contentDescription = "Agent Chat") },
                    label = { Text("المحادثة", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanNeon,
                        selectedTextColor = CyanNeon,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = SpaceBlack
                    ),
                    modifier = Modifier.testTag("nav_chat")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Build, contentDescription = "Tools") },
                    label = { Text("الأدوات", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanNeon,
                        selectedTextColor = CyanNeon,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = SpaceBlack
                    ),
                    modifier = Modifier.testTag("nav_tools")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.ListAlt, contentDescription = "Logs") },
                    label = { Text("السجلات", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanNeon,
                        selectedTextColor = CyanNeon,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = SpaceBlack
                    ),
                    modifier = Modifier.testTag("nav_logs")
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> DashboardView(
                    deviceInfo = deviceInfo,
                    uiState = uiState,
                    isAgentActive = isAgentActive,
                    onRegeneratePin = { viewModel.regeneratePin() },
                    onPairWithWorker = { viewModel.pairWithWorker() },
                    onToggleAgent = { context -> viewModel.toggleAgentService(context) },
                    onTestTikTok = {
                        selectedTab = 1
                        viewModel.executeAutonomousPrompt("افتح TikTok وانتقل للفيديو التالي")
                    },
                    onRefresh = { viewModel.refreshState() }
                )
                1 -> ChatView(
                    messages = chatMessages,
                    uiState = uiState,
                    onSendMessage = { prompt -> viewModel.executeAutonomousPrompt(prompt) },
                    onSelectModel = { modelId -> viewModel.setModel(modelId) }
                )
                2 -> ToolPlaygroundView(
                    lastResult = uiState.lastExecutedResult,
                    onExecuteTool = { tool, args -> viewModel.executeSingleTool(tool, args) }
                )
                3 -> LiveLogsView(
                    logs = recentLogs,
                    selectedFilter = uiState.filterLogSource,
                    onFilterSelect = { filter -> viewModel.setLogFilter(filter) },
                    onClearLogs = { viewModel.clearLogs() }
                )
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            initialWorkerUrl = uiState.workerUrl,
            onDismiss = { showSettingsDialog = false },
            onSave = { url, key ->
                viewModel.saveSettings(url, key)
                showSettingsDialog = false
            }
        )
    }
}
