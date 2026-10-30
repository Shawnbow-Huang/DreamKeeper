#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-migrate.py —— Plan 3 Task 4 的库比对闸门

用法：
  # 1) 把设备上拉下来的三件（db + -wal + -shm）做成可比对的 JSON
  python scripts/check-migrate.py --dump docs/evidence/plan3 --out docs/evidence/plan3/p3-mig-01-baseline.json

  # 2) 比对升级前 / 升级后的两份 JSON
  python scripts/check-migrate.py --diff p3-mig-01-baseline.json p3-mig-02-upgraded.json

退出码：
  0  比对通过（最后一行 RESULT: GREEN）
  1  比对不通过（最后一行 RESULT: RED: 原因）
  2  输入/用法错误（目录里没有 .db、JSON 结构坏了、缺三件等）

为什么要先把三件拷出来再打开（D274）：
  设备上的 `dreamkeeper.db` 常常只有 4096 B，真正的数据在 `-wal` 里。只拷 `.db` 会得到
  「0 张表 / user_version=0」的假红。反过来，用 Python 的 sqlite3 直接打开**证据目录里**的库，
  关闭时 SQLite 会 checkpoint 并把 `-wal`/`-shm` 删掉——等于把证据原件改了。所以这里一律：
  先把 db / -wal / -shm 复制到临时目录（同名），再打开副本，原件一个字节都不碰。

比对口径（brief §0.11）：
  * 老表 `dreams` / `tags` / `dream_tags` / `media_assets` 的**每一行每一列**升级前后完全相等
    （浮点按精确文本、中文按 UTF-8 原文，禁 ensure_ascii 转义）；
  * `dreams` 只允许多出 `novelSelectedVersion` / `generationStage` 两列，且老行上必须全 NULL，
    NULL 计数 == 行数；
  * `updatedAt` 一个字都不许变（它在「老列全等」里，但单独打一行，免得报告读漏）；
  * 表集合：老表一张不少，只多出四张新表；`room_master_table.identity_hash` 允许变（要记录）；
  * 用户版本号必须 1 → 2；`integrity_check` = ok；`foreign_key_check` 0 行；
  * 索引：`dream_tags` 只多 `index_dream_tags_tagId`，其余老表索引集合不变。

报告口径：业务表（dreams/tags/dream_tags/media_assets + 四张新表 = 8）+ room_master_table = 9。
设备上 sqlite_master 实际还会有 `android_metadata`（框架自己建的），所以原始表数 10；两数并列打印，
避免「9 还是 10」在报告里打架。

