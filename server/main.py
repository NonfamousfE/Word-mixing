import hashlib
import json
import os
import secrets
from urllib.parse import unquote
from fastapi import FastAPI, HTTPException, Request, Header
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse

from database import engine, Base, DATA_DIR, check_migrations
from routers.auth_routes import router as auth_router
from routers.group_routes import router as group_router
from routers.entry_routes import router as entry_router
from routers.phonetics_routes import router as phonetics_router
from routers.transfer_routes import router as transfer_router
from routers.settings_routes import router as settings_router
from routers.sync_compat_routes import router as sync_compat_router

# Initialize database tables and run automatic migrations
Base.metadata.create_all(bind=engine)
check_migrations()

app = FastAPI(
    title="单词混记 API (WordMix)",
    version="2.0.0",
    description="单词混记 C-S 架构后端服务，提供词库管理、音标解析、多端接入与 Web 前端托管。",
)

# CORS setup for Web SPA and Mobile App
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# API Routers
app.include_router(auth_router, prefix="/api")
app.include_router(group_router, prefix="/api")
app.include_router(entry_router, prefix="/api")
app.include_router(phonetics_router, prefix="/api")
app.include_router(transfer_router, prefix="/api")
app.include_router(settings_router, prefix="/api")
app.include_router(sync_compat_router)


@app.get("/health")
def health():
    return {
        "ok": True,
        "service": "wordmix-backend",
        "version": "2.0.0",
        "mode": "C-S",
    }


# --------------------------------------------------------------------------
# 安卓端自更新分发兼容接口 (/app/latest, /app/download/...)
# --------------------------------------------------------------------------
APK_DIR = os.path.join(DATA_DIR, "apk")


@app.get("/app/latest")
def get_latest_apk(request: Request):
    meta_path = os.path.join(APK_DIR, "meta.json")
    if os.path.exists(meta_path):
        try:
            with open(meta_path, "r", encoding="utf-8") as f:
                meta = json.load(f)
            apk_file = meta.get("file")
            if apk_file and os.path.exists(os.path.join(APK_DIR, apk_file)):
                meta["size"] = os.path.getsize(os.path.join(APK_DIR, apk_file))
                base = str(request.base_url).rstrip("/")
                meta["url"] = f"{base}/app/download/{apk_file}"
                return meta
        except Exception:
            pass
    return {
        "versionCode": 13,
        "versionName": "1.1.2",
        "file": "wordmix.apk",
        "url": "",
        "changelog": "修复增量同步防重与多端数据一致性问题",
    }


@app.get("/app/download/{filename}")
def download_apk(filename: str):
    file_path = os.path.join(APK_DIR, os.path.basename(filename))
    if os.path.exists(file_path):
        return FileResponse(file_path, media_type="application/vnd.android.package-archive")

    # 如果请求的是通用的 wordmix.apk，自动匹配目录下最新的实际 apk
    if filename == "wordmix.apk" and os.path.exists(APK_DIR):
        meta_path = os.path.join(APK_DIR, "meta.json")
        if os.path.exists(meta_path):
            try:
                with open(meta_path, "r", encoding="utf-8") as f:
                    m = json.load(f)
                target = m.get("file")
                if target and os.path.exists(os.path.join(APK_DIR, target)):
                    return FileResponse(os.path.join(APK_DIR, target), media_type="application/vnd.android.package-archive")
            except Exception:
                pass
        apks = sorted([f for f in os.listdir(APK_DIR) if f.endswith(".apk") and not f.startswith("wordmix-9999")])
        if apks:
            return FileResponse(os.path.join(APK_DIR, apks[-1]), media_type="application/vnd.android.package-archive")

    raise HTTPException(status_code=404, detail="文件不存在")


