from fastapi import APIRouter, Query
from schemas import PhoneticOut
import phonetics

router = APIRouter(prefix="/phonetics", tags=["音标"])


@router.get("", response_model=PhoneticOut)
def get_phonetic(word: str = Query(..., min_length=1, description="要查询音标的单词")):
    clean = word.strip()
    try:
        ipa = phonetics.ipa(clean)
    except Exception:
        ipa = ""
    return PhoneticOut(word=clean, ipa=ipa)