纯标准库，sqlite3 用法与 scripts/check-cost.py 一致（只读打开副本）。
"""

import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import sys
import tempfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - 老 Python / 非 tty
    pass

NEW_TABLES = ("novel_versions", "novel_messages", "generation_jobs", "llm_calls")
LEGACY_TABLES = ("dreams", "tags", "dream_tags", "media_assets")
FRAMEWORK_TABLES = {"android_metadata", "room_master_table"}
DREAMS_ADDED_COLUMNS = ("novelSelectedVersion", "generationStage")
EXPECTED_TAGS_INDEX = "index_dream_tags_tagId"
NEW_TABLE_INDICES = {
    "novel_versions": {"index_novel_versions_dreamId_version"},
    "novel_messages": {"index_novel_messages_dreamId_createdAt"},
    "llm_calls": {"index_llm_calls_dreamId_createdAt"},
    "generation_jobs": set(),
}


class InputError(Exception):
    """整个输入不可用：用法错、文件缺了、JSON 结构坏到没法比 → exit 2。"""


# ---------------------------------------------------------------- 序列化

def canon(value):
    """把 SQLite 的一个值转成可 JSON 化、且跨平台逐字可比的形式。

    浮点按 `repr` 存字符串：JSON 数字经过解析往返可能变成 `0.8500000000000001` 这种，
    而 `dream_tags.confidence` 的比对口径是「精确文本」，字符串最稳。
    """
    if value is None:
        return None
    if isinstance(value, float):
        return "f:" + repr(value)
    if isinstance(value, bool):
        return "i:" + str(int(value))
    if isinstance(value, int):
        return "i:" + str(value)
    if isinstance(value, bytes):
        return "x:" + value.hex()
    return "s:" + str(value)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------- dump

def find_db(directory):
    if os.path.isfile(directory):
        return directory
    if not os.path.isdir(directory):
        raise InputError(f"不是目录也不是文件: {directory}")
    candidates = [
        os.path.join(directory, name)
        for name in sorted(os.listdir(directory))
        if name.endswith(".db") and not name.endswith("-wal") and not name.endswith("-shm")
    ]
    if not candidates:
        raise InputError(f"目录里没有 .db 文件: {directory}")
    # 证据目录里可能同时躺着 baseline 与 upgraded 两份；优先名字里带 baseline 的
    preferred = [p for p in candidates if "baseline" in os.path.basename(p)]
    return (preferred or candidates)[0]


def copy_trio_to_temp(db_path):
    """把 db + -wal + -shm 拷进临时目录，返回 (临时 db 路径, 临时目录对象, 三件信息)。"""
    tmp = tempfile.TemporaryDirectory(prefix="check-migrate-")
    name = os.path.basename(db_path)
    target = os.path.join(tmp.name, name)
    files = []
    for suffix in ("", "-wal", "-shm"):
        src = db_path + suffix
        if os.path.exists(src):
            dst = target + suffix
            shutil.copy2(src, dst)
            files.append({"file": name + suffix, "bytes": os.path.getsize(src), "sha256": sha256_file(src)})
        else:
            files.append({"file": name + suffix, "bytes": None, "sha256": None})
    return target, tmp, files


def dump(db_path):
    target, tmp, files = copy_trio_to_temp(db_path)
    try:
        conn = sqlite3.connect(target)
        conn.row_factory = sqlite3.Row
        try:
            user_version = conn.execute("PRAGMA user_version").fetchone()[0]
            integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
            fk_rows = len(conn.execute("PRAGMA foreign_key_check").fetchall())
            tables = [
                r[0]
                for r in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
                )
            ]
            payload_tables = {}
            for table in tables:
                info = conn.execute(f'PRAGMA table_info("{table}")').fetchall()
                cols = [r[1] for r in info]
                pks = [r[1] for r in sorted(info, key=lambda r: r[5]) if r[5] > 0]
                order = ", ".join(f'"{c}"' for c in (pks or ["rowid"]))
                rows = []
                for row in conn.execute(f'SELECT * FROM "{table}" ORDER BY {order}'):
                    rows.append({c: canon(row[c]) for c in cols})
                indices = sorted(
                    r[0]
                    for r in conn.execute(
                        "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? "
                        "AND name NOT LIKE 'sqlite_%'",
                        (table,),
                    )
                )
                payload_tables[table] = {
                    "columns": cols,
                    "primary_key": pks,
                    "row_count": len(rows),
                    "rows": rows,
                    "indexes": indices,
                }
            identity = None
            if "room_master_table" in tables:
                got = conn.execute("SELECT identity_hash FROM room_master_table WHERE id = 42").fetchone()
                identity = got[0] if got else None
            return {
                "db_file": os.path.basename(db_path),
                "files": files,
                "user_version": user_version,
                "identity_hash": identity,
                "integrity_check": integrity,
                "foreign_key_check_rows": fk_rows,
                "tables": tables,
                "table_data": payload_tables,
            }
        finally:
            conn.close()
    finally:
        tmp.cleanup()


def dump_text(payload):
    lines = []
    db_name = payload["db_file"]
    lines.append(f"db={db_name}")
    for f in payload["files"]:
        if f["bytes"] is None:
            lines.append(f"{f['file']}=MISSING")
        else:
            lines.append(f"{f['file']}={f['bytes']}B sha256={f['sha256']}")
    lines.append(f"user_version={payload['user_version']}")
    lines.append(f"identity_hash={payload['identity_hash']}")
    business = [t for t in payload["tables"] if t not in FRAMEWORK_TABLES]
    lines.append(f"tables={len(business) + ('room_master_table' in payload['tables'])} raw_tables={len(payload['tables'])}")
    for table in payload["tables"]:
        data = payload["table_data"][table]
        lines.append(
            f"{table} rows={data['row_count']} cols={len(data['columns'])} indexes={','.join(data['indexes']) or '-'}"
        )
    lines.append(f"integrity_check={payload['integrity_check']}")
    lines.append(f"foreign_key_check_rows={payload['foreign_key_check_rows']}")
    lines.append("RESULT: DUMP-OK")
    return "\n".join(lines)


# ---------------------------------------------------------------- diff

def load_payload(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError) as exc:
        raise InputError(f"读不了 JSON: {path}: {exc}")


def row_key(table, row, pks):
    if pks:
        return tuple(row.get(c) for c in pks)
    return None


def index_rows(table, data):
    pks = data.get("primary_key") or []
    keyed = {}
    for row in data["rows"]:
        key = row_key(table, row, pks)
        if key is None:
            key = tuple(sorted(row.items()))
        if key in keyed:
            raise InputError(f"{table} 里主键重复: {key}")
        keyed[key] = row
    return keyed


def diff(before, after):
    pair = (before["user_version"], after["user_version"])
    if pair == (2, 3):
        return diff_2_3(before, after)
    return diff_1_2(before, after)


def diff_1_2(before, after):
    problems = []
    notes = []

    if before["user_version"] != 1 or after["user_version"] != 2:
        problems.append(
            f"user_version 必须 1→2，实际 {before['user_version']}→{after['user_version']}"
        )
    if before["integrity_check"].lower() != "ok":
        problems.append(f"迁移前 integrity_check={before['integrity_check']}")
    if after["integrity_check"].lower() != "ok":
        problems.append(f"迁移后 integrity_check={after['integrity_check']}")
    if before["foreign_key_check_rows"] != 0 or after["foreign_key_check_rows"] != 0:
        problems.append("foreign_key_check 有行（v1 四表零外键，v2 也不许加）")

    before_tables = set(before["tables"])
    after_tables = set(after["tables"])
    missing = before_tables - after_tables
    if missing:
        problems.append(f"升级后丢了表: {sorted(missing)}")
    added = after_tables - before_tables
    if added != set(NEW_TABLES):
        problems.append(f"新增表必须恰好是 {sorted(NEW_TABLES)}，实际 {sorted(added)}")

    rows_before = sum(before["table_data"][t]["row_count"] for t in LEGACY_TABLES if t in before_tables)
    rows_after_legacy = sum(after["table_data"][t]["row_count"] for t in LEGACY_TABLES if t in after_tables)

    lines = [f"tables=9 rows_before={rows_before} rows_after={rows_after_legacy}"]

    for table in LEGACY_TABLES:
        if table not in before_tables or table not in after_tables:
            continue
        b = before["table_data"][table]
        a = after["table_data"][table]
        b_cols, a_cols = b["columns"], a["columns"]
        if table == "dreams":
            extra = [c for c in a_cols if c not in b_cols]
            if extra != list(DREAMS_ADDED_COLUMNS):
                problems.append(f"dreams 新增列必须恰好是 {list(DREAMS_ADDED_COLUMNS)}，实际 {extra}")
            if [c for c in a_cols if c in b_cols] != b_cols:
                problems.append("dreams 老列顺序/集合被改了")
        elif a_cols != b_cols:
            problems.append(f"{table} 列集合被改了: {b_cols} → {a_cols}")
        if b["row_count"] != a["row_count"]:
            problems.append(f"{table} 行数变了: {b['row_count']} → {a['row_count']}")
            lines.append(f"{table} rows={b['row_count']}->{a['row_count']} FAIL")
            continue

        b_rows = index_rows(table, b)
        a_rows = index_rows(table, a)
        equal = 0
        null_new = 0
        for key, brow in b_rows.items():
            arow = a_rows.get(key)
            if arow is None:
                problems.append(f"{table} 丢行: {key}")
                continue
            for col in b_cols:
                if brow[col] != arow.get(col):
                    problems.append(f"{table} 列值变了: {key} {col}: {brow[col]!r} → {arow.get(col)!r}")
            if table == "dreams":
                for col in DREAMS_ADDED_COLUMNS:
                    if arow.get(col) is None:
                        null_new += 1
                    else:
                        problems.append(f"dreams 老行新列必须是 NULL: {key} {col}={arow.get(col)!r}")
            equal += 1
        if table == "dreams":
            expected_nulls = a["row_count"] * len(DREAMS_ADDED_COLUMNS)
            if null_new != expected_nulls:
                problems.append(f"dreams 新列 NULL 计数 {null_new} != 行数×2 {expected_nulls}")
            lines.append(f"dreams rows={a['row_count']} equal={equal} new_columns_null={null_new}/{expected_nulls}")
            lines.append(f"dreams updatedAt unchanged={equal}/{b['row_count']}")
        else:
            lines.append(f"{table} rows={a['row_count']} equal={equal}")

        before_idx = set(b["indexes"])
        after_idx = set(a["indexes"])
        if table == "dream_tags":
            if after_idx - before_idx != {EXPECTED_TAGS_INDEX}:
                problems.append(f"dream_tags 索引新增必须恰好是 {EXPECTED_TAGS_INDEX}，实际 {sorted(after_idx - before_idx)}")
            if before_idx - after_idx:
                problems.append(f"dream_tags 索引丢了: {sorted(before_idx - after_idx)}")
        elif before_idx != after_idx:
            problems.append(f"{table} 索引集合变了: {sorted(before_idx)} → {sorted(after_idx)}")

        lines.append(f"{table} indexes={','.join(sorted(after_idx)) or '-'}")

    for table in NEW_TABLES:
        if table not in after_tables:
            continue
        data = after["table_data"][table]
        if data["row_count"] != 0:
            problems.append(f"新表 {table} 迁移后必须是空表，实际 {data['row_count']} 行")
        expected = NEW_TABLE_INDICES[table]
        if set(data["indexes"]) != expected:
            problems.append(f"{table} 索引 {sorted(data['indexes'])} != {sorted(expected)}")
        lines.append(f"{table} rows={data['row_count']} indexes={','.join(data['indexes']) or '-'}")

    if before.get("identity_hash") == after.get("identity_hash"):
        problems.append("identity_hash 没变：schema 没换（或迁移根本没跑）")
    lines.append(f"identity_hash {before.get('identity_hash')} -> {after.get('identity_hash')}")

    lines.append(f"integrity_check={before['integrity_check']} -> {after['integrity_check']}")
    lines.append(f"foreign_key_check_rows={before['foreign_key_check_rows']} -> {after['foreign_key_check_rows']}")

    return lines, problems, notes



def diff_2_3(before, after):
    """v2 -> v3：只加 dreams.transcriptMs，并校验回填口径。"""
    problems = []
    notes = []

    if before["user_version"] != 2 or after["user_version"] != 3:
        problems.append(
            f"user_version 必须 2→3，实际 {before['user_version']}→{after['user_version']}"
        )
    for side, payload in (("迁移前", before), ("迁移后", after)):
        if payload["integrity_check"].lower() != "ok":
            problems.append(f"{side} integrity_check={payload['integrity_check']}")
        if payload["foreign_key_check_rows"] != 0:
            problems.append(f"{side} foreign_key_check 有行（v3 也不许加外键）")

    before_tables = set(before["tables"])
    after_tables = set(after["tables"])
    missing = before_tables - after_tables
    if missing:
        problems.append(f"升级后丢了表: {sorted(missing)}")
    added = after_tables - before_tables
    if added:
        problems.append(f"v2→v3 不许新增表，实际 {sorted(added)}")

    business = [t for t in sorted(before_tables & after_tables) if t not in FRAMEWORK_TABLES]
    lines = [f"pair=2->3 business_tables={len(business)}"]

    for table in business:
        b = before["table_data"][table]
        a = after["table_data"][table]
        b_cols, a_cols = b["columns"], a["columns"]
        if table == "dreams":
            extra = [c for c in a_cols if c not in b_cols]
            if extra != ["transcriptMs"]:
                problems.append(f"dreams 新增列必须恰好是 ['transcriptMs']，实际 {extra}")
            if [c for c in a_cols if c in b_cols] != b_cols:
                problems.append("dreams 老列顺序/集合被改了")
        elif a_cols != b_cols:
            problems.append(f"{table} 列集合被改了: {b_cols} → {a_cols}")

        if b["row_count"] != a["row_count"]:
            problems.append(f"{table} 行数变了: {b['row_count']} → {a['row_count']}")
            lines.append(f"{table} rows={b['row_count']}->{a['row_count']} FAIL")
            continue

        if set(b["indexes"]) != set(a["indexes"]):
            problems.append(f"{table} 索引集合变了: {sorted(b['indexes'])} → {sorted(a['indexes'])}")

        b_rows = index_rows(table, b)
        a_rows = index_rows(table, a)
        equal = 0
        for key, brow in b_rows.items():
            arow = a_rows.get(key)
            if arow is None:
                problems.append(f"{table} 丢行: {key}")
                continue
            for col in b_cols:
                if brow[col] != arow.get(col):
                    problems.append(f"{table} 列值变了: {key} {col}: {brow[col]!r} → {arow.get(col)!r}")
            if table == "dreams":
                raw_ok = bool((brow.get("rawTranscript") or "").strip())
                expected = (
                    brow.get("rawAudioMs")
                    if brow.get("rawAudioPath") not in (None, "")
                    and brow.get("rawAudioMs") is not None
                    and brow.get("transcriptStatus") == "OK"
                    and raw_ok
                    else 0
                )
                if arow.get("transcriptMs") != expected:
                    problems.append(
                        f"dreams transcriptMs 回填错: {key} 期望 {expected} 实际 {arow.get('transcriptMs')}"
                    )
            equal += 1
        lines.append(f"{table} rows={a['row_count']} equal={equal}")

    if before.get("identity_hash") == after.get("identity_hash"):
        problems.append("identity_hash 没变：schema 没换（或迁移根本没跑）")
    lines.append(f"identity_hash {before.get('identity_hash')} -> {after.get('identity_hash')}")
    lines.append(f"integrity_check={before['integrity_check']} -> {after['integrity_check']}")
    lines.append(
        f"foreign_key_check_rows={before['foreign_key_check_rows']} -> {after['foreign_key_check_rows']}"
    )

    return lines, problems, notes


# ---------------------------------------------------------------- main

def main(argv=None):
    parser = argparse.ArgumentParser(description="DreamKeeper v1→v2 迁移库比对")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--dump", metavar="DB_OR_DIR", help="设备拉下来的 db（或含三件的目录）")
    group.add_argument("--diff", nargs=2, metavar=("BEFORE", "AFTER"), help="两份 dump JSON")
    parser.add_argument("--out", help="--dump 的 JSON 输出路径（缺省打印到 stdout）")
    parser.add_argument("--apk-sha256", help="可选：写进 dump 元数据的 v2 APK sha256")
    args = parser.parse_args(argv)

    try:
        if args.dump:
            payload = dump(find_db(args.dump))
            if args.apk_sha256:
                payload["apk_sha256"] = args.apk_sha256
            text = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True)
            if args.out:
                with open(args.out, "w", encoding="utf-8", newline="\n") as f:
                    f.write(text + "\n")
                print(dump_text(payload))
                print(f"json={args.out}")
            else:
                print(text)
            return 0

        before = load_payload(args.diff[0])
        after = load_payload(args.diff[1])
        lines, problems, notes = diff(before, after)
        for line in lines:
            print(line)
        for note in notes:
            print(f"NOTE {note}")
        if problems:
            for p in problems:
                print(f"FAIL {p}")
            print(f"RESULT: RED: {len(problems)} 处不一致")
            return 1
        print("RESULT: GREEN")
        return 0
    except InputError as exc:
        print(f"INPUT ERROR: {exc}", file=sys.stderr)
        return 2
    except sqlite3.Error as exc:
        print(f"SQLITE ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
