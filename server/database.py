import os
import sqlite3
from sqlalchemy import create_engine
from sqlalchemy.orm import declarative_base, sessionmaker

if os.path.exists("/var/lib/wordmix"):
    DEFAULT_DATA_DIR = "/var/lib/wordmix"
else:
    DEFAULT_DATA_DIR = os.path.dirname(os.path.abspath(__file__))

DATA_DIR = os.environ.get("WORDMIX_DATA_DIR", DEFAULT_DATA_DIR)
os.makedirs(DATA_DIR, exist_ok=True)
DB_PATH = os.environ.get("WORDMIX_DB_PATH", os.path.join(DATA_DIR, "wordmix.db"))

DATABASE_URL = f"sqlite:///{DB_PATH}"

engine = create_engine(
    DATABASE_URL,
    connect_args={"check_same_thread": False},
    echo=False
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


def check_migrations():
    try:
        with sqlite3.connect(DB_PATH) as conn:
            cols = [r[1] for r in conn.execute("PRAGMA table_info(users)").fetchall()]
            if cols and "is_admin" not in cols:
                conn.execute("ALTER TABLE users ADD COLUMN is_admin BOOLEAN DEFAULT 0")
                conn.execute("UPDATE users SET is_admin = 1 WHERE username = 'admin'")
            conn.execute(
                "CREATE TABLE IF NOT EXISTS system_settings ("
                "key TEXT PRIMARY KEY, value TEXT NOT NULL)"
            )
            conn.commit()
    except Exception:
        pass


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
