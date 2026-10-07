from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from database import get_db
from models import User, SystemSetting
from schemas import SettingsOut, SettingsUpdate
from auth import get_current_user

router = APIRouter(prefix="/settings", tags=["系统设置"])


@router.get("", response_model=SettingsOut)
def get_settings(db: Session = Depends(get_db)):
    setting = db.query(SystemSetting).filter(SystemSetting.key == "default_page_size").first()
    size = 10
    if setting:
        try:
            size = int(setting.value)
        except Exception:
            size = 10
    return SettingsOut(default_page_size=size)


@router.put("", response_model=SettingsOut)
def update_settings(
    req: SettingsUpdate,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if not current_user.is_admin:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="需要管理员特权账号才能修改系统默认分页大小",
        )

    setting = db.query(SystemSetting).filter(SystemSetting.key == "default_page_size").first()
    if not setting:
        setting = SystemSetting(key="default_page_size", value=str(req.default_page_size))
        db.add(setting)
    else:
        setting.value = str(req.default_page_size)

    db.commit()
    return SettingsOut(default_page_size=req.default_page_size)
