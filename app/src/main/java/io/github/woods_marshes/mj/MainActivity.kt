package io.github.woods_marshes.mj

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.woods_marshes.mj.service.MjAccessibilityService
import io.github.woods_marshes.mj.ui.theme.MjTheme
import io.github.woods_marshes.mj.view.MjAnimationView
import androidx.core.content.edit
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MjTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember {
        context.getSharedPreferences(MjAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var soundEnabled by remember { mutableStateOf(prefs.getBoolean(MjAccessibilityService.KEY_SOUND_ENABLED, true)) }
    var preferSw by remember { mutableStateOf(prefs.getBoolean(MjAnimationView.KEY_PREFER_SW, false)) }
    var testAnimationAsset by remember { mutableStateOf<String?>(null) }
    var showDisclaimer by remember {
        mutableStateOf(!prefs.getBoolean(MjAccessibilityService.KEY_DISCLAIMER_AGREED, false))
    }

    // 从系统设置返回时自动刷新服务开启状态与解码器偏好
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = isAccessibilityServiceEnabled(context)
                preferSw = prefs.getBoolean(MjAnimationView.KEY_PREFER_SW, false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 无障碍服务状态卡
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp, horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.status_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            if (serviceEnabled) R.string.status_on else R.string.status_off
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (serviceEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) {
                        Text(text = stringResource(R.string.btn_enable_accessibility))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 动画与音效卡
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.pref_sound_label),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = soundEnabled,
                            onCheckedChange = {
                                soundEnabled = it
                                prefs.edit {
                                    putBoolean(MjAccessibilityService.KEY_SOUND_ENABLED, it)
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.decoder_label),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = !preferSw,
                            onClick = {
                                preferSw = false
                                prefs.edit {
                                    putBoolean(MjAnimationView.KEY_PREFER_SW, false)
                                }
                            },
                            label = { Text(text = stringResource(R.string.decoder_hw)) }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = preferSw,
                            onClick = {
                                preferSw = true
                                prefs.edit {
                                    putBoolean(MjAnimationView.KEY_PREFER_SW, true)
                                }
                            },
                            label = { Text(text = stringResource(R.string.decoder_sw)) }
                        )
                    }
                    Text(
                        text = stringResource(R.string.decoder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { testAnimationAsset = MjAnimationView.nextAsset() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.btn_test_animation))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 后台保活卡（非原生系统）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.miui_suggestion_text),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { openMiuiAutoStart(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.btn_miui_autostart))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { openMiuiBatterySettings(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.btn_miui_battery))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.main_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.github_repo_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { openGitHub(context) }
            )

            Spacer(Modifier.height(12.dp))
        }
    }

    // 测试动画：不依赖无障碍服务，直接在本应用界面内播放
    testAnimationAsset?.let { asset ->
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MjAnimationView(
                        ctx,
                        animationAsset = asset,
                        playSound = soundEnabled,
                        onFinished = { testAnimationAsset = null }
                    )
                }
            )
        }
    }

    // 首次启动免责声明，确认后不再显示
    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { /* 必须点击同意才能关闭 */ },
            title = { Text(text = stringResource(R.string.dialog_disclaimer_title)) },
            text = {
                Text(
                    text = stringResource(R.string.dialog_disclaimer_text),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit {
                        putBoolean(MjAccessibilityService.KEY_DISCLAIMER_AGREED, true)
                    }
                    showDisclaimer = false
                }) {
                    Text(text = stringResource(R.string.dialog_disclaimer_confirm))
                }
            }
        )
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val component = ComponentName(context, MjAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(':').any {
        it.equals(component.flattenToString(), ignoreCase = true) ||
            it.equals(component.flattenToShortString(), ignoreCase = true)
    }
}

private fun openGitHub(context: Context) {
    context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/woods-marshes/MJ".toUri()))
}

private fun openAppDetailsSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
    )
}

/** MIUI 自启动管理页；非 MIUI 系统回退到应用详情页。 */
private fun openMiuiAutoStart(context: Context) {
    val miui = Intent().setComponent(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
    )
    runCatching { context.startActivity(miui) }
        .onFailure { openAppDetailsSettings(context) }
}

/** MIUI 省电策略（后台弹出/锁屏后台运行）页；非 MIUI 系统回退到应用详情页。 */
private fun openMiuiBatterySettings(context: Context) {
    val label = runCatching {
        context.packageManager.getApplicationInfo(context.packageName, 0)
            .loadLabel(context.packageManager).toString()
    }.getOrDefault(context.packageName)
    val miui = Intent().setComponent(
        ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfig")
    ).putExtra("package_name", context.packageName)
        .putExtra("package_label", label)
    runCatching { context.startActivity(miui) }
        .onFailure { openAppDetailsSettings(context) }
}
