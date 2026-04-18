package com.airpay.upi.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airpay.upi.R

private val BgPitch = Color(0xFFFAFAFF)
private val BgSurface = Color(0xFFFFFFFF)
private val NeonCyan = Color(0xFF6666FF)
private val NeonCyanDim = Color(0xFF4D4DCC)
private val ToggleSoft = Color(0xFFE8C8D0)
private val ToggleSoftText = Color(0xFF8A4D5E)
private val TextPrimary = Color(0xFF0C0C16)
private val TextSecondary = Color(0xFF555577)
private val GlassBorder = Color(0x336666FF)
private val NeonSuccess = Color(0xFF2FB986)
private val avatarColors = listOf(
    Color(0xFF6666FF),
    Color(0xFFB8BAFF),
    Color(0xFF2FB986),
    Color(0xFFC9E8FF),
    Color(0xFF4D4DCC)
)

@Composable
fun HomeScreen(
    balance: String,
    serviceEnabled: Boolean,
    currentLanguageLabel: String,
    recentRecipients: List<Pair<String, String>>,
    onScanQR: () -> Unit,
    onManualPay: () -> Unit,
    onVoicePay: () -> Unit,
    onCheckBalance: () -> Unit,
    onOpenHistory: () -> Unit,
    onRecipientClick: (String, String) -> Unit,
    onOpenAccessibility: () -> Unit,
    onToggleLanguage: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPitch)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 44.dp, bottom = 28.dp)
        ) {
            HeaderSection(
                serviceEnabled = serviceEnabled,
                currentLanguageLabel = currentLanguageLabel,
                onOpenAccessibility = onOpenAccessibility,
                onToggleLanguage = onToggleLanguage
            )
            Spacer(Modifier.height(22.dp))
            DebitCard(balance = balance, onRefresh = onCheckBalance)
            Spacer(Modifier.height(24.dp))
            ActionButtonsRow(
                onManualPay = onManualPay,
                onVoicePay = onVoicePay,
                onCheckBalance = onCheckBalance,
                onHistory = onOpenHistory
            )
            Spacer(Modifier.height(24.dp))
            if (recentRecipients.isNotEmpty()) {
                RecipientsSection(recipients = recentRecipients, onRecipientClick = onRecipientClick)
                Spacer(Modifier.height(24.dp))
            }
            BigScannerButton(onScanQR = onScanQR)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HeaderSection(
    serviceEnabled: Boolean,
    currentLanguageLabel: String,
    onOpenAccessibility: () -> Unit,
    onToggleLanguage: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "AirPay",
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.6.sp,
                style = TextStyle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF4B5DFF),
                            Color(0xFF3F51F7),
                            Color(0xFF2F43D8)
                        )
                    ),
                    letterSpacing = 0.6.sp
                )
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            HeaderChip(
                text = if (serviceEnabled) stringResource(R.string.main_service_on) else stringResource(R.string.main_service_off),
                color = if (serviceEnabled) NeonSuccess else NeonCyanDim,
                background = if (serviceEnabled) NeonSuccess.copy(alpha = 0.15f) else NeonCyan.copy(alpha = 0.15f),
                onClick = onOpenAccessibility
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(ToggleSoft)
                    .clickable(onClick = onToggleLanguage)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = currentLanguageLabel,
                    color = ToggleSoftText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HeaderChip(text: String, color: Color, background: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text = text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DebitCard(balance: String, onRefresh: () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(20.dp),
                spotColor = Color(0xFF16162B).copy(alpha = 0.2f),
                ambientColor = Color(0xFF16162B).copy(alpha = 0.1f)
            )
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF2A2A40), Color(0xFF16162B), Color(0xFF0C0C16)),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
            .padding(24.dp)
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .offset(x = maxWidth - 120.dp, y = (-18).dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f))
        )
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AirPay",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp
                )
                HeaderChip(
                    text = stringResource(R.string.home_refresh),
                    color = Color.White,
                    background = Color.White.copy(alpha = 0.14f),
                    onClick = onRefresh
                )
            }

            Spacer(Modifier.height(20.dp))
            Text("••••  ••••  ••••  4295", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))

            Row {
                Column {
                    Text("VALID THRU", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
                    Text("12/28", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(28.dp))
                Column {
                    Text("CARD HOLDER", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
                    Text("AIRPAY USER", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.weight(1f))

            Column {
                Text(stringResource(R.string.home_balance_label), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                Text("₹ $balance", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ActionButtonsRow(
    onManualPay: () -> Unit,
    onVoicePay: () -> Unit,
    onCheckBalance: () -> Unit,
    onHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(BgSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 16.dp)
    ) {
        Text(stringResource(R.string.home_quick_actions), color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionButton(R.drawable.ic_mobile, stringResource(R.string.home_action_pay_id), onManualPay)
            ActionButton(R.drawable.ic_call, stringResource(R.string.home_action_voice), onVoicePay)
            ActionButton(R.drawable.ic_arrow_down, stringResource(R.string.home_action_balance), onCheckBalance)
            ActionButton(R.drawable.ic_history, stringResource(R.string.home_action_history), onHistory)
        }
    }
}

@Composable
private fun ActionButton(icon: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(16.dp),
                    spotColor = NeonCyan.copy(alpha = 0.15f),
                    ambientColor = NeonCyan.copy(alpha = 0.05f)
                )
                .clip(RoundedCornerShape(16.dp))
                .background(NeonCyan.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                tint = NeonCyan,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun RecipientsSection(
    recipients: List<Pair<String, String>>,
    onRecipientClick: (String, String) -> Unit
) {
    Column {
        Text(stringResource(R.string.home_recent_recipients), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.home_recent_hint), color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(recipients.size) { index ->
                val (name, id) = recipients[index]
                val avatarColor = avatarColors[kotlin.math.abs(id.hashCode()) % avatarColors.size]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onRecipientClick(name, id) }
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(avatarColor.copy(alpha = 0.15f))
                            .border(1.5.dp, avatarColor.copy(alpha = 0.7f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.firstOrNull()?.uppercase() ?: "?",
                            color = if (avatarColor == Color(0xFFC9E8FF)) TextPrimary else avatarColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = name,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun BigScannerButton(onScanQR: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(24.dp),
                spotColor = NeonCyan.copy(alpha = glowAlpha * 0.4f),
                ambientColor = NeonCyan.copy(alpha = 0.05f)
            )
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(NeonCyan.copy(alpha = 0.08f), NeonCyan.copy(alpha = 0.02f))
                )
            )
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            .clickable(onClick = onScanQR),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_qr_scan),
                contentDescription = "Scan QR",
                tint = NeonCyan,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.home_scan_pay),
                color = NeonCyan,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.home_scan_pay_desc), color = TextSecondary, fontSize = 13.sp)
        }
    }
}
