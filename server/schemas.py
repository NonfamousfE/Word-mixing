from typing import Optional, List
from pydantic import BaseModel, Field


# --------------------------------------------------------------------------
# Auth & User
# --------------------------------------------------------------------------

class UserRegister(BaseModel):
    username: str = Field(..., min_length=2, max_length=64)
    password: str = Field(..., min_length=4, max_length=128)


class UserLogin(BaseModel):
    username: str
    password: str


class UserOut(BaseModel):
    id: int
    username: str
    is_admin: bool = False
    created_at: str

    class Config:
        from_attributes = True


class TokenOut(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: UserOut


# --------------------------------------------------------------------------
# WordEntry
# --------------------------------------------------------------------------

class WordEntryCreate(BaseModel):
    group_id: str
    word: str = Field(..., min_length=1)
    meaning: Optional[str] = ""
    note: Optional[str] = ""
    phonetic: Optional[str] = ""


class WordEntryUpdate(BaseModel):
    word: Optional[str] = None
    meaning: Optional[str] = None
    note: Optional[str] = None
    phonetic: Optional[str] = None


class WordEntryOut(BaseModel):
    id: str
    group_id: str
    word: str
    meaning: str
    note: str
    phonetic: str
    created_at: str
    updated_at: str

    class Config:
        from_attributes = True


# --------------------------------------------------------------------------
# Group
# --------------------------------------------------------------------------

class GroupCreate(BaseModel):
    name: str = Field(..., min_length=1)
    note: Optional[str] = ""


class GroupUpdate(BaseModel):
    name: Optional[str] = None
    note: Optional[str] = None


class GroupOut(BaseModel):
    id: str
    name: str
    note: str
    created_at: str
    updated_at: str
    entry_count: int = 0

    class Config:
        from_attributes = True


class GroupDetailOut(BaseModel):
    id: str
    name: str
    note: str
    created_at: str
    updated_at: str
    entries: List[WordEntryOut] = []

    class Config:
        from_attributes = True


class PaginatedGroupsOut(BaseModel):
    items: List[GroupDetailOut]
    total_groups: int
    total_entries: int
    page: int
    page_size: int
    total_pages: int


class SettingsOut(BaseModel):
    default_page_size: int


class SettingsUpdate(BaseModel):
    default_page_size: int = Field(..., ge=1, le=500)


# --------------------------------------------------------------------------
# Phonetic & Transfer
# --------------------------------------------------------------------------

class PhoneticOut(BaseModel):
    word: str
    ipa: str


class ImportData(BaseModel):
    groups: List[dict] = []
    entries: List[dict] = []
