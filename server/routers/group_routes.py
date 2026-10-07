from typing import List, Optional
import math
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from sqlalchemy import or_, func

from database import get_db
from models import User, Group, WordEntry, SystemSetting, now_iso
from schemas import GroupCreate, GroupUpdate, GroupDetailOut, PaginatedGroupsOut
from auth import get_current_user
from routers.sync_compat_routes import record_server_op

router = APIRouter(prefix="/groups", tags=["混淆词组"])


@router.get("/chips")
def list_group_chips(
    sort: Optional[str] = Query("default", description="排序方式: default(按添加顺序), word_count_desc(按单词数降序), name_asc(字典序)"),
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    query = db.query(Group.id, Group.name).filter(Group.user_id == current_user.id)
    if sort == "word_count_desc":
        word_count_subq = (
            db.query(func.count(WordEntry.id))
            .filter(WordEntry.group_id == Group.id)
            .scalar_subquery()
        )
        query = query.order_by(word_count_subq.desc(), Group.name.asc())
    elif sort == "name_asc":
        query = query.order_by(Group.name.asc())
    else:
        latest_entry_subq = (
            db.query(func.max(func.coalesce(WordEntry.updated_at, WordEntry.created_at)))
            .filter(WordEntry.group_id == Group.id)
            .scalar_subquery()
        )
        group_time = func.coalesce(Group.updated_at, Group.created_at)
        query = query.order_by(func.coalesce(latest_entry_subq, group_time).desc())
    
    rows = query.all()
    return [{"id": r[0], "name": r[1]} for r in rows]


@router.get("", response_model=PaginatedGroupsOut)
def list_groups(
    q: Optional[str] = Query(None, description="搜索关键词（支持匹配分组名、单词、释义）"),
    page: int = Query(1, ge=1, description="当前页码，从 1 开始"),
    page_size: Optional[int] = Query(None, ge=1, le=5000, description="每页词组数量（默认10）"),
    sort: Optional[str] = Query("default", description="排序方式: default, word_count_desc, name_asc"),
    group_id: Optional[str] = Query(None, description="筛选指定分组ID"),
    all: bool = Query(False, description="是否获取全部数据"),
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    # 如果未指定每页大小，使用系统配置或默认 10
    if page_size is None:
        setting = db.query(SystemSetting).filter(SystemSetting.key == "default_page_size").first()
        try:
            page_size = int(setting.value) if setting else 10
        except Exception:
            page_size = 10

    query = db.query(Group).filter(Group.user_id == current_user.id)

    if group_id:
        query = query.filter(Group.id == group_id)

    if q and q.strip():
        term = f"%{q.strip().lower()}%"
        matching_group_ids = (
            db.query(WordEntry.group_id)
            .filter(
                WordEntry.user_id == current_user.id,
                or_(
                    WordEntry.word.ilike(term),
                    WordEntry.meaning.ilike(term),
                    WordEntry.note.ilike(term),
                )
            )
            .distinct()
            .all()
        )
        matched_ids = [gid[0] for gid in matching_group_ids]
        query = query.filter(
            or_(
                Group.name.ilike(term),
                Group.note.ilike(term),
                Group.id.in_(matched_ids)
            )
        )

    total_groups = query.count()
    total_entries = db.query(WordEntry).filter(WordEntry.user_id == current_user.id).count()

    # 排序处理
    if sort == "word_count_desc":
        word_count_subq = (
            db.query(func.count(WordEntry.id))
            .filter(WordEntry.group_id == Group.id)
            .scalar_subquery()
        )
        query = query.order_by(word_count_subq.desc(), Group.name.asc())
    elif sort == "name_asc":
        query = query.order_by(Group.name.asc())
    else:
        # 默认：按组内单词修改/添加时间从新到旧 (desc)
        latest_entry_subq = (
            db.query(func.max(func.coalesce(WordEntry.updated_at, WordEntry.created_at)))
            .filter(WordEntry.group_id == Group.id)
            .scalar_subquery()
        )
        group_time = func.coalesce(Group.updated_at, Group.created_at)
        query = query.order_by(func.coalesce(latest_entry_subq, group_time).desc())

    if all:
        items = query.all()
        return PaginatedGroupsOut(
            items=items,
            total_groups=total_groups,
            total_entries=total_entries,
            page=1,
            page_size=total_groups or 10,
            total_pages=1,
        )

    total_pages = math.ceil(total_groups / page_size) if total_groups > 0 else 1
    items = (
        query
        .offset((page - 1) * page_size)
        .limit(page_size)
        .all()
    )

    return PaginatedGroupsOut(
        items=items,
        total_groups=total_groups,
        total_entries=total_entries,
        page=page,
        page_size=page_size,
        total_pages=total_pages,
    )


@router.post("", response_model=GroupDetailOut)
def create_group(
    req: GroupCreate,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    name = req.name.strip()
    if not name:
        raise HTTPException(status_code=400, detail="分组名称不能为空")
    
    group = Group(
        user_id=current_user.id,
        name=name,
        note=req.note or "",
        created_at=now_iso(),
        updated_at=now_iso(),
    )
    db.add(group)
    db.commit()
    db.refresh(group)

    # 记入增量同步日志
    record_server_op("group.add", group.id, {
        "id": group.id,
        "name": group.name,
        "note": group.note,
        "createdAt": group.created_at,
        "updatedAt": group.updated_at,
    })

    return group


@router.get("/recommend")
def recommend_groups(
    word: str = Query(..., description="待推荐的单词"),
    limit: int = Query(8, ge=1, le=20, description="返回候选推荐的最大数量"),
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    from similarity import find_similar_groups
    return find_similar_groups(db, current_user.id, word, limit=limit)


@router.get("/{group_id}", response_model=GroupDetailOut)
def get_group(
    group_id: str,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    group = db.query(Group).filter(Group.id == group_id, Group.user_id == current_user.id).first()
    if not group:
        raise HTTPException(status_code=404, detail="分组不存在")
    return group


@router.put("/{group_id}", response_model=GroupDetailOut)
def update_group(
    group_id: str,
    req: GroupUpdate,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    group = db.query(Group).filter(Group.id == group_id, Group.user_id == current_user.id).first()
    if not group:
        raise HTTPException(status_code=404, detail="分组不存在")
    
    if req.name is not None and req.name.strip():
        group.name = req.name.strip()
    if req.note is not None:
        group.note = req.note.strip()
    
    group.updated_at = now_iso()
    db.commit()
    db.refresh(group)

    # 记入增量同步日志
    record_server_op("group.update", group.id, {
        "id": group.id,
        "name": group.name,
        "note": group.note,
        "createdAt": group.created_at,
        "updatedAt": group.updated_at,
    })

    return group


@router.delete("/{group_id}")
def delete_group(
    group_id: str,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    group = db.query(Group).filter(Group.id == group_id, Group.user_id == current_user.id).first()
    if not group:
        raise HTTPException(status_code=404, detail="分组不存在")
    
    db.delete(group)
    db.commit()

    # 记入增量同步日志
    record_server_op("group.remove", group_id, {
        "id": group_id,
        "deleted": True,
    })

    return {"ok": True, "message": "分组及所属词条已删除"}
