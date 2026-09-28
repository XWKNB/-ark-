# 🧊 冰川时代大冒险 ARK 工具

> Android 端 **ARK 资源包** 解包 / 打包工具，专为《冰川时代大冒险》设计。
> 使用 **XXTEA** 加密 + **zlib** 压缩，支持 **无损复制** 未修改的文件。

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![IDE](https://img.shields.io/badge/IDE-CodeAssist-orange.svg)](https://github.com/tyron12233/CodeAssist)

---

## ✨ 功能特性

| 功能 | 说明 |
|------|------|
| 📦 **解包** | 解密 `.ark` 资源包，导出所有文件 + `_ark_manifest.json` 清单 |
| 🔒 **打包** | 将修改后的目录重新打包成 `.ark`，**未改动的文件从原包无损复制** |
| 🎯 **无损复制** | 只重新压缩/加密你修改过的文件，其余字节原样搬运 |
| 💾 **路径记忆** | 自动保存上次输入的 4 个路径，重启应用不丢失 |
| 📊 **实时进度** | 显示百分比、当前正在处理的文件名 |
| 🔑 **权限引导** | 一键跳转系统设置申请「所有文件访问权限」 |
| 🌊 **流式处理** | 大文件流式读写，避免内存翻倍导致 OOM |

---

## 📸 界面预览

```
┌─────────────────────────────────────┐
│  ⚙️ ARK 资源包工具                  │
│  冰川时代大冒险 · XXTEA 解密/打包    │
├─────────────────────────────────────┤
│  🔑 存储权限                         │
│  ✅ 已获得存储权限                   │
├─────────────────────────────────────┤
│  📦 解包                             │
│  [ ARK 文件路径         ]            │
│  [ 输出文件夹路径       ]            │
│  [ ▶ 开始解包          ]            │
├─────────────────────────────────────┤
│  🔒 打包                             │
│  [ 解包目录路径         ]            │
│  [ 原始 ARK 路径(必填)  ]            │
│  [ 输出 ARK 文件路径    ]            │
│  [ 🔨 开始打包          ]            │
├─────────────────────────────────────┤
│  📊 处理进度: 62%  (1240/2000)      │
│  ████████████░░░░░░░░               │
│  当前: assets/models/hero.bin        │
└─────────────────────────────────────┘
```

---

## 📱 技术栈

- **语言**：Kotlin
- **UI**：Jetpack Compose + Material 3
- **加密**：XXTEA（128-bit key）
- **压缩**：zlib（`Deflater` / `Inflater`）
- **存储**：SharedPreferences（路径记忆）
- **大文件 IO**：`RandomAccessFile` + `BufferedOutputStream`
- **构建**：Android SDK 36 / minSdk 21 / targetSdk 34

---

## 💻 开发环境

本项目使用 **CodeAssist** 开发（Android 端 IDE）。

- **IDE**：[CodeAssist](https://github.com/tyron12233/CodeAssist)（Android 上的 Java/Kotlin IDE）
- **构建系统**：`module.toml`（CodeAssist 的项目配置格式）
- **开发设备**：Android 手机 / 平板
- **无需 Android Studio**：整个项目可在手机上完成编码、编译、调试

---

## 🔧 使用流程

### 📦 解包

1. 填写 **ARK 文件路径**，例如 `/storage/emulated/0/game.ark`
2. 填写 **输出文件夹路径**，例如 `/storage/emulated/0/ark_unpacked`
3. 点击 **▶ 开始解包**
4. 输出目录会生成 `_ark_manifest.json`，记录每个文件的原始信息：

   ```json
   {
     "ark_version": "1.6",
     "count": 2000,
     "source_ark": "ark_unpacked",
     "entries": [
       {
         "name": "assets/config.xml",
         "offset": 16,
         "orig": 4096,
         "comp": 1536,
         "stored": 1552,
         "encrypted": true,
         "tag": "01020304",
         "hash": "a1b2c3d4e5f6a7b8",
         "md5_plain": "d41d8cd98f00b204e9800998ecf8427e"
       }
     ]
   }
   ```

### 🔒 打包（无损复制）

1. 填写 **解包目录路径**（含 `_ark_manifest.json`）
2. 填写 **原始 ARK 路径**（**必填**，用于无损复制）
3. 填写 **输出 ARK 文件路径**，例如 `/storage/emulated/0/repacked.ark`
4. 点击 **🔨 开始打包**

> ⚠️ **原始 ARK 必填**：只有提供原始包，工具才能比对 MD5，只重压改动过的文件，其余字节原样拷贝。这样打包既快又能保证未修改内容的**字节级一致**。

---

## 📂 项目结构

```
app/
├── src/main/
│   ├── kotlin/com/xwk/glacierark/
│   │   ├── MainActivity.kt      # Compose UI 主界面
│   │   ├── ArkEngine.kt         # XXTEA + zlib + ARK 解析
│   │   ├── ArkUtils.kt          # 解包/打包/流式 IO/manifest
│   │   └── ArkModels.kt         # 数据类
│   ├── res/
│   │   ├── drawable/            # 图标矢量
│   │   ├── values/              # 字符串、颜色、主题
│   │   └── mipmap-*/            # 启动图标
│   └── AndroidManifest.xml
├── module.toml                  # CodeAssist 模块配置
└── proguard-rules.pro
```

---

## 🧠 核心算法

### XXTEA 加解密

```
DELTA = 0x9E3779B9
KEY   = (0x0132944F, 0x00025BA1, 0x0132944F, 0x009988B5)
```

- 用于解密目录区、加密文件内容
- 完整实现，与原 Python 工具**完全兼容**

### ARK 文件结构

```
[0x00]  uint32   count        条目总数
[0x04]  uint32   dir_offset   目录区偏移
[0x08]  8 bytes  version      版本字符串（如 "1.6"）
[0x10]           data area    数据区（每个条目连续存放）
[...]            dir area     目录区（XXTEA 加密，168 字节/条目）
```

每条目录项 **168 字节**：

| 偏移  | 大小 | 含义                          |
|-------|------|-------------------------------|
| 0x00  | 128  | 文件名（`\0` 结尾）            |
| 0x80  | 4    | 数据区偏移                    |
| 0x84  | 4    | 原始大小                      |
| 0x88  | 4    | 压缩后大小                    |
| 0x8C  | 4    | 存储大小（加密后，0=未加密）   |
| 0x90  | 4    | tag                           |
| 0x94  | 16   | MD5 前 16 字符                |
| 0xA4  | 4    | 填充                          |

### 数据流

```
解包流程:
  ark 文件 → 读取目录区 → XXTEA 解密 → 解析条目
  → 按条目逐个读取数据 → XXTEA 解密 → zlib 解压 → 写入文件

打包流程:
  读取 manifest → 遍历目录文件 → 比对 MD5
    ├─ 未改动 → 从原 ark 无损复制原始字节
    └─ 已改动 → zlib 压缩 → XXTEA 加密 → 写盘
  → 构建目录区 → XXTEA 加密 → 追加到文件尾
```

---

## ⚡ 性能优化

- **流式打包**：`RandomAccessFile` 直接写盘，避免 `ByteArrayOutputStream` 内存翻倍
- **无损复制**：未改动文件用 **64KB 缓冲**直接从原包拷贝，不重新压缩
- **压缩等级 6**：比 level 9 快 **3 倍**，体积仅大 **2%**
- **1MB 输出缓冲**：减少系统调用，大文件尤其明显
- **MD5 只算一次**：避免重复遍历
- **分块 manifest 解析**：避免大正则回溯
- **`largeHeap`**：Android 堆上限提升到 512MB
- **函数拆分**：压缩/加密拆到独立函数，中间变量尽早被 GC 回收

---

## ⚠️ 已知限制

- 单个超大文件（> 200MB）在低端手机上仍可能 OOM
- 需要「**所有文件访问权限**」（Android 11+）才能读写公共目录
- 未提供原 ARK 时**无法打包**（保证无损的前提）

---

## 🛠️ 构建

### 使用 CodeAssist（推荐，手机端）

1. 在 Android 设备上安装 [CodeAssist](https://github.com/tyron12233/CodeAssist)
2. 用 CodeAssist 打开本仓库的源码目录
3. CodeAssist 会读取 `module.toml` 自动加载项目
4. 点击工具栏的 **Build** 按钮编译
5. 生成的 APK 在 `app/build/outputs/apk/debug/` 下

### 使用 MT 管理器（替代方案）

1. 用 MT 管理器打开项目目录
2. 进入 **AI 做应用** → 打开项目 → **构建**
3. 生成 APK

**最低要求**：Android 5.0 (API 21)

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request。

1. Fork 本项目
2. 创建分支：`git checkout -b feature/xxx`
3. 提交修改：`git commit -m 'feat: 添加 xxx'`
4. 推送分支：`git push origin feature/xxx`
5. 提交 Pull Request

---

## 📜 免责声明

本工具仅供 **学习研究** 使用。

请勿用于商业用途或侵犯游戏厂商版权的行为。因使用本工具产生的任何后果，作者不承担任何责任。

---

## 📄 License

[Apache License 2.0](LICENSE)

Copyright 2026 XWKNB

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

---

<p align="center">
  <sub>Made with ❤️ on Android with CodeAssist</sub>
</p>