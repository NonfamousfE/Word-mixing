# 单词混记 (WordMix)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](android/)
[![FastAPI](https://img.shields.io/badge/FastAPI-2.0.0-009688.svg)](server/)
[![Vue 3](https://img.shields.io/badge/Vue-3.5-4FC08D.svg)](web/)
[![Release](https://img.shields.io/badge/Release-v1.1.2-orange.svg)](https://github.com/NonfamousfE/Word-mixing/releases)

> 专为**易混淆英语单词**设计的现代记忆、检索与自测工具。  
> 采用 **Android 原生移动端 + Web 响应式网页端 + FastAPI 云端服务** 的现代全栈架构。支持抗遗忘遮挡自测、音标与 TTS 朗读、离线优先增量同步与应用内一键自更新。

---

## 📑 快速导航

- 📱 **新手机用户**：👉 [**《安卓端快速使用说明书》**](docs/安卓端快速使用说明书.md)（1分钟完成下载安装、配网拉取云端全量词库与背诵指南）
- 🛠️ **开发者 / 新人 / AI Agent**：👉 [**《项目开发与架构说明文档》**](docs/项目开发与架构说明文档.md)（业务全景、双轨同步机制、代码地图与防踩坑守则）
- 🤝 **参与贡献**：👉 [**《贡献指南 (CONTRIBUTING)》**](CONTRIBUTING.md)
- 📜 **开源许可**：👉 [**MIT License**](LICENSE)

---

## 🌟 核心特性与设计哲学

### 1. 解决混淆词核心痛点
* **告别单调词表**：传统背单词软件按词频或字母流式排列，容易混淆的词散落各处。WordMix 以**「易混词组」**为核心单元（例如 `plague / plight`、`contract / contact / contrast`、`moral / morale / mortal`）。
* **智能相近词推荐**：录入新词时，系统基于 Levenshtein 编辑距离自动计算词库中的形近词，支持一键归入同组。

### 2. 抗遗忘遮挡自测（Flashcard Recall）
卡片默认隐藏中文释义，模拟真实背诵回忆场景：

```
┌──────────────────────────────────────────────┐
│  clause    /klɔːz/                           │  ← 单词（加粗）+ 国际音标
│  ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬                        │  ← 默认灰色遮挡条（在大脑中回忆释义）
│  ··········································  │  ← 【点击遮挡条】立即揭开：
│  clause    /klɔːz/                           │
│  n. 条款；从句                                │  ← 显示中文释义（再次点击可重新遮挡）
└──────────────────────────────────────────────┘
```

### 3. 双端覆盖与极致轻量
* **Android 原生端**：
  * **体积极致精简**：安装包仅 **2.4 MB**。
  * **零三方重依赖**：不依赖 Compose/Retrofit/OkHttp 等重型框架，纯 Kotlin + 原生 View 构建，毫秒级启动，顺滑跟手。
  * **系统原生发音**：调用 Android 原生 Text-to-Speech (TTS) 朗读。
  * **应用内一键自更新**：内置版本探测、Range 断点续传下载、SHA256 完整性校验与系统覆盖安装，告别手动拷贝 APK。
* **Web 响应式网页端**：
  * 基于 **Vue 3 + Vite + TypeScript** 构建，现代毛玻璃卡片风格，支持多端浏览器秒级访问。
  * 支持 Web Speech API 语音合成朗读与实时模糊过滤。

### 4. 离线优先 (Offline-First) 与双轨增量同步
* **事件溯源（Op-Log）**：客户端每次修改生成带有时钟版本与全局唯一 `op_id` 的原子操作，即使在地铁、弱网或飞行模式下也能离线记词，连网后无感追更。
* **墓碑机制 (Tombstone)**：删除操作严格打标 `deleted: true`，杜绝离线设备重新连网时将已删单词“复活”。
* **双轨兼容架构**：FastAPI 服务端通过 `sync_compat_routes.py` 桥接，Web 端通过 RESTful API 修改的同时自动生成增量日志，与移动端的增量同步协议实时双向互通。

---

## 🏗️ 系统架构与目录结构

```
单词混记 (WordMix)
├── android/                   # Android 原生应用 (Kotlin, 原生View, 零三方框架)
│   └── app/src/main/kotlin/com/wordmix/app/
│       ├── MainActivity.kt    # 主界面交互与分组筛选
│       ├── CardAdapter.kt     # 卡片适配器、自测遮挡与发音
│       ├── Library.kt         # 内存模型与增量日志重放
│       ├── Syncer.kt          # HttpURLConnection 增量同步客户端
│       ├── Updater.kt         # 应用内自更新逻辑
│       └── Store.kt           # 本地原子持久化落盘
├── web/                       # Web 响应式单页应用 (Vue 3 + Vite + TS)
│   ├── src/App.vue            # Web 端单页应用全交互
│   └── src/api.ts             # RESTful API 客户端
├── server/                    # 云端服务 (FastAPI + SQLAlchemy + SQLite)
│   ├── main.py                # 路由入口、APK 分发、SPA 托管与热更新
│   ├── models.py              # 数据模型 (User, Group, WordEntry)
│   ├── routers/               # REST API 与增量同步兼容路由
│   └── static/                # Web 前端构建产物托管目录
├── build/                     # 测试与自动化部署脚本
│   ├── run-all-tests.py       # 一键服务端全量测试
│   └── server/upload-bundle.py# 免终端代码热更新脚本
├── docs/                      # 核心项目文档
│   ├── 安卓端快速使用说明书.md  # 手机端新机配置使用说明
│   └── 项目开发与架构说明文档.md # 开发者必读的架构与开发手册
├── start-server.py            # 本地一键启动脚本 (服务 + 浏览器打开 Web)
└── dist/                      # 发布产物 (单词混记.apk)
```

---

## 🚀 快速上手与运行指南

### 1. 手机端使用（新机用户）
1. 打开安卓手机浏览器，访问下载最新版 APK（或通过电脑安装包传输）：  
   👉 `http://<你的服务器地址>:18080/app/download/wordmix.apk`  
   *(注：仓库根目录 `dist/单词混记.apk` 亦提供最新版安装包)*
2. 安装后打开 App，点击右上角 **「设置」** 填入服务器地址与同步 Token。
3. 点击 **「立即同步」**，秒级拉取云端全部词库开启背诵！  
   *(详细步骤请参阅 [《安卓端快速使用说明书》](docs/安卓端快速使用说明书.md))*

### 2. 电脑本地全栈启动
电脑只需装有 Python 3.10+，在项目根目录运行：
```powershell
python start-server.py
```
* **Web 网页端**：服务启动后会自动唤起浏览器打开 `http://localhost:8000`
* **交互式 API 文档**：访问 `http://localhost:8000/docs`
* **默认演示账号**：`admin / admin123`

### 3. Web 前端独立开发（热重载）
```powershell
cd web
npm install
npm run dev
# 前端构建生成至 server/static/：
npm run build
```

### 4. 安卓端构建与单元测试
> ⚠ **避坑关键**：由于 Windows 中文路径会导致 Gradle worker 在生成 classpath 时触发乱码，必须使用纯 ASCII 路径进行构建！

```powershell
# 1. 镜像同步到纯 ASCII 目录
robocopy "android" "C:\wm-app" /MIR /NFL /NDL /NJH /NJS /NP

# 2. 运行纯 JVM 单元测试（无需模拟器）
cd C:\wm-app
& "%USERPROFILE%\.gradle\wrapper\dists\gradle-8.12-all\ejduaidbjup3bmmkhw3rie4zb\gradle-8.12\bin\gradle.bat" --no-daemon :app:testDebugUnitTest

# 3. 构建发布版 APK
& "%USERPROFILE%\.gradle\wrapper\dists\gradle-8.12-all\ejduaidbjup3bmmkhw3rie4zb\gradle-8.12\bin\gradle.bat" --no-daemon :app:assembleRelease
```

---

## 🛠️ 自动化运维与发布

* **自动化测试**：
  ```powershell
  python build/run-all-tests.py
  ```
* **一键无感热更新云服务器**（免 SSH 登录终端）：
  ```powershell
  python build/server/upload-bundle.py
  ```
* **上传并分发新版 Android APK**：
  ```powershell
  python build/server/upload-apk.py "C:\wm-app\app\build\outputs\apk\release\app-release.apk"
  ```

---

## ⚖️ 核心开发规范（必读铁律）

> 📢 **所有接手开发者 / AI Agent 必须遵守**：  
> **代码与文档必须严格保持同步更新！**

在本项目中，文档不仅仅是说明书，更是新人上手与多端协同的关键基石。凡涉及以下改动，**必须同步更新对应文档，禁止只改代码不改文档**：

1. **接口或架构调整**（如增删 API、调整数据字段、修改同步逻辑）：
   - 必须同步更新 [`docs/项目开发与架构说明文档.md`](docs/项目开发与架构说明文档.md) 中的架构图、协议说明与代码地图。
2. **手机端操作或功能变化**（如新增功能按钮、调整自测交互、更新配网流程）：
   - 必须同步更新 [`docs/安卓端快速使用说明书.md`](docs/安卓端快速使用说明书.md)，确保普通用户操作指南始终准确。
3. **版本迭代与发布**：
   - 必须同步更新 [`README.md`](README.md) 中的版本号与核心介绍，并在 GitHub Release 中注明更新日志（Changelog）。
4. **废弃或移除功能**：
   - 必须在代码删除的同时彻底清理相关说明文档，并在此记录废弃原因，杜绝“文档残留幽灵功能”。
