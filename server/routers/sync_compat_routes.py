import os
import json
import re
import sqlite3
import threading
from typing import Optional, Dict, Any, List
from fastapi import APIRouter, Query, Request, HTTPException, Body
from database import DATA_DIR, get_db, SessionLocal
from models import Group, WordEntry, User, now_iso
import phonetics

router = APIRouter(tags=["旧版同步兼容"])

SYNC_DB_PATH = os.path.join(DATA_DIR, "sync.db")
_db_lock = threading.Lock()


def get_sync_conn():
    os.makedirs(DATA_DIR, exist_ok=True)
    conn = sqlite3.connect(SYNC_DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS ops (
            version    INTEGER PRIMARY KEY AUTOINCREMENT,
            op_id      TEXT    NOT NULL UNIQUE,
            device     TEXT    NOT NULL,
            seq        INTEGER NOT NULL DEFAULT 0,
            kind       TEXT    NOT NULL,
            item_id    TEXT,
            payload    TEXT    NOT NULL,
            received_at TEXT   NOT NULL,
            at         TEXT
        )
        """
    )
    cols = {row["name"] for row in conn.execute("PRAGMA table_info(ops)").fetchall()}
    if "at" not in cols:
        conn.execute("ALTER TABLE ops ADD COLUMN at TEXT")
        conn.commit()
    return conn


def record_server_op(kind: str, item_id: str, payload: dict, device: str = "web"):
    """供服务器端 Web API 在对数据执行增删改时调用，确保生成增量变更日志供移动端对齐"""
    try:
        conn = get_sync_conn()
        with _db_lock:
            op_id = f"{device}-{now_iso()}-{item_id}"
            conn.execute(
                "INSERT OR IGNORE INTO ops (op_id, device, seq, kind, item_id, payload, received_at, at)"
                " VALUES (?,?,?,?,?,?,?,?)",
                (
                    op_id,
                    device,
                    0,
                    kind,
                    item_id,
                    json.dumps(payload, ensure_ascii=False),
                    now_iso(),
                    now_iso(),
                ),
            )
            conn.commit()
            head_row = conn.execute("SELECT COALESCE(MAX(version), 0) AS v FROM ops").fetchone()
            head = head_row[0] if head_row else 0
        conn.close()
        return head
    except Exception as e:
        print(f"[record_server_op error] {e}")
        return 0


@router.get("/sync/version")
def get_sync_version():
    """轻量探测当前云端操作日志的最新版本号，供客户端 0 成本判断是否有增量数据"""
    try:
        conn = get_sync_conn()
        head_row = conn.execute("SELECT COALESCE(MAX(version), 0) AS v FROM ops").fetchone()
        head = head_row["v"] if head_row else 0
        conn.close()
        return {"version": head}
    except Exception:
        return {"version": 0}


@router.get("/sync/pull")
def pull_ops(since: int = Query(0, description="拉取版本自此版本之后的变更"), limit: int = 5000):
    try:
        conn = get_sync_conn()
        cols = {row["name"] for row in conn.execute("PRAGMA table_info(ops)").fetchall()}
        at_col = "at" if "at" in cols else "'' AS at"
        rows = conn.execute(
            f"SELECT version, op_id, device, seq, kind, payload, received_at, {at_col}"
            f" FROM ops WHERE version > ? ORDER BY version ASC LIMIT ?",
            (since, limit),
        ).fetchall()
        head_row = conn.execute("SELECT COALESCE(MAX(version), 0) AS v FROM ops").fetchone()
        head = head_row["v"] if head_row else 0
        conn.close()
    except Exception as exc:
        print(f"[/sync/pull error] {exc}")
        return {"ops": [], "head": 0, "count": 0, "error": str(exc)}

    ops = []
    for r in rows:
        try:
            payload = json.loads(r["payload"])
        except Exception:
            payload = {}
        ops.append({
            "id": r["op_id"],
            "dev": r["device"],
            "seq": r["seq"],
            "kind": r["kind"],
            "payload": payload,
            "at": (r["at"] if "at" in r.keys() else "") or "",
            "version": r["version"],
            "serverAt": r["received_at"],
        })

    return {
        "ops": ops,
        "head": head,
        "count": len(ops),
        "more": len(ops) == limit and (ops[-1]["version"] < head if ops else False),
    }


@router.post("/sync/push")
def push_ops(body: Dict[str, Any] = Body(...)):
    raw_ops = body.get("ops", [])
    device = str(body.get("device") or "unknown")

    accepted = 0
    duplicated = 0
    skipped = 0
    versions = []

    conn = get_sync_conn()
    with _db_lock:
        cols = {row["name"] for row in conn.execute("PRAGMA table_info(ops)").fetchall()}
        if "at" not in cols:
            conn.execute("ALTER TABLE ops ADD COLUMN at TEXT")
            conn.commit()

        for op in raw_ops:
            op_id = str(op.get("id") or "").strip()
            kind = str(op.get("kind") or "").strip()
            payload = op.get("payload")
            if not op_id or not kind or not isinstance(payload, dict):
                skipped += 1
                continue
            item_id = str(payload.get("id") or "")
            try:
                cur = conn.execute(
                    "INSERT INTO ops (op_id, device, seq, kind, item_id, payload, received_at, at)"
                    " VALUES (?,?,?,?,?,?,?,?)",
                    (
                        op_id,
                        str(op.get("dev") or device),
                        int(op.get("seq") or 0),
                        kind,
                        item_id,
                        json.dumps(payload, ensure_ascii=False),
                        now_iso(),
                        str(op.get("at") or ""),
                    ),
                )
                versions.append(cur.lastrowid)
                accepted += 1
            except sqlite3.IntegrityError:
                duplicated += 1
            except Exception as e:
                print(f"[push_ops insert error] {e}")
                skipped += 1
        conn.commit()
        head = conn.execute("SELECT COALESCE(MAX(version), 0) AS v FROM ops").fetchone()["v"]
    conn.close()

    print(f"[/sync/push] device={device}, ops_received={len(raw_ops)}, accepted={accepted}, duplicated={duplicated}, skipped={skipped}, head={head}")

    # 如果有新接受的 op，同步更新 wordmix.db
    if accepted > 0:
        try:
            apply_ops_to_wordmix(raw_ops)
        except Exception as e:
            print(f"[apply_ops_to_wordmix error] {repr(str(e))}")

    return {
        "accepted": accepted,
        "duplicated": duplicated,
        "skipped": skipped,
        "versions": versions,
        "head": head,
    }


def apply_ops_to_wordmix(ops: List[Dict[str, Any]]):
    db = SessionLocal()
    try:
        user = db.query(User).filter(User.username == "admin").first()
        if not user:
            user = db.query(User).order_by(User.id.asc()).first()
        if not user:
            print("[apply_ops_to_wordmix] No user found in database")
            return 0

        group_aliases = {}
        applied_count = 0

        for op in ops:
            kind = op.get("kind", "")
            payload = op.get("payload") or {}
            item_id = payload.get("id")
            if not item_id:
                continue

            if kind.startswith("group."):
                g_name = str(payload.get("name") or "").strip()
                clean_name = g_name.lower().strip()
                is_deleted = bool(payload.get("deleted")) or kind == "group.remove"

                g = db.query(Group).filter(Group.user_id == user.id, Group.id == item_id).first()
                if not g and clean_name and not is_deleted:
                    for eg in db.query(Group).filter(Group.user_id == user.id).all():
                        if eg.name.lower().strip() == clean_name:
                            g = eg
                            group_aliases[item_id] = eg.id
                            break

                if is_deleted:
                    if g:
                        db.delete(g)
                        db.flush()
                        applied_count += 1
                elif g_name:
                    if g:
                        g.name = g_name
                        if payload.get("note") is not None:
                            g.note = payload.get("note")
                        g.updated_at = payload.get("updatedAt") or now_iso()
                    else:
                        g = Group(
                            id=item_id,
                            user_id=user.id,
                            name=g_name,
                            note=payload.get("note") or "",
                            created_at=payload.get("createdAt") or now_iso(),
                            updated_at=payload.get("updatedAt") or now_iso(),
                        )
                        db.add(g)
                    db.flush()
                    applied_count += 1

            elif kind.startswith("entry."):
                fields = payload.get("fields") or {}
                word = str(payload.get("word") or fields.get("f_word") or "").strip()
                meaning = str(payload.get("meaning") or fields.get("f_meaning") or "").strip()
                note = str(payload.get("note") or fields.get("f_note") or "").strip()
                raw_gid = payload.get("groupId")
                gid = group_aliases.get(raw_gid, raw_gid)
                is_deleted = bool(payload.get("deleted")) or kind == "entry.remove"

                e = db.query(WordEntry).filter(WordEntry.user_id == user.id, WordEntry.id == item_id).first()

                if is_deleted:
                    if e:
                        db.delete(e)
                        db.flush()
                        applied_count += 1
                elif word:
                    group_obj = None
                    if gid:
                        group_obj = db.query(Group).filter(Group.id == gid, Group.user_id == user.id).first()
                    if not group_obj and word:
                        for eg in db.query(Group).filter(Group.user_id == user.id).all():
                            if eg.name.lower().strip() == word.lower().strip():
                                group_obj = eg
                                if gid:
                                    group_aliases[gid] = eg.id
                                gid = eg.id
                                break
                    if not group_obj and gid:
                        group_obj = Group(
                            id=gid,
                            user_id=user.id,
                            name=word,
                            note="",
                            created_at=payload.get("createdAt") or now_iso(),
                            updated_at=payload.get("updatedAt") or now_iso(),
                        )
                        db.add(group_obj)
                        db.flush()

                    if group_obj:
                        target_gid = group_obj.id
                        if not e:
                            e = db.query(WordEntry).filter(
                                WordEntry.user_id == user.id,
                                WordEntry.group_id == target_gid,
                                WordEntry.word == word
                            ).first()

                        if e:
                            e.group_id = target_gid
                            if meaning:
                                e.meaning = meaning
                            if note:
                                e.note = note
                            e.updated_at = payload.get("updatedAt") or now_iso()
                        else:
                            ipa = ""
                            try:
                                ipa = phonetics.ipa(word)
                            except Exception:
                                pass
                            new_e = WordEntry(
                                id=item_id,
                                group_id=target_gid,
                                user_id=user.id,
                                word=word,
                                meaning=meaning,
                                note=note,
                                phonetic=ipa,
                                created_at=payload.get("createdAt") or now_iso(),
                                updated_at=payload.get("updatedAt") or now_iso(),
                            )
                            db.add(new_e)
                        db.flush()
                        applied_count += 1

        db.commit()
        print(f"[apply_ops_to_wordmix] successfully applied {applied_count} changes into wordmix.db")
        return applied_count
    except Exception as exc:
        db.rollback()
        import traceback
        err_msg = "".join(traceback.format_exception_only(type(exc), exc)).strip()
        print(f"[apply_ops_to_wordmix error] {repr(err_msg)}")
        return 0
    finally:
        db.close()


@router.post("/sync/rebuild")
def rebuild_wordmix_endpoint():
    """管理接口：从 sync.db 的 ops 表中重放计算活体数据，全量重置并对齐 wordmix.db"""
    result = rebuild_wordmix_from_ops()
    return result


def rebuild_wordmix_from_ops():
    """从 sync.db 的 ops 表中重放计算最新数据，并全量对齐至 wordmix.db"""
    try:
        conn = get_sync_conn()
        rows = conn.execute("SELECT version, op_id, device, seq, kind, payload, at, received_at FROM ops ORDER BY version ASC").fetchall()
        conn.close()
    except Exception as e:
        print(f"[rebuild_wordmix_from_ops error reading ops] {repr(str(e))}")
        return {"ok": False, "error": str(e)}

    ops = []
    for r in rows:
        try:
            payload = json.loads(r["payload"])
        except Exception:
            payload = {}
        ops.append({
            "id": r["op_id"],
            "dev": r["device"],
            "seq": r["seq"],
            "kind": r["kind"],
            "payload": payload,
            "at": r["at"] or "",
            "version": r["version"],
            "serverAt": r["received_at"],
        })

    db = SessionLocal()
    try:
        user = db.query(User).filter(User.username == "admin").first()
        if not user:
            user = db.query(User).order_by(User.id.asc()).first()
        if not user:
            return {"ok": False, "error": "No user found in database"}

        from collections import OrderedDict
        lib_groups = OrderedDict()
        lib_entries = OrderedDict()
        group_aliases = {}

        for op in ops:
            kind = op.get("kind", "")
            payload = dict(op.get("payload") or {})
            item_id = payload.get("id")
            if not item_id:
                continue
            tomb = payload.get("deleted", False) or kind.endswith(".remove")
            is_group = kind.startswith("group.")

            if not is_group and group_aliases:
                gid = payload.get("groupId")
                if gid in group_aliases:
                    payload["groupId"] = group_aliases[gid]

            bucket = lib_groups if is_group else lib_entries
            existing = bucket.get(item_id)
            is_dup_merge = False

            if existing is None and not tomb:
                if is_group:
                    gname = (payload.get("name") or "").lower().strip()
                    if gname:
                        for eg_id, eg in bucket.items():
                            if not eg.get("deleted") and (eg.get("name") or "").lower().strip() == gname:
                                existing = eg
                                is_dup_merge = True
                                break
                else:
                    w = (payload.get("word") or payload.get("fields", {}).get("f_word") or "").lower().strip()
                    gid = payload.get("groupId")
                    if w:
                        for ee_id, ee in bucket.items():
                            if not ee.get("deleted"):
                                ee_w = (ee.get("word") or ee.get("fields", {}).get("f_word") or "").lower().strip()
                                ee_gid = ee.get("groupId")
                                if (not gid or ee_gid == gid) and ee_w == w:
                                    existing = ee
                                    is_dup_merge = True
                                    break

            if existing is None:
                copy = dict(payload)
                if tomb:
                    copy["deleted"] = True
                else:
                    copy.pop("deleted", None)
                bucket[item_id] = copy
            elif is_dup_merge:
                orig_id = existing.get("id")
                remote_id = item_id
                existing["id"] = remote_id
                if is_group:
                    group_aliases[orig_id] = remote_id
                    for e in lib_entries.values():
                        if e.get("groupId") == orig_id:
                            e["groupId"] = remote_id
                existing["updatedAt"] = max(payload.get("updatedAt", ""), existing.get("updatedAt", ""))
            else:
                if tomb:
                    existing["deleted"] = True
                else:
                    existing.pop("deleted", None)
                    orig_id = existing.get("id")
                    for k, v in payload.items():
                        if k not in ("kind", "dev", "seq", "payload", "id"):
                            existing[k] = v
                    if orig_id:
                        existing["id"] = orig_id

        alive_groups = [g for g in lib_groups.values() if not g.get("deleted")]
        alive_entries = [e for e in lib_entries.values() if not e.get("deleted")]

        db.query(WordEntry).filter(WordEntry.user_id == user.id).delete()
        db.query(Group).filter(Group.user_id == user.id).delete()
        db.flush()

        for g in alive_groups:
            db_g = Group(
                id=g.get("id"),
                user_id=user.id,
                name=g.get("name") or "",
                note=g.get("note") or "",
                created_at=g.get("createdAt") or now_iso(),
                updated_at=g.get("updatedAt") or now_iso(),
            )
            db.add(db_g)
        db.flush()

        valid_gids = {g.get("id") for g in alive_groups}

        for e in alive_entries:
            gid = e.get("groupId")
            if gid not in valid_gids:
                continue
            word = str(e.get("word") or e.get("fields", {}).get("f_word") or "").strip()
            meaning = str(e.get("meaning") or e.get("fields", {}).get("f_meaning") or "").strip()
            note = str(e.get("note") or e.get("fields", {}).get("f_note") or "").strip()
            ipa = ""
            try:
                ipa = phonetics.ipa(word)
            except Exception:
                pass
            db_e = WordEntry(
                id=e.get("id"),
                group_id=gid,
                user_id=user.id,
                word=word,
                meaning=meaning,
                note=note,
                phonetic=ipa,
                created_at=e.get("createdAt") or now_iso(),
                updated_at=e.get("updatedAt") or now_iso(),
            )
            db.add(db_e)

        db.commit()
        print(f"[rebuild_wordmix_from_ops] Successfully rebuilt: {len(alive_groups)} groups, {len(alive_entries)} entries")
        return {
            "ok": True,
            "groups_count": len(alive_groups),
            "entries_count": len(alive_entries),
        }
    except Exception as exc:
        db.rollback()
        import traceback
        err_msg = "".join(traceback.format_exception_only(type(exc), exc)).strip()
        print(f"[rebuild_wordmix_from_ops error] {repr(err_msg)}")
        return {"ok": False, "error": err_msg}
    finally:
        db.close()
