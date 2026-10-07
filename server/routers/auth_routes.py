from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from database import get_db
from models import User, Token
from schemas import UserRegister, UserLogin, UserOut, TokenOut
from auth import hash_password, verify_password, create_token, get_current_user

router = APIRouter(prefix="/auth", tags=["认证"])


@router.post("/register", response_model=TokenOut)
def register(req: UserRegister, db: Session = Depends(get_db)):
    exist = db.query(User).filter(User.username == req.username.strip()).first()
    if exist:
        raise HTTPException(status_code=400, detail="该用户名已被注册")
    
    user = User(
        username=req.username.strip(),
        password_hash=hash_password(req.password),
    )
    db.add(user)
    db.commit()
    db.refresh(user)

    token = create_token(db, user.id)
    return TokenOut(access_token=token, user=user)


@router.post("/login", response_model=TokenOut)
def login(req: UserLogin, db: Session = Depends(get_db)):
    user = db.query(User).filter(User.username == req.username.strip()).first()
    if not user or not verify_password(req.password, user.password_hash):
        raise HTTPException(status_code=400, detail="用户名或密码错误")

    token = create_token(db, user.id)
    return TokenOut(access_token=token, user=user)


@router.get("/me", response_model=UserOut)
def me(current_user: User = Depends(get_current_user)):
    return current_user


@router.post("/logout")
def logout(current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    db.query(Token).filter(Token.user_id == current_user.id).delete()
    db.commit()
    return {"ok": True, "message": "已安全退出"}
