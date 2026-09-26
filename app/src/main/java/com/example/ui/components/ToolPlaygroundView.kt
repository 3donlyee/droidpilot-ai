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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.data.model.ScreenNode
import com.example.data.model.ScreenNodesResult
import com.example.data.model.ToolResult
import com.example.engine.CommandPolicy
import com.example.engine.CommandValidator
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.SpaceBorder
import com.example.ui.theme.SpaceCard
import com.example.ui.theme.SpaceCardElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletNeon

@Composable
fun ToolPlaygroundView(
    lastResult: ToolResult?,
    onExecuteTool: (String, Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier
) {
    var shellInput by remember { mutableStateOf("getprop ro.product.model") }
    var appPackageInput by remember { mutableStateOf("TikTok") }
    var tapTargetInput by remember { mutableStateOf("") }

    val policy = remember(shellInput) {
        CommandValidator.validate(shellInput).policy
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "🛠️ أدوات التحكم المباشر (Tools)",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = TextPrimary
            )
            Text(
                text = "تشغيل واختبار جميع أدوات الوكيل على الهاتف مباشرة",
                fontSize = 12.sp,
                color = TextSecondary
            )
        }

        // Quick gestures row
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SpaceCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SpaceBorder, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(text = "الإيماءات الأساسية", fontWeight = FontWeight.Bold, color = CyanNeon, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ToolActionButton(
                            icon = Icons.Default.ArrowUpward,
                            label = "Swipe Up",
                            onClick = { onExecuteTool("swipe_up", emptyMap()) },
                            modifier = Modifier.weight(1f)
                        )
                        ToolActionButton(
                            icon = Icons.Default.ArrowDownward,
                            label = "Swipe Down",
                            onClick = { onExecuteTool("swipe_down", emptyMap()) },
                            modifier = Modifier.weight(1f)
                        )
                        ToolActionButton(
                            icon = Icons.Default.ArrowBack,
                            label = "Press Back",
                            onClick = { onExecuteTool("press_back", emptyMap()) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ToolActionButton(
                            icon = Icons.Default.Search,
                            label = "فحص الشاشة",
                            onClick = { onExecuteTool("get_screen_nodes", emptyMap()) },
                            modifier = Modifier.weight(1f)
                        )
                        ToolActionButton(
                            icon = Icons.Default.Devices,
                            label = "حزمة الواجهة",
                            onClick = { onExecuteTool("get_current_package", emptyMap()) },
                            modifier = Modifier.weight(1f)
                        )
                        ToolActionButton(
                            icon = Icons.Default.CameraAlt,
                            label = "Screenshot",
                            onClick = { onExecuteTool("take_screenshot", emptyMap()) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Launch App Section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SpaceCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SpaceBorder, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(text = "تشغيل تطبيق (open_app)", fontWeight = FontWeight.Bold, color = VioletNeon, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = appPackageInput,
                            onValueChange = { appPackageInput = it },
                            placeholder = { Text("مثال: TikTok أو com.zhiliaoapp.musically", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VioletNeon,
                                unfocusedBorderColor = SpaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onExecuteTool("open_app", mapOf("package_name" to appPackageInput)) },
                            colors = ButtonDefaults.buttonColors(containerColor = VioletNeon),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Launch, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("فتح")
                        }
                    }
                }
            }
        }

        // Tap Element Section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SpaceCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SpaceBorder, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(text = "نقر عنصر بالمعرف أو النص (tap_element)", fontWeight = FontWeight.Bold, color = CyanNeon, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = tapTargetInput,
                            onValueChange = { tapTargetInput = it },
                            placeholder = { Text("مثال: node_001 أو 'Like' أو نص الزر", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanNeon,
                                unfocusedBorderColor = SpaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onExecuteTool("tap_element", mapOf("element_id" to tapTargetInput)) },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.TouchApp, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("نقر", color = Color.Black)
                        }
                    }
                }
            }
        }

        // Shell Runner Section with Command Policy
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SpaceCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SpaceBorder, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "أمر Shell (run_shell)", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                        // Policy badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when (policy) {
                                        CommandPolicy.SAFE -> EmeraldGreen.copy(alpha = 0.2f)
                                        CommandPolicy.REQUIRES_CONFIRMATION -> AmberWarning.copy(alpha = 0.2f)
                                        CommandPolicy.BLOCKED -> CrimsonAlert.copy(alpha = 0.2f)
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = policy.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = when (policy) {
                                    CommandPolicy.SAFE -> EmeraldGreen
                                    CommandPolicy.REQUIRES_CONFIRMATION -> AmberWarning
                                    CommandPolicy.BLOCKED -> CrimsonAlert
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = shellInput,
                        onValueChange = { shellInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanNeon,
                            unfocusedBorderColor = SpaceBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { onExecuteTool("run_shell", mapOf("command" to shellInput)) },
                        colors = ButtonDefaults.buttonColors(containerColor = SpaceCardElevated),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Code, contentDescription = null, tint = CyanNeon)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تنفيذ الأمر في الـ Shell", color = CyanNeon)
                    }
                }
            }
        }

        // Last Result Output Display
        if (lastResult != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SpaceCardElevated),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (lastResult.success) EmeraldGreen else CrimsonAlert, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "نتيجة الأداة: ${lastResult.tool}",
                                fontWeight = FontWeight.Bold,
                                color = if (lastResult.success) EmeraldGreen else CrimsonAlert
                            )
                            Text(
                                text = if (lastResult.success) "SUCCESS ✓" else "FAILED ✗",
                                color = if (lastResult.success) EmeraldGreen else CrimsonAlert,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // If data is ScreenNodesResult
                        if (lastResult.data is ScreenNodesResult) {
                            val res = lastResult.data
                            Text(
                                text = "الحزمة: ${res.packageName} • العناصر المكتشفة: ${res.elementCount}",
                                fontSize = 12.sp,
                                color = CyanNeon
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            res.elements.take(15).forEach { node ->
                                ScreenNodeItem(node)
                            }
                        } else {
                            Text(
                                text = lastResult.data?.toString() ?: (lastResult.error ?: "No data"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScreenNodeItem(node: ScreenNode) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SpaceCard)
            .padding(6.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = node.id,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = VioletNeon
                )
                Text(
                    text = "[${node.bounds.left},${node.bounds.top} to ${node.bounds.right},${node.bounds.bottom}]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
            if (!node.text.isNullOrEmpty()) {
                Text(text = "text: \"${node.text}\"", fontSize = 11.sp, color = TextPrimary)
            }
            if (!node.description.isNullOrEmpty()) {
                Text(text = "desc: \"${node.description}\"", fontSize = 11.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
fun ToolActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = SpaceCardElevated),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = CyanNeon, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = label, fontSize = 11.sp, color = TextPrimary)
        }
    }
}
