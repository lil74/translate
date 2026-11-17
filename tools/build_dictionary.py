"""Build the bundled read-only Chinese dictionary from ECDICT CSV (MIT).

Usage: python tools/build_dictionary.py work/ecdict.csv
Source: https://github.com/skywind3000/ECDICT
Only original Chinese translations, phonetics and vocabulary tags are bundled.
"""
import csv
import gzip
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import sys

root = Path(__file__).resolve().parents[1]
source = Path(sys.argv[1])
target = root / "app/src/main/assets/dictionary"
target.mkdir(parents=True, exist_ok=True)
database = root / "work/dictionary-v1.sqlite"
if database.exists():
    database.unlink()
db = sqlite3.connect(database)
db.execute("CREATE TABLE entries(word TEXT PRIMARY KEY, phonetic TEXT NOT NULL, meaning TEXT NOT NULL, tags TEXT NOT NULL, rank INTEGER NOT NULL) WITHOUT ROWID")
with source.open(encoding="utf-8-sig", newline="") as stream:
    for row in csv.DictReader(stream):
        word = row["word"].strip().lower()
        meaning = row["translation"].replace(r"\n", "\n").strip()
        if len(word) > 100 or not re.fullmatch(r"[a-z]+(?:[ '-][a-z]+)*", word) or not re.search(r"[\u3400-\u9fff]", meaning):
            continue
        ranks = [int(row[k]) for k in ("frq", "bnc") if row.get(k, "").isdigit() and int(row[k]) > 0]
        db.execute("INSERT OR IGNORE INTO entries VALUES(?,?,?,?,?)", (word, row["phonetic"], meaning, row["tag"], min(ranks, default=999999)))
db.commit()
count = db.execute("SELECT COUNT(*) FROM entries").fetchone()[0]
db.execute("VACUUM")
db.close()
with database.open("rb") as stream, (target / "dictionary-v1.sqlite.gzip").open("wb") as output:
    with gzip.GzipFile(filename="", fileobj=output, mode="wb", mtime=0) as compressed:
        while chunk := stream.read(1024 * 1024):
            compressed.write(chunk)
(target / "source.json").write_text(json.dumps({"source": "https://github.com/skywind3000/ECDICT", "license": "MIT", "entries": count, "csvSha256": hashlib.sha256(source.read_bytes()).hexdigest()}, indent=2), encoding="utf-8")
print(f"Bundled {count} entries; database {database.stat().st_size} bytes")
