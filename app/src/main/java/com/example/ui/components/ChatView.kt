package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.SpaceBorder
import com.example.ui.theme.SpaceCard
import com.example.ui.theme.SpaceCardElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletNeon
import com.example.ui.viewmodel.ChatMessage
import com.example.ui.viewmodel.UiState

data class AiModelInfo(
    val id: String,
    val displayName: String,
    val provider: String,
    val supportsReasoning: Boolean,
    val supportsTools: Boolean,
    val supportsVision: Boolean
)

val AVAILABLE_MODELS = listOf(
    AiModelInfo(
        id = "@cf/openai/gpt-oss-20b",
        displayName = "GPT-OSS 20B",
        provider = "Cloudflare",
        supportsReasoning = true,
        supportsTools = true,
        supportsVision = false
    ),
    AiModelInfo(
        id = "@cf/meta/llama-3.3-70b-instruct",
        displayName = "Llama 3.3 70B",
        provider = "Cloudflare",
        supportsReasoning = true,
        supportsTools = true,
        supportsVision = false
    ),
    AiModelInfo(
        id = "@cf/meta/llama-3.1-8b-instruct",
        displayName = "Llama 3.1 8B",
        provider = "Cloudflare",
        supportsReasoning = false,
        supportsTools = true,
        supportsVision = false
    )
)

@Composable
fun ChatView(
    messages: List<ChatMessage>,
    uiState: UiState,
    onSendMessage: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val currentModel = AVAILABLE_MODELS.find { it.id == uiState.selectedModelId } ?: AVAILABLE_MODELS.first()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Model Selector Top Bar
        Surface(
            color = SpaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SpaceBorder)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SpaceCardElevated)
                            .clickable { modelMenuExpanded = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "Model",
                            tint = CyanNeon,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = currentModel.displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TextPrimary
                        )
                        Text(text = " ▼", fontSize = 10.sp, color = TextSecondary)
                    }

                    // Status
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(EmeraldGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "● Available",
                            color = EmeraldGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                DropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false },
                    modifier = Modifier.background(SpaceCardElevated)
                ) {
                    AVAILABLE_MODELS.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = "${model.displayName} (${model.provider})",
                                        fontWeight = FontWeight.Bold,
                                        color = if (model.id == currentModel.id) CyanNeon else TextPrimary
                                    )
                                    Text(
                                        text = "Tools: ✓ | Reasoning: ${if (model.supportsReasoning) "✓" else "✗"}",
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )
                                }
                            },
                            onClick = {
                                onSelectModel(model.id)
                                modelMenuExpanded = false
                            }
                        )
                    }
                }

                // Capabilities Matrix Row
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Provider: ${currentModel.provider}", fontSize = 11.sp, color = TextSecondary)
                    Text(
                        text = if (currentModel.supportsReasoning) "✓ Reasoning" else "✗ Reasoning",
                        fontSize = 11.sp,
                        color = if (currentModel.supportsReasoning) EmeraldGreen else TextSecondary
                    )
                    Text(
                        text = if (currentModel.supportsTools) "✓ Tool Calling" else "✗ Tool Calling",
                        fontSize = 11.sp,
                        color = if (currentModel.supportsTools) CyanNeon else TextSecondary
                    )
                    Text(text = "✗ Vision", fontSize = 11.sp, color = TextSecondary)
                }
            }
        }

        // Messages List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }

            if (uiState.isExecutingPrompt) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SpaceCardElevated)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = CyanNeon,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (uiState.activeToolName != null) {
                                "جاري استدعاء الأداة: ${uiState.activeToolName}..."
                            } else {
                                "النموذج يفكر وينسق الأوامر..."
                            },
                            fontSize = 12.sp,
                            color = CyanNeon
                        )
                    }
                }
            }
        }

        // Quick suggestions row
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val suggestions = listOf(
                "افتح TikTok وانتقل للفيديو التالي",
                "افحص عناصر الشاشة الحالية",
                "تمرير لأعلى (Swipe Up)",
                "معلومات الهاتف"
            )
            items(suggestions) { text ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(SpaceCard)
                        .border(1.dp, SpaceBorder, RoundedCornerShape(16.dp))
                        .clickable { onSendMessage(text) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(text = text, fontSize = 11.sp, color = TextSecondary)
                }
            }
        }

        // Input Field Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("اكتب أمراً للـ Agent (مثال: افتح TikTok)...", fontSize = 13.sp) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input_field"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanNeon,
                    unfocusedBorderColor = SpaceBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(24.dp),
                maxLines = 3
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        val text = inputText
                        inputText = ""
                        onSendMessage(text)
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CyanNeon)
                    .testTag("send_prompt_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = Color.Black
                )
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    when (message.sender) {
        "USER" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                        .background(CyanNeon.copy(alpha = 0.2f))
                        .border(1.dp, CyanNeon.copy(alpha = 0.5f), RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                        .padding(12.dp)
                ) {
                    Text(text = message.content, color = TextPrimary, fontSize = 14.sp)
                }
            }
        }

        "TOOL" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SpaceCardElevated)
                        .border(1.dp, SpaceBorder, RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(VioletNeon.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = "Tool",
                                tint = VioletNeon,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Tool: ${message.toolName ?: "command"}",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = VioletNeon
                            )
                            Text(
                                text = message.content,
                                fontSize = 12.sp,
                                color = TextPrimary
                            )
                        }
                        if (message.isSuccess != null) {
                            Icon(
                                imageVector = if (message.isSuccess) Icons.Default.Check else Icons.Default.Close,
                                contentDescription = null,
                                tint = if (message.isSuccess) EmeraldGreen else CrimsonAlert,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        "SYSTEM" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "⚙️ ${message.content}",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SpaceCard)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        else -> { // AI
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                        .background(SpaceCard)
                        .border(1.dp, VioletNeon.copy(alpha = 0.3f), RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = VioletNeon,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "DroidPilot AI",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = VioletNeon
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = message.content, color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
