from fastapi import APIRouter, Depends, HTTPException, Body
from sqlalchemy.orm import Session
from typing import Dict, Any

from database import get_db
from models import User, Group, WordEntry, now_iso
from auth import get_current_user
import phonetics

router = APIRouter(prefix="/transfer", tags=["导入导出"])


@router.get("/export")
def export_library(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    groups = db.query(Group).filter(Group.user_id == current_user.id).order_by(Group.created_at).all()
    entries = db.query(WordEntry).filter(WordEntry.user_id == current_user.id).order_by(WordEntry.created_at).all()

    return {
        "schemaVersion": 2,
        "exportedAt": now_iso(),
        "user": current_user.username,
        "groups": [
            {
                "id": g.id,
                "name": g.name,
                "note": g.note,
                "createdAt": g.created_at,
                "updatedAt": g.updated_at,
            }
            for g in groups
        ],
        "entries": [
            {
                "id": e.id,
                "groupId": e.group_id,
                "word": e.word,
                "meaning": e.meaning,
                "note": e.note,
                "phonetic": e.phonetic,
                "createdAt": e.created_at,
                "updatedAt": e.updated_at,
            }
            for e in entries
        ],
    }


@router.post("/import")
def import_library(
    payload: Dict[str, Any] = Body(...),
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """支持导入原有 `library.json` 格式数据。"""
    raw_groups = payload.get("groups", [])
    raw_entries = payload.get("entries", [])

    imported_groups_count = 0
    imported_entries_count = 0

    group_id_map = {}

    for g in raw_groups:
        if g.get("deleted"):
            continue
        g_name = str(g.get("name") or "").strip()
        if not g_name:
            continue
        
        # Check if group already exists for this user
        existing_group = db.query(Group).filter(
            Group.user_id == current_user.id,
            Group.name == g_name
        ).first()

        if existing_group:
            group_id_map[g.get("id")] = existing_group.id
        else:
            new_g = Group(
                id=g.get("id") or None,
                user_id=current_user.id,
                name=g_name,
                note=g.get("note") or "",
                created_at=g.get("createdAt") or now_iso(),
                updated_at=g.get("updatedAt") or now_iso(),
            )
            db.add(new_g)
            db.flush()
            group_id_map[g.get("id")] = new_g.id
            imported_groups_count += 1

    for e in raw_entries:
        if e.get("deleted"):
            continue
        raw_gid = e.get("groupId")
        target_gid = group_id_map.get(raw_gid, raw_gid)
        
        # Verify target group exists
        target_group = db.query(Group).filter(
            Group.id == target_gid,
            Group.user_id == current_user.id
        ).first()
        if not target_group:
            continue

        # Extract word and meaning
        fields = e.get("fields") or {}
        word = str(e.get("word") or fields.get("f_word") or "").strip()
        meaning = str(e.get("meaning") or fields.get("f_meaning") or "").strip()
        note = str(e.get("note") or fields.get("f_note") or "").strip()
        if not word:
            continue

        # Check phonetic
        phonetic = str(e.get("phonetic") or "").strip()
        if not phonetic:
            try:
                phonetic = phonetics.ipa(word)
            except Exception:
                phonetic = ""

        # Check existing entry in same group
        exist_entry = db.query(WordEntry).filter(
            WordEntry.user_id == current_user.id,
            WordEntry.group_id == target_gid,
            WordEntry.word == word
        ).first()

        if exist_entry:
            if not exist_entry.meaning and meaning:
                exist_entry.meaning = meaning
            if not exist_entry.phonetic and phonetic:
                exist_entry.phonetic = phonetic
        else:
            new_entry = WordEntry(
                id=e.get("id") or None,
                group_id=target_gid,
                user_id=current_user.id,
                word=word,
                meaning=meaning,
                note=note,
                phonetic=phonetic,
                created_at=e.get("createdAt") or now_iso(),
                updated_at=e.get("updatedAt") or now_iso(),
            )
            db.add(new_entry)
            imported_entries_count += 1

    db.commit()
    return {
        "ok": True,
        "imported_groups": imported_groups_count,
        "imported_entries": imported_entries_count,
    }
