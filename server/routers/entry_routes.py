from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from database import get_db
from models import User, Group, WordEntry, now_iso
from schemas import WordEntryCreate, WordEntryUpdate, WordEntryOut
from auth import get_current_user
from routers.sync_compat_routes import record_server_op
import phonetics

router = APIRouter(prefix="/entries", tags=["单词条目"])


@router.post("", response_model=WordEntryOut)
def create_entry(
    req: WordEntryCreate,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    group = db.query(Group).filter(Group.id == req.group_id, Group.user_id == current_user.id).first()
    if not group:
        raise HTTPException(status_code=404, detail="指定的分组不存在")

    word_clean = req.word.strip()
    if not word_clean:
        raise HTTPException(status_code=400, detail="单词不能为空")

    phonetic = (req.phonetic or "").strip()
    if not phonetic:
        try:
            phonetic = phonetics.ipa(word_clean)
        except Exception:
            phonetic = ""

    entry = WordEntry(
        group_id=req.group_id,
        user_id=current_user.id,
        word=word_clean,
        meaning=(req.meaning or "").strip(),
        note=(req.note or "").strip(),
        phonetic=phonetic,
        created_at=now_iso(),
        updated_at=now_iso(),
    )
    db.add(entry)
    db.commit()
    db.refresh(entry)

    # 记入增量同步日志，供安卓手机端增量拉取
    record_server_op("entry.add", entry.id, {
        "id": entry.id,
        "groupId": entry.group_id,
        "word": entry.word,
        "meaning": entry.meaning,
        "note": entry.note,
        "phonetic": entry.phonetic,
        "createdAt": entry.created_at,
        "updatedAt": entry.updated_at,
    })

    return entry


@router.put("/{entry_id}", response_model=WordEntryOut)
def update_entry(
    entry_id: str,
    req: WordEntryUpdate,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    entry = db.query(WordEntry).filter(WordEntry.id == entry_id, WordEntry.user_id == current_user.id).first()
    if not entry:
        raise HTTPException(status_code=404, detail="单词条目不存在")

    word_changed = False
    if req.word is not None and req.word.strip():
        new_w = req.word.strip()
        if new_w != entry.word:
            entry.word = new_w
            word_changed = True

    if req.meaning is not None:
        entry.meaning = req.meaning.strip()
    if req.note is not None:
        entry.note = req.note.strip()

    if req.phonetic is not None:
        entry.phonetic = req.phonetic.strip()
    elif word_changed:
        try:
            entry.phonetic = phonetics.ipa(entry.word)
        except Exception:
            pass

    entry.updated_at = now_iso()
    db.commit()
    db.refresh(entry)

    # 记入增量同步日志
    record_server_op("entry.update", entry.id, {
        "id": entry.id,
        "groupId": entry.group_id,
        "word": entry.word,
        "meaning": entry.meaning,
        "note": entry.note,
        "phonetic": entry.phonetic,
        "createdAt": entry.created_at,
        "updatedAt": entry.updated_at,
    })

    return entry


@router.delete("/{entry_id}")
def delete_entry(
    entry_id: str,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    entry = db.query(WordEntry).filter(WordEntry.id == entry_id, WordEntry.user_id == current_user.id).first()
    if not entry:
        raise HTTPException(status_code=404, detail="单词条目不存在")

    db.delete(entry)
    db.commit()

    # 记入增量同步日志
    record_server_op("entry.remove", entry_id, {
        "id": entry_id,
        "deleted": True,
    })

    return {"ok": True, "message": "单词条目已删除"}
