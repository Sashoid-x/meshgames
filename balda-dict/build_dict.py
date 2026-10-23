#!/usr/bin/env python3
"""Конвертер словаря Балды: singular.txt -> app/src/main/assets/balda_dict.txt.gz.

Пайплайн (подробно в SOURCE.md):
  1. нормализация: нижний регистр, «ё» -> «е»;
  2. фильтр ^[а-я]{2,}$: прочерки/пробелы («а-ля фуршет»), латиница и
     цифры отбрасываются, слова короче 2 букв не нужны (змейка >= 2);
  3. дедуп;
  4. сортировка по codepoint (бинарный поиск BaldaDictionary требует
     порядка; байтовый порядок UTF-8 совпадает с codepoint для кириллицы);
  5. gzip.
"""
import gzip
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent
SRC = ROOT / "singular.txt"
DST = ROOT.parent / "app" / "src" / "main" / "assets" / "balda_dict.txt.gz"

words = set()
with open(SRC, encoding="utf-8") as f:
    for line in f:
        w = line.strip().lower().replace("ё", "е")
        if 2 <= len(w) <= 25 and all("а" <= c <= "я" for c in w):
            words.add(w)

ordered = sorted(words)  # по codepoint
payload = ("\n".join(ordered) + "\n").encode("utf-8")

DST.parent.mkdir(parents=True, exist_ok=True)
with gzip.open(DST, "wb", compresslevel=9) as f:
    f.write(payload)

five = sum(1 for w in ordered if len(w) == 5)
print(f"{len(ordered)} слов (из них 5-буквенных: {five}), "
      f"{len(payload)} Б raw -> {DST.stat().st_size} Б gzip -> {DST}")
