#!/usr/bin/env python3
"""单词混记 · C-S 架构一键启动服务

启动后：
- Web 网页端：浏览器打开 http://localhost:8000
- REST API 文档：http://localhost:8000/docs
- 默认登录账号：admin / admin123
"""

import os
import sys
import webbrowser

server_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "server")
sys.path.insert(0, server_dir)

if __name__ == "__main__":
    import uvicorn
    print("\n" + "="*50)
    print("🚀 单词混记 (WordMix) C-S 架构服务启动中...")
    print("🌐 电脑 Web 网页端地址: http://localhost:8000")
    print("📖 REST API 接口文档:  http://localhost:8000/docs")
    print("👤 默认演示账号:       admin / admin123")
    print("="*50 + "\n")

    # 尝试在启动后自动打开浏览器
    try:
        webbrowser.open("http://localhost:8000")
    except Exception:
        pass

    uvicorn.run("main:app", host="0.0.0.0", port=8000, app_dir=server_dir, reload=False)
