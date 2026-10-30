#!/usr/bin/env python3
"""Plan 2 Task 6：只读导出 Room 库里的音频行，并输出 §0-14 要求的交叉表。

交叉表三列：`(bytes-44)//32 | rawAudioMs | diff`，diff != 0 即红（说明扫描没把
文件真源写回库里，或写回了旧值）。

用法：
  python p2-scan-db.py <dreamkeeper.db> [--stats id=bytes ...] [--all]
"""

import argparse
import hashlib
import os
import sqlite3
import sys

HEADER = 44
BYTES_PER_MS = 32


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("--stats", action="append", default=[], help="id=bytes（可重复）")
    ap.add_argument("--all", action="store_true", help="连种子行一起打印")
    args = ap.parse_args()

    stats = {}
    for item in args.stats:
        key, _, value = item.partition("=")
        stats[key] = int(value)

    uri = "file:" + os.path.abspath(args.db).replace(os.sep, "/") + "?mode=ro"
    con = sqlite3.connect(uri, uri=True)
    con.row_factory = sqlite3.Row

    print(f"db: {args.db}")
    print(f"db_sha256: {sha256(args.db)}")
    print(f"db_bytes: {os.path.getsize(args.db)}")
    print("sql: SELECT * FROM dreams" + ("" if args.all else " WHERE rawAudioPath IS NOT NULL"))
    print()

    query = (
        "SELECT id, dreamDate, createdAt, updatedAt, isDraft, transcriptStatus, "
        "rawAudioPath, rawAudioMs, rawTranscript FROM dreams"
    )
    if not args.all:
        query += " WHERE rawAudioPath IS NOT NULL"
    query += " ORDER BY createdAt, id"
    rows = list(con.execute(query))
    if not rows:
        print("(no rows)")
        return 0

    print("id | dreamDate | createdAt | updatedAt | isDraft | transcriptStatus | rawAudioMs | path | transcriptChars")
    for r in rows:
        print(
            " | ".join(
                [
                    str(r["id"]),
                    str(r["dreamDate"]),
                    str(r["createdAt"]),
                    str(r["updatedAt"]),
                    str(r["isDraft"]),
                    str(r["transcriptStatus"]),
                    str(r["rawAudioMs"]),
                    str(r["rawAudioPath"]),
                    str(len(r["rawTranscript"] or "")),
                ]
            )
        )

    audio_rows = [r for r in rows if r["rawAudioPath"]]
    if audio_rows:
        print()
        print("cross-check: (bytes-44)//32 | rawAudioMs | diff")
        red = 0
        for r in audio_rows:
            dream_id = r["id"]
            if dream_id not in stats:
                print(f"{dream_id} | {r['rawAudioMs']} | MISSING_STAT")
                red += 1
                continue
            expect = (stats[dream_id] - HEADER) // BYTES_PER_MS
            actual = r["rawAudioMs"]
            diff = -1 if actual is None else expect - actual
            print(f"{expect} | {actual} | {diff}")
            if diff != 0:
                red += 1
        print(f"cross_red_rows: {red}")
        return 0 if red == 0 else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
