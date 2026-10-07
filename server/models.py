import os
import time
from datetime import datetime, timezone
from sqlalchemy import Column, Integer, String, Text, ForeignKey, Float, Boolean
from sqlalchemy.orm import relationship
from database import Base


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="microseconds")


def generate_id(prefix: str) -> str:
    return f"{prefix}_{int(time.time() * 1000):x}_{os.urandom(3).hex()}"


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True, autoincrement=True)
    username = Column(String(64), unique=True, index=True, nullable=False)
    password_hash = Column(String(256), nullable=False)
    is_admin = Column(Boolean, default=False, nullable=False)
    created_at = Column(String(64), default=now_iso, nullable=False)

    groups = relationship("Group", back_populates="user", cascade="all, delete-orphan")
    entries = relationship("WordEntry", back_populates="user", cascade="all, delete-orphan")
    tokens = relationship("Token", back_populates="user", cascade="all, delete-orphan")


class SystemSetting(Base):
    __tablename__ = "system_settings"

    key = Column(String(64), primary_key=True)
    value = Column(String(256), nullable=False)


class Token(Base):
    __tablename__ = "tokens"

    token = Column(String(128), primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    created_at = Column(String(64), default=now_iso, nullable=False)
    expires_at = Column(Float, nullable=False)

    user = relationship("User", back_populates="tokens")


class Group(Base):
    __tablename__ = "groups"

    id = Column(String(64), primary_key=True, index=True, default=lambda: generate_id("g"))
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    name = Column(String(128), nullable=False, index=True)
    note = Column(Text, default="", nullable=False)
    created_at = Column(String(64), default=now_iso, nullable=False)
    updated_at = Column(String(64), default=now_iso, nullable=False)

    user = relationship("User", back_populates="groups")
    entries = relationship(
        "WordEntry",
        back_populates="group",
        cascade="all, delete-orphan",
        order_by="WordEntry.created_at"
    )


class WordEntry(Base):
    __tablename__ = "word_entries"

    id = Column(String(64), primary_key=True, index=True, default=lambda: generate_id("e"))
    group_id = Column(String(64), ForeignKey("groups.id", ondelete="CASCADE"), nullable=False, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    word = Column(String(128), nullable=False, index=True)
    meaning = Column(Text, default="", nullable=False)
    note = Column(Text, default="", nullable=False)
    phonetic = Column(String(128), default="", nullable=False)
    created_at = Column(String(64), default=now_iso, nullable=False)
    updated_at = Column(String(64), default=now_iso, nullable=False)

    group = relationship("Group", back_populates="entries")
    user = relationship("User", back_populates="entries")
