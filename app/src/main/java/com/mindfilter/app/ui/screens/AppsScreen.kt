package com.mindfilter.app.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfilter.app.data.AppSettings
import com.mindfilter.app.data.DEFAULT_MONITORED
import com.mindfilter.app.ui.GlassCard
import com.mindfilter.app.ui.ScreenTitle
import com.mindfilter.app.ui.SectionTitle
import com.mindfilter.app.ui.theme.LocalGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val pkg: String, val label: String, val icon: ImageBitmap?)

private fun loadApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val infos = try {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    } catch (e: Exception) {
        emptyList()
    }
    return infos
        .map { it.activityInfo }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .map { ai ->
            val icon = try {
                ai.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
            InstalledApp(ai.packageName, ai.loadLabel(pm).toString(), icon)
        }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun AppsScreen() {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val s by AppSettings.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<InstalledApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadApps(ctx) }
    }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenTitle("Applications", "MindFilter n'analyse que les applis cochées.") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Rechercher une application", color = g.textSecondary) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = g.textSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = g.text,
                    unfocusedTextColor = g.text,
                    focusedBorderColor = g.accent,
                    unfocusedBorderColor = g.chip,
                ),
            )
        }
        val list = apps
        if (list == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = g.accent)
                }
            }
        } else {
            val filtered = list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            val monitored = filtered.filter { it.pkg in s.monitoredPackages }
            val suggested = filtered.filter { it.pkg !in s.monitoredPackages && it.pkg in DEFAULT_MONITORED }
            val others = filtered.filter { it.pkg !in s.monitoredPackages && it.pkg !in DEFAULT_MONITORED }
            if (monitored.isNotEmpty()) {
                item { SectionTitle("Surveillées (${monitored.size})") }
                items(monitored, key = { "m" + it.pkg }) { AppRow(it, true) }
            }
            if (suggested.isNotEmpty()) {
                item { SectionTitle("Suggérées") }
                items(suggested, key = { "s" + it.pkg }) { AppRow(it, false) }
            }
            if (others.isNotEmpty()) {
                item { SectionTitle("Autres applications") }
                items(others, key = { "o" + it.pkg }) { AppRow(it, false) }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, monitored: Boolean) {
    val g = LocalGlass.current
    GlassCard(corner = 22.dp, padding = 12.dp, onClick = { AppSettings.setPackageMonitored(app.pkg, !monitored) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (app.icon != null) {
                Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            } else {
                Box(Modifier.size(40.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                app.label,
                color = g.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = monitored,
                onCheckedChange = { AppSettings.setPackageMonitored(app.pkg, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = g.positive, checkedThumbColor = Color.White),
            )
        }
    }
}
