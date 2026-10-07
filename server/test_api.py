import os
import sys

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fastapi.testclient import TestClient
from main import app

client = TestClient(app)


def test_api():
    print("=== 开始后端 API 自动化测试 ===")

    # 1. 健康检查
    res = client.get("/health")
    assert res.status_code == 200, f"Health check failed: {res.text}"
    assert res.json()["ok"] is True
    print(" [✓] 1. /health 健康检查通过")

    # 2. 登录 admin (若不存在则自动注册)
    res = client.post("/api/auth/login", json={"username": "admin", "password": "admin123"})
    if res.status_code != 200:
        client.post("/api/auth/register", json={"username": "admin", "password": "admin123"})
        res = client.post("/api/auth/login", json={"username": "admin", "password": "admin123"})
    assert res.status_code == 200, f"Login failed: {res.text}"
    token = res.json()["access_token"]
    assert token, "Token should not be empty"
    headers = {"Authorization": f"Bearer {token}"}
    print(f" [✓] 2. /api/auth/login 登录成功, Token 获取成功")

    # 3. 获取当前用户信息
    res = client.get("/api/auth/me", headers=headers)
    assert res.status_code == 200
    assert res.json()["username"] == "admin"
    print(" [✓] 3. /api/auth/me 校验通过")

    # 4. 获取词库分组列表
    res = client.get("/api/groups", headers=headers)
    assert res.status_code == 200
    data = res.json()
    groups = data.get("items", data) if isinstance(data, dict) else data
    assert isinstance(groups, list)
    print(f" [✓] 4. /api/groups 获取词库成功，当前有 {len(groups)} 个分组")

    # 5. 音标查询 API
    res = client.get("/api/phonetics?word=instinct", headers=headers)
    assert res.status_code == 200
    assert "ɪnstɪŋkt" in res.json()["ipa"]
    print(f" [✓] 5. /api/phonetics 音标查询通过: instinct -> {res.json()['ipa']}")

    # 7. 创建测试分组与词条
    res = client.post("/api/groups", headers=headers, json={"name": "test_confusion", "note": "测试分组"})
    assert res.status_code == 200
    test_group = res.json()
    test_gid = test_group["id"]
    print(f" [✓] 7. /api/groups 创建测试分组成功 (ID: {test_gid})")

    # 8. 添加词条（验证自动获取音标功能）
    res = client.post("/api/entries", headers=headers, json={
        "group_id": test_gid,
        "word": "conspicuous",
        "meaning": "显眼的；显著的",
        "note": "测试助记",
    })
    assert res.status_code == 200
    test_entry = res.json()
    assert test_entry["word"] == "conspicuous"
    assert test_entry["phonetic"] != "", "Phonetic should be auto-populated"
    test_eid = test_entry["id"]
    print(f" [✓] 8. /api/entries 创建词条成功 (自动获取音标: {test_entry['phonetic']})")

    # 9. 更新词条
    res = client.put(f"/api/entries/{test_eid}", headers=headers, json={"meaning": "极显眼的"})
    assert res.status_code == 200
    assert res.json()["meaning"] == "极显眼的"
    print(" [✓] 9. /api/entries 更新词条释义通过")

    # 10. 删除测试词条与测试分组
    res = client.delete(f"/api/entries/{test_eid}", headers=headers)
    assert res.status_code == 200
    res = client.delete(f"/api/groups/{test_gid}", headers=headers)
    assert res.status_code == 200
    print(" [✓] 10. 删除测试分组与词条通过")

    # 11. 导出测试
    res = client.get("/api/transfer/export", headers=headers)
    assert res.status_code == 200
    export_data = res.json()
    assert "groups" in export_data and "entries" in export_data
    print(f" [✓] 11. /api/transfer/export 导出成功 (共 {len(export_data['groups'])} 组, {len(export_data['entries'])} 词)")

    print("\n🎉 全部 11 项后端 REST API 自动化测试 100% 通过！\n")


if __name__ == "__main__":
    test_api()
