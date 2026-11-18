package com.raymanvh.waterhsync.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raymanvh.waterhsync.SyncController
import com.raymanvh.waterhsync.SyncedDrink
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(c: SyncController, withPermissions: (() -> Unit) -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    // Ticks so "5 min ago" stays fresh.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("WaterH Sync", fontWeight = FontWeight.SemiBold) },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!c.signedIn) item { SignInCard(c) }

            if (c.bottleAddress == null) {
                item { FindBottleCard(c, withPermissions) }
            } else {
                item { HeroCard(c, now, withPermissions) }
            }

            c.error?.let { item { MessageCard(it, isError = true) } }

            item { DrinksCard(c) }
            item { SettingsCard(c, withPermissions) }
            item { DiagnosticsCard(c, withPermissions) }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun HeroCard(c: SyncController, now: Long, withPermissions: (() -> Unit) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WaterDrop(Modifier.size(44.dp), colors.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(prettyBottleName(c.bottleName), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        c.lastSyncAt?.let { "Synced ${relative(it, now)}" } ?: "Not synced yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
            }

            c.bottleInfo?.let { info ->
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile(
                        label = if (info.charging) "Charging" else "Battery",
                        value = "${info.batteryPercent}%",
                        modifier = Modifier.weight(1f),
                    ) { BatteryGauge(info.batteryPercent, info.charging, Modifier.size(width = 30.dp, height = 15.dp)) }
                    StatTile(
                        label = "Today on bottle",
                        value = info.todayMl?.let { c.formatVolume(it) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Bottle checked ${relative(info.seenAt, now)} · firmware ${info.firmware}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onPrimaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            if (c.busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(colors.surface.copy(alpha = 0.6f))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(c.progress ?: "Working…", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { c.cancel() }) { Text("Cancel") }
                }
            } else {
                Button(
                    onClick = { withPermissions { c.sync() } },
                    enabled = c.signedIn,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(50),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Sync now", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                if (!c.signedIn) {
                    Text(
                        "Sign in to WaterH to sync.",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally),
                    )
                }
            }

            c.lastResult?.let {
                if (!c.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = colors.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (icon != null) {
                Spacer(Modifier.width(10.dp))
                icon()
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun SignInCard(c: SyncController) {
    var email by rememberSaveable { mutableStateOf(c.email ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    SectionCard(title = "Sign in to WaterH") {
        Text(
            "Drinks are uploaded to your WaterH account, where the Home Assistant integration picks them up. " +
                "Only a sign-in token is kept on this phone, not your password.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        c.signInError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { c.signIn(email, password); password = "" },
            enabled = !c.signingIn && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(50),
        ) {
            if (c.signingIn) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("Sign in")
        }
        Text(
            "Signed up with Google or Facebook? Set a password in the WaterH app first.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun FindBottleCard(c: SyncController, withPermissions: (() -> Unit) -> Unit) {
    SectionCard(title = "Find your bottle") {
        Text(
            "Close the WaterH app, then tilt the bottle to wake it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        FilledTonalButton(
            onClick = { if (c.scanning) c.stopScan() else withPermissions { c.startScan() } },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(50),
        ) {
            if (c.scanning) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Searching… tap to stop")
            } else {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("Search for bottles")
            }
        }
        c.found.forEach { b ->
            ListItem(
                headlineContent = { Text(prettyBottleName(b.name)) },
                supportingContent = { Text("${b.address} · signal ${signalLabel(b.rssi)}") },
                leadingContent = { WaterDrop(Modifier.size(28.dp), MaterialTheme.colorScheme.primary) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { c.selectBottle(b) },
            )
        }
        if (!c.scanning && c.found.isEmpty()) {
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun DrinksCard(c: SyncController) {
    val today = startOfToday()
    val todays = c.history.filter { it.timeMillis >= today }
    val earlier = c.history.filter { it.timeMillis < today }.take(10)
    SectionCard(title = "Synced today") {
        if (todays.isEmpty()) {
            Text(
                "No drinks synced from the bottle today yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "${todays.size} ${if (todays.size == 1) "drink" else "drinks"} · ${c.formatVolume(todays.sumOf { it.ml })}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            todays.forEach { DrinkRow(it, c, showDate = false) }
        }
        if (earlier.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Earlier", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            earlier.forEach { DrinkRow(it, c, showDate = true) }
        }
    }
}

@Composable
private fun DrinkRow(d: SyncedDrink, c: SyncController, showDate: Boolean) {
    val fmt = if (showDate) "EEE h:mm a" else "h:mm a"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(12.dp))
        Text(SimpleDateFormat(fmt, Locale.getDefault()).format(Date(d.timeMillis)), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(c.formatVolume(d.ml), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsCard(c: SyncController, withPermissions: (() -> Unit) -> Unit) {
    SectionCard(title = "Settings") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Automatic sync", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    if (c.autoSync) "Syncs whenever your bottle wakes up nearby. A small notification stays while this is on."
                    else "Sync in the background whenever your bottle wakes up nearby — no need to open any app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = c.autoSync,
                enabled = c.bottleAddress != null && c.signedIn,
                onCheckedChange = { on ->
                    if (on) withPermissions { c.setAutoSyncEnabled(true) } else c.setAutoSyncEnabled(false)
                },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        HomeAssistantSetting(c)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SettingRow(
            title = "WaterH account",
            subtitle = if (c.signedIn) (c.email ?: "Signed in") + " · ${if (c.imperial) "oz" else "mL"}" else "Not signed in",
            action = if (c.signedIn) "Sign out" else null,
            onAction = { c.signOut() },
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SettingRow(
            title = "Bottle",
            subtitle = c.bottleAddress?.let { "${prettyBottleName(c.bottleName)} · $it" } ?: "None selected",
            action = if (c.bottleAddress != null) "Change" else null,
            onAction = { c.forgetBottle() },
        )
    }
}

@Composable
private fun HomeAssistantSetting(c: SyncController) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(c.haWebhookUrl ?: "") }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Home Assistant", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        c.haWebhookUrl == null -> "Optional: send battery and charging to the WaterH integration."
                        c.haStatus != null -> c.haStatus!!
                        else -> "Connected; reports after each sync."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                draft = c.haWebhookUrl ?: ""
                editing = !editing
            }) { Text(if (editing) "Close" else if (c.haWebhookUrl == null) "Set up" else "Edit") }
        }
        if (editing) {
            Text(
                "In Home Assistant: Settings → Devices & services → WaterH → Configure. Copy the webhook URL shown there and paste it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Webhook URL") },
                placeholder = { Text("https://…/api/webhook/…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { c.setHomeAssistantUrl(draft); editing = false },
                    enabled = draft.isBlank() || draft.trim().startsWith("http"),
                ) { Text("Save") }
                if (c.haWebhookUrl != null) {
                    OutlinedButton(onClick = { c.sendHomeAssistantTest() }) { Text("Send test") }
                    TextButton(onClick = { c.setHomeAssistantUrl(""); draft = ""; editing = false }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun DiagnosticsCard(c: SyncController, withPermissions: (() -> Unit) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text("Diagnostics", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp)) {
                Text(
                    "Check reads the bottle without changing anything or uploading. Copy the log to share it when something goes wrong.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { withPermissions { c.checkOnly() } },
                        enabled = !c.busy && c.bottleAddress != null,
                    ) { Text("Check bottle") }
                    OutlinedButton(onClick = {
                        val text = buildString {
                            appendLine("WaterH Sync log")
                            appendLine("Bottle: ${c.bottleName} (${c.bottleAddress})")
                            c.error?.let { appendLine("Error: $it") }
                            c.lastResult?.let { appendLine("Result: $it") }
                            c.rawLog.forEach { appendLine(it) }
                        }
                        clipboard.setText(AnnotatedString(text))
                    }) { Text("Copy log") }
                }
                c.backgroundNote?.let {
                    Text(
                        "Last background issue: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (c.rawLog.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(10.dp),
                    ) {
                        c.rawLog.takeLast(60).forEach {
                            Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun MessageCard(text: String, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) colors.errorContainer else colors.secondaryContainer,
            contentColor = if (isError) colors.onErrorContainer else colors.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A simple battery outline filled to [percent]. */
@Composable
private fun BatteryGauge(percent: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.onSurface
    val fill = when {
        charging -> MaterialTheme.colorScheme.primary
        percent <= 15 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        val nub = size.width * 0.08f
        val body = Size(size.width - nub - stroke, size.height - stroke)
        drawRoundRect(outline, topLeft = Offset(stroke / 2, stroke / 2), size = body, cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(stroke))
        drawRoundRect(
            outline,
            topLeft = Offset(body.width + stroke / 2, size.height * 0.3f),
            size = Size(nub, size.height * 0.4f),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        val inset = stroke * 2
        val w = (body.width - inset * 2) * (percent.coerceIn(0, 100) / 100f)
        drawRoundRect(fill, topLeft = Offset(stroke / 2 + inset, stroke / 2 + inset), size = Size(w, body.height - inset * 2), cornerRadius = CornerRadius(1.5.dp.toPx()))
    }
}

/** A water drop drawn with a path, used as the app's mark. */
@Composable
private fun WaterDrop(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.5f, 0f)
            cubicTo(w * 0.5f, 0f, w * 0.08f, h * 0.48f, w * 0.08f, h * 0.66f)
            cubicTo(w * 0.08f, h * 0.88f, w * 0.27f, h, w * 0.5f, h)
            cubicTo(w * 0.73f, h, w * 0.92f, h * 0.88f, w * 0.92f, h * 0.66f)
            cubicTo(w * 0.92f, h * 0.48f, w * 0.5f, 0f, w * 0.5f, 0f)
            close()
        }
        drawPath(p, color)
        drawCircle(Color.White.copy(alpha = 0.45f), radius = w * 0.09f, center = Offset(w * 0.36f, h * 0.68f))
    }
}

// ---------------------------------------------------------------------------

private fun relative(time: Long, now: Long): String =
    if (now - time < 60_000) "just now"
    else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun prettyBottleName(name: String?): String =
    name?.replace("WaterH-", "WaterH ")?.replace("-", " ") ?: "WaterH bottle"

private fun signalLabel(rssi: Int): String = when {
    rssi > -60 -> "strong"
    rssi > -75 -> "good"
    else -> "weak"
}