@app.post("/app/upload")
async def upload_apk(
    request: Request,
    x_version_code: str = Header(..., alias="X-Version-Code"),
    x_version_name: str = Header(..., alias="X-Version-Name"),
    x_changelog: str = Header("", alias="X-Changelog"),
    authorization: str = Header("", alias="Authorization"),
):
    token = authorization[7:].strip() if authorization.startswith("Bearer ") else ""
    token_valid = False

    token_path = os.path.join(DATA_DIR, "token.txt")
    if os.path.exists(token_path):
        try:
            with open(token_path, "r", encoding="utf-8") as fh:
                srv_tok = fh.read().strip()
            if srv_tok and secrets.compare_digest(token, srv_tok):
                token_valid = True
        except Exception:
            pass

    if not token_valid and token:
        from database import SessionLocal
        from models import Token
        db = SessionLocal()
        try:
            tok_obj = db.query(Token).filter(Token.token == token).first()
            if tok_obj:
                token_valid = True
        finally:
            db.close()

    if not token_valid and os.path.exists(token_path):
        raise HTTPException(status_code=401, detail="未授权，请提供合法的 Bearer Token")

    try:
        vcode_i = int(x_version_code)
    except ValueError:
        raise HTTPException(status_code=400, detail="bad_version_code")

    changelog = unquote(x_changelog)
    data = await request.body()
    if not data or not data.startswith(b"PK"):
        raise HTTPException(status_code=400, detail="文件格式不是合法的 APK (未以 PK 开头)")

    os.makedirs(APK_DIR, exist_ok=True)
    name = f"wordmix-{vcode_i}-{x_version_name}.apk"
    tmp_path = os.path.join(APK_DIR, name + ".part")
    with open(tmp_path, "wb") as fh:
        fh.write(data)
    target_path = os.path.join(APK_DIR, name)
    if os.path.exists(target_path):
        os.remove(target_path)
    os.rename(tmp_path, target_path)

    import shutil
    shutil.copyfile(target_path, os.path.join(APK_DIR, "wordmix.apk"))

    from models import now_iso
    meta = {
        "versionCode": vcode_i,
        "versionName": x_version_name,
        "file": name,
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "changelog": changelog,
        "uploadedAt": now_iso(),
    }
    with open(os.path.join(APK_DIR, "meta.json"), "w", encoding="utf-8") as fh:
        json.dump(meta, fh, ensure_ascii=False, indent=2)

    return meta


@app.post("/app/update-bundle")
async def update_bundle(
    request: Request,
    authorization: str = Header("", alias="Authorization"),
):
    token = authorization[7:].strip() if authorization.startswith("Bearer ") else ""
    token_valid = False

    token_path = os.path.join(DATA_DIR, "token.txt")
    if os.path.exists(token_path):
        try:
            with open(token_path, "r", encoding="utf-8") as fh:
                srv_tok = fh.read().strip()
            if srv_tok and secrets.compare_digest(token, srv_tok):
                token_valid = True
        except Exception:
            pass

    if not token_valid and token:
        from database import SessionLocal
        from models import Token
        db = SessionLocal()
        try:
            tok_obj = db.query(Token).filter(Token.token == token).first()
            if tok_obj:
                token_valid = True
        finally:
            db.close()

    if not token_valid and os.path.exists(token_path):
        raise HTTPException(status_code=401, detail="未授权，请提供合法的 Bearer Token")

    data = await request.body()
    if not data or len(data) < 100:
        raise HTTPException(status_code=400, detail="非法压缩包格式")

    import io, tarfile, threading, subprocess, time
    buf = io.BytesIO(data)
    app_dir = os.path.dirname(os.path.abspath(__file__))
    try:
        with tarfile.open(fileobj=buf, mode="r:gz") as tar:
            tar.extractall(path=app_dir)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"解压失败: {e}")

    def restart_service():
        time.sleep(1)
        subprocess.run(["systemctl", "restart", "wordmix-sync"], check=False)

    threading.Thread(target=restart_service, daemon=True).start()
    return {"ok": True, "message": "服务端与 Web 资源已更新，正在无感重启服务"}


# --------------------------------------------------------------------------
# Web 前端静态站点托管 (SPA 模式)
# --------------------------------------------------------------------------
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
if os.path.exists(STATIC_DIR):
    assets_dir = os.path.join(STATIC_DIR, "assets")
    if os.path.exists(assets_dir):
        app.mount("/assets", StaticFiles(directory=assets_dir), name="assets")

    @app.get("/")
    async def serve_index():
        return FileResponse(os.path.join(STATIC_DIR, "index.html"))

    @app.get("/{full_path:path}")
    async def serve_spa(full_path: str):
        file_path = os.path.join(STATIC_DIR, full_path)
        if os.path.isfile(file_path):
            return FileResponse(file_path)
        index_file = os.path.join(STATIC_DIR, "index.html")
        if os.path.isfile(index_file):
            return FileResponse(index_file)
        return {"error": "Not Found"}
else:
    @app.get("/")
    def index():
        return {
            "message": "单词混记后端服务运行中 (静态目录尚未构建)",
            "docs": "/docs",
            "health": "/health",
        }


if __name__ == "__main__":
    import uvicorn
    host = os.environ.get("WORDMIX_HOST", "0.0.0.0")
    port = int(os.environ.get("WORDMIX_PORT", os.environ.get("PORT", "18080")))
    uvicorn.run("main:app", host=host, port=port, reload=False)
