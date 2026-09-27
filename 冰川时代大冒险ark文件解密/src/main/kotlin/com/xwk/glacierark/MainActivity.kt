package com.xwk.glacierark

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val PREFS_NAME = "ark_tool_prefs"
private const val KEY_EXTRACT_ARK = "extract_ark_path"
private const val KEY_EXTRACT_OUT = "extract_out_dir"
private const val KEY_PACK_SRC = "pack_src_dir"
private const val KEY_PACK_ORIGINAL = "pack_original_ark"
private const val KEY_PACK_OUT = "pack_out_ark"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { ArkToolScreen() } }
    }
}

@Composable
fun ArkToolScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val prefs = remember { ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var hasStoragePermission by remember { mutableStateOf(checkStoragePermission(ctx)) }
    var permissionMsg by remember { mutableStateOf("") }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasStoragePermission = checkStoragePermission(ctx)
        permissionMsg = if (hasStoragePermission) "✅ 权限已授予" else "⚠️ 权限未授予"
    }

    val legacyPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasStoragePermission = result.values.all { it }
        permissionMsg = if (hasStoragePermission) "✅ 权限已授予" else "⚠️ 用户拒绝"
    }

    LaunchedEffect(Unit) {
        hasStoragePermission = checkStoragePermission(ctx)
    }

    var status by remember { mutableStateOf("请输入路径后点击按钮") }
    var entries by remember { mutableStateOf<List<ArkEntry>>(emptyList()) }
    var header by remember { mutableStateOf<ArkHeader?>(null) }

    var busy by remember { mutableStateOf(false) }
    var progressCurrent by remember { mutableStateOf(0) }
    var progressTotal by remember { mutableStateOf(0) }
    var currentFile by remember { mutableStateOf("") }

    var extractArkPath by remember { mutableStateOf(prefs.getString(KEY_EXTRACT_ARK, "") ?: "") }
    var extractOutDir by remember { mutableStateOf(prefs.getString(KEY_EXTRACT_OUT, "") ?: "") }
    var packSrcDir by remember { mutableStateOf(prefs.getString(KEY_PACK_SRC, "") ?: "") }
    var packOriginalArk by remember { mutableStateOf(prefs.getString(KEY_PACK_ORIGINAL, "") ?: "") }
    var packOutArk by remember { mutableStateOf(prefs.getString(KEY_PACK_OUT, "") ?: "") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("⚙️ ARK 资源包工具", fontSize = 24.sp)
        Text(
            "冰川时代大冒险 · XXTEA 解密/打包",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.outline
        )

        HorizontalDivider()

        // ============ 权限区 ============
        Text("🔑 存储权限", fontSize = 16.sp)
        Surface(
            color = if (hasStoragePermission)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (hasStoragePermission) "✅ 已获得存储权限" else "⚠️ 未获得存储权限",
                fontSize = 13.sp,
                modifier = Modifier.padding(10.dp)
            )
        }
        if (!hasStoragePermission) {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:${ctx.packageName}")
                            manageStorageLauncher.launch(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            manageStorageLauncher.launch(intent)
                        }
                    } else {
                        legacyPermLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("🔑 申请存储权限") }
        }
        if (permissionMsg.isNotEmpty()) {
            Text(permissionMsg, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }

        HorizontalDivider()

        // ============ 解包区 ============
        Text("📦 解包", fontSize = 18.sp)
        OutlinedTextField(
            value = extractArkPath,
            onValueChange = {
                extractArkPath = it
                prefs.edit().putString(KEY_EXTRACT_ARK, it).apply()
            },
            label = { Text("ARK 文件路径") },
            placeholder = { Text("/storage/emulated/0/xxx.ark") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = extractOutDir,
            onValueChange = {
                extractOutDir = it
                prefs.edit().putString(KEY_EXTRACT_OUT, it).apply()
            },
            label = { Text("输出文件夹路径") },
            placeholder = { Text("/storage/emulated/0/ark_unpacked") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                if (!hasStoragePermission) {
                    status = "请先申请存储权限"; return@Button
                }
                val arkFile = File(extractArkPath.trim())
                val outDir = File(extractOutDir.trim())
                if (!arkFile.exists() || !arkFile.isFile) {
                    status = "错误: ARK 文件不存在\n${arkFile.absolutePath}"; return@Button
                }
                if (extractOutDir.isBlank()) {
                    status = "错误: 请填写输出文件夹路径"; return@Button
                }

                busy = true; progressCurrent = 0; progressTotal = 0; currentFile = ""
                status = "正在解包..."

                scope.launch(Dispatchers.IO) {
                    try {
                        val blob = arkFile.readBytes()
                        val (h, list, _) = ArkEngine.readArk(blob)

                        @Suppress("UNUSED_EXPRESSION")
                        blob
                        System.gc()

                        outDir.mkdirs()

                        var lastUpdate = 0L
                        ArkUtils.writeUnpackedFromFile(arkFile, outDir, h, list) { cur, tot, name ->
                            val now = System.currentTimeMillis()
                            if (cur == tot || now - lastUpdate > 200) {
                                lastUpdate = now
                                scope.launch(Dispatchers.Main) {
                                    progressCurrent = cur
                                    progressTotal = tot
                                    currentFile = name
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            header = h
                            entries = list
                            status = "解包完成!\n版本: ${h.version}\n文件数: ${list.size}\n输出: ${outDir.absolutePath}"
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            status = "解包失败: ${e.message}"
                        }
                    } finally {
                        withContext(Dispatchers.Main) { busy = false }
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "⏳ 正在处理..." else "▶ 开始解包") }

        HorizontalDivider()

        // ============ 打包区 ============
        Text("🔒 打包", fontSize = 18.sp)
        OutlinedTextField(
            value = packSrcDir,
            onValueChange = {
                packSrcDir = it
                prefs.edit().putString(KEY_PACK_SRC, it).apply()
            },
            label = { Text("解包目录路径 (含 _ark_manifest.json)") },
            placeholder = { Text("/storage/emulated/0/ark_unpacked") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = packOriginalArk,
            onValueChange = {
                packOriginalArk = it
                prefs.edit().putString(KEY_PACK_ORIGINAL, it).apply()
            },
            label = { Text("原始 ARK 路径 (必填，用于无损复制)") },
            placeholder = { Text("/storage/emulated/0/xxx.ark") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = packOutArk,
            onValueChange = {
                packOutArk = it
                prefs.edit().putString(KEY_PACK_OUT, it).apply()
            },
            label = { Text("输出 ARK 文件路径") },
            placeholder = { Text("/storage/emulated/0/repacked.ark") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                if (!hasStoragePermission) {
                    status = "请先申请存储权限"; return@Button
                }
                // ★ 原 ARK 必填
                if (packOriginalArk.isBlank()) {
                    status = "错误: 原始 ARK 路径为必填项"; return@Button
                }
                val srcDir = File(packSrcDir.trim())
                val outArk = File(packOutArk.trim())
                val origArk = File(packOriginalArk.trim())

                if (!srcDir.exists() || !srcDir.isDirectory) {
                    status = "错误: 解包目录不存在\n${srcDir.absolutePath}"; return@Button
                }
                if (packOutArk.isBlank()) {
                    status = "错误: 请填写输出 ARK 文件路径"; return@Button
                }
                if (!origArk.exists() || !origArk.isFile) {
                    status = "错误: 原始 ARK 文件不存在\n${origArk.absolutePath}"; return@Button
                }

                busy = true; progressCurrent = 0; progressTotal = 0; currentFile = ""
                status = "正在打包（启用无损复制）..."

                scope.launch(Dispatchers.IO) {
                    try {
                        val mf = File(srcDir, "_ark_manifest.json")
                        if (!mf.exists()) {
                            withContext(Dispatchers.Main) {
                                status = "错误: 目录里找不到 _ark_manifest.json"
                            }
                            return@launch
                        }
                        val version = Regex(""""ark_version"\s*:\s*"([^"]+)"""")
                            .find(mf.readText())?.groupValues?.get(1) ?: "1.6"

                        outArk.parentFile?.mkdirs()

                        var lastUpdate = 0L
                        val n = ArkUtils.packArk(srcDir, outArk, origArk, version) { cur, tot, name ->
                            val now = System.currentTimeMillis()
                            if (cur == tot || now - lastUpdate > 200) {
                                lastUpdate = now
                                scope.launch(Dispatchers.Main) {
                                    progressCurrent = cur
                                    progressTotal = tot
                                    currentFile = name
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            status = "打包完成（无损复制已启用）!\n条目: $n\n输出: ${outArk.absolutePath}\n(${outArk.length()} 字节)"
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            status = "打包失败: ${e.message}"
                        }
                    } finally {
                        withContext(Dispatchers.Main) { busy = false }
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "⏳ 正在处理..." else "🔨 开始打包") }

        HorizontalDivider()

        // ============ 进度区 ============
        if (busy || progressTotal > 0) {
            val percent = if (progressTotal > 0) (progressCurrent * 100 / progressTotal) else 0
            Text("处理进度: $percent%  ($progressCurrent / $progressTotal)", fontSize = 13.sp)
            LinearProgressIndicator(
                progress = { if (progressTotal > 0) progressCurrent.toFloat() / progressTotal else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            if (currentFile.isNotEmpty()) {
                Text(
                    "当前: $currentFile",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            HorizontalDivider()
        }

        // ============ 状态区 ============
        Text("状态:", fontSize = 14.sp)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                status,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(12.dp)
            )
        }

        if (entries.isNotEmpty()) {
            Text("文件清单 (${entries.size}):", fontSize = 14.sp)
            entries.take(50).forEachIndexed { i, e ->
                Text(
                    "${i + 1}. ${e.name}  [${e.orig}B]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            if (entries.size > 50) {
                Text(
                    "... 共 ${entries.size} 个文件",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

fun checkStoragePermission(ctx: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}