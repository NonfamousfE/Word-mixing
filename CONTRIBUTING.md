# 贡献指南 (Contributing to WordMix)

感谢你关注并愿意为「单词混记 (WordMix)」贡献力量！  
为了保持项目高质量、轻量化与文档的绝对一致性，请在提交 Pull Request (PR) 前阅读以下准则。

---

## 🎯 核心开发铁律（必读）

> 📢 **更新代码必须严格同步更新相关文档！**  
> 功能代码修改与文档修改必须在**同一个 Pull Request / Commit** 中提交，严禁只提代码不改文档。

1. **若修改了接口、表结构或后端逻辑**：
   - 同步更新：[`docs/项目开发与架构说明文档.md`](docs/项目开发与架构说明文档.md) 中的架构图、协议定义或代码地图。
2. **若修改了 Android 客户端交互或界面**：
   - 同步更新：[`docs/安卓端快速使用说明书.md`](docs/安卓端快速使用说明书.md)。
3. **若新增了核心特性或重要配置**：
   - 同步更新：[`README.md`](README.md)。

---

## 🛠️ 本地开发与提交流程

### 1. 开发环境建议
- Python 3.10+
- JDK 17 & Android SDK
- Node.js 18+ 与 npm (仅修改 Web 前端需要)

### 2. 避免中文路径坑 (Android)
如果修改了 Android 端代码，请注意由于 Gradle worker classpath 解析机制，必须将 `android/` 目录同步到纯 ASCII 路径（如 `C:\wm-app`）下进行编译与单元测试。

### 3. 运行本地自动化测试
在提交前，请确保自动化测试 100% 通过：
```powershell
python build/run-all-tests.py
```

### 4. 提交规范
- Commit message 请清晰描述本次修改的目的与变更内容，遵循 Conventional Commits 风格（例如 `feat:`, `fix:`, `refactor:`, `docs:`）。
- 禁止提交任何测试数据库（`*.db`）、个人环境配置（`*.env`）或密钥文件。

---

## 📜 开源协议
通过向本项目贡献代码，即表示你同意你的贡献将基于 [MIT License](LICENSE) 授权发布。
