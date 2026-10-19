package com.leakproof.app.ui.screens

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.BuildConfig
import com.leakproof.app.security.TrustCheck
import com.leakproof.app.security.TrustReport
import com.leakproof.app.ui.components.AppCard
import com.leakproof.app.ui.theme.BrandPrimary
import com.leakproof.app.ui.viewmodel.LeakproofViewModel

private val Amber = Color(0xFFF5A623)

/** What this installed copy of the app can and cannot do, read from the phone rather than claimed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustCenterScreen(viewModel: LeakproofViewModel, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val facts = remember { readFacts(context) }
    val checks = remember(facts) {
        TrustReport.build(facts.permissions, facts.allowBackup, facts.debuggable, BuildConfig.ALLOW_SCREEN_CAPTURE)
    }
    val records by viewModel.allSubscriptions.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Trust Center", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("trust_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .testTag("trust_center")
        ) {
            Text(
                "Don't take our word for it. These checks are read from your phone about this exact copy of Leakproof.",
                fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    checks.forEach { CheckRow(it) }
                }
            }

            Spacer(Modifier.height(16.dp))
            AppCard {
                Column(Modifier.padding(16.dp)) {
                    Text("Try it yourself", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Switch on airplane mode, then import a statement or screenshot. It works exactly the same, because nothing here ever needed a connection.",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                        modifier = Modifier.fillMaxWidth().testTag("trust_airplane_button")
                    ) { Text("Open airplane mode settings") }
                }
            }

            Spacer(Modifier.height(16.dp))
            AppCard {
                Column(Modifier.padding(16.dp)) {
                    Text("What is stored", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "${records.size} record${if (records.size == 1) "" else "s"} on this phone, encrypted.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )
                    TrustReport.STORED.forEach { Bullet("✓", it, BrandPrimary) }
                    Spacer(Modifier.height(10.dp))
                    Text("Never stored", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    TrustReport.NEVER_STORED.forEach { Bullet("✕", it, MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            Spacer(Modifier.height(16.dp))
            AppCard {
                Column(Modifier.padding(16.dp)) {
                    Text("This build", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    Text("Version ${facts.versionName}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("Signing certificate (SHA-256)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        facts.fingerprint ?: "Unavailable",
                        fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp).testTag("trust_fingerprint")
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Permissions this app asks for", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (facts.permissions.isEmpty()) "None" else facts.permissions.joinToString("\n") { TrustReport.friendly(it) },
                        fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp).testTag("trust_permissions")
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CheckRow(check: TrustCheck) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = if (check.passed) Icons.Default.CheckCircle else Icons.Default.Info,
            contentDescription = if (check.passed) "Passed" else "Note",
            tint = if (check.passed) BrandPrimary else Amber,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(check.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(check.detail, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun Bullet(mark: String, text: String, markColor: Color) {
    Row(Modifier.padding(bottom = 4.dp)) {
        Text(mark, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = markColor, modifier = Modifier.width(20.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

private class Facts(
    val permissions: List<String>,
    val allowBackup: Boolean,
    val debuggable: Boolean,
    val versionName: String,
    val fingerprint: String?
)

@Suppress("DEPRECATION")
private fun readFacts(context: android.content.Context): Facts {
    val pm = context.packageManager
    val pkg = context.packageName
    val flags = context.applicationInfo.flags
    val permissions = runCatching {
        pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList().orEmpty()
    }.getOrDefault(emptyList())
    val version = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull().orEmpty()
    val cert = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            (info?.apkContentsSigners ?: info?.signingCertificateHistory)?.firstOrNull()?.toByteArray()
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
        }
    }.getOrNull()
    return Facts(
        permissions = permissions,
        allowBackup = flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0,
        debuggable = flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        versionName = version,
        fingerprint = cert?.let { TrustReport.fingerprint(it) }
    )
}
