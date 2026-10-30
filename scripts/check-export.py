#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-export.py —— Plan 3 导出验证闸门（Task 10 §0.5，D357）

用法：
  # txt / docx 共用 DB 前置：--from-db 的 sha256 必须等于 sidecar 的 MERGED_SHA256
  python scripts/check-export.py --txt <file> --file-name <name> --from-db <db> --dream-id <id>
  python scripts/check-export.py --docx <file> --file-name <name> --from-db <db> --dream-id <id>
  python scripts/check-export.py --pdf <file> [--file-name <name>]
  python scripts/check-export.py --txt ... --mutation      # 负向自查（只在 .scratch/ 副本上做）
  python scripts/check-export.py --self-test               # 纯标准库夹具自测，可离线复跑

退出码：
  0  RESULT: GREEN
  1  RESULT: RED（断言不成立）
  2  RESULT: RED（输入/用法错误，例如 sidecar 缺失、DB sha 不符）

设计取舍：
  * 期望串不是从导出文件反推，而是**只从 DB 推导**（D357）：正文用
    resolveFinalNovel(selected ?: maxVersion) 的口径；头部四行、并 `共 N 字` 的 N
    用与 Kotlin FaithfulGates.contentCharCount 相同的字符集逐码点复刻。
  * 前置 sha 对齐 D371 的 sidecar：合并后的 .db 才是取证对象；DEVICE_DB_SHA256
    只作溯源、不参与断言。
  * pdf 只做结构级断言（D338）：不抽取文本、不做像素判定。
  * --mutation 先复制到 .scratch/ 再改（D372），结束恢复并核对原件 sha256 未变；
    txt 额外做 CRLF 负向（.gitattributes 入库时会把 CRLF 静默转成 LF，不造样本查不出来）。
  * --sidecar 可覆盖 sidecar 路径（默认 docs/evidence/plan3/p3-e2e-08-db-sidecar.txt），
    供 Phase A host-only 夹具自测使用；真实 E2E 一律用默认路径。
"""

import argparse
import hashlib
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import urllib.parse
import zipfile
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

try:  # Python >= 3.9；Windows 上需要 tzdata，没有就退回固定 +08:00
    from zoneinfo import ZoneInfo

    CST = ZoneInfo("Asia/Shanghai")
except Exception:  # pragma: no cover - 兜底路径
    from datetime import timedelta

    CST = timezone(timedelta(hours=8))

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SIDECAR = os.path.join(REPO_ROOT, "docs", "evidence", "plan3", "p3-e2e-08-db-sidecar.txt")
SCRATCH_DIR = os.path.join(REPO_ROOT, ".scratch", "check-export-mutations")

EXPECTED_TABLES = 9
EXPECTED_USER_VERSION = 2

# FaithfulGates.PUNCT（Kotlin 端 34 个字符，逐字一致）
PUNCT = "，。！？；：、,.!?;:…—\u3000 \n\r\t“”\"'（）()《》【】[]"
# FaithfulGates.isWhitespace（含 0x85/0xA0/0x202F 等 Java isWhitespace 不含的码点）
EXTRA_WHITESPACE = {
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0,
    0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
} | set(range(0x2000, 0x200B))
# Java Char.isWhitespace（Kotlin trim/isWhitespace 口径；用于标题清洗与 trim）
JAVA_WHITESPACE = {
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x1680,
    0x2028, 0x2029, 0x205F, 0x3000,
} | set(range(0x2000, 0x2007)) | set(range(0x2008, 0x200B))

ILLEGAL_TITLE_CHARS = set('\\/:*?"<>|')

W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
DOCX_PARTS = ["[Content_Types].xml", "_rels/.rels", "word/document.xml"]


class InputError(Exception):
    """输入不可用：sidecar 缺失、DB sha 不符、dream 不存在、表结构不足 → exit 2。"""


# ---------------------------------------------------------------- 基础工具

def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    with open(path, "rb") as f:
        return sha256_bytes(f.read())


def read_bytes(path):
    with open(path, "rb") as f:
        return f.read()


def content_char_count(text):
    """Kotlin FaithfulGates.contentCharCount 的逐码点等价实现：跳过空白与 PUNCT。"""
    n = 0
    for ch in text:
        cp = ord(ch)
        if cp in EXTRA_WHITESPACE:
            continue
        if cp <= 0xFFFF and ch in PUNCT:
            continue
        n += 1
    return n


def is_iso_control(ch):
    cp = ord(ch)
    return cp <= 0x1F or 0x7F <= cp <= 0x9F


def java_is_whitespace(ch):
    return ord(ch) in JAVA_WHITESPACE


def java_trim(text):
    start, end = 0, len(text)
    while start < end and java_is_whitespace(text[start]):
        start += 1
    while end > start and java_is_whitespace(text[end - 1]):
        end -= 1
    return text[start:end]


def header_title(raw):
    """ExportNaming.headerTitle：换行压空格、trim、空标题兜底 dream。"""
    flat = (raw or "").replace("\r", " ").replace("\n", " ")
    flat = java_trim(flat)
    return flat if flat else "dream"


def clean_title(raw):
    """ExportNaming.cleanTitle：剔非法字符、空白/控制字符折叠成单空格、截 20 码点。"""
    out = []
    started = False
    pending_space = False
    for ch in raw or "":
        if ch in ILLEGAL_TITLE_CHARS:
            continue
        if java_is_whitespace(ch) or is_iso_control(ch):
            if started:
                pending_space = True
            continue
        if pending_space:
            out.append(" ")
            pending_space = False
        out.append(ch)
        started = True
    collapsed = "".join(out)
    if not collapsed:
        return "dream"
    # Python 的 str 切片按 code point 计数，与 Kotlin takeCodePoints 等价
    return collapsed[:20]


def file_name(doc, extension):
    date_part = doc["date"].replace("-", "")
    tail = "-raw" if doc["raw"] else "-v%d" % doc["version"]
    return "%s-%s%s.%s" % (date_part, clean_title(doc["title_raw"]), tail, extension)


def date_line(date_text, created_at):
    """ExportDates.dateLine：日期取 dreamDate；时分取 createdAt 的上海时区；<=0 → 00:00。"""
    if created_at is not None and created_at > 0:
        at = datetime.fromtimestamp(created_at / 1000.0, tz=timezone.utc).astimezone(CST)
        hhmm = at.strftime("%H:%M")
    else:
        hhmm = "00:00"
    return "%s %s" % (date_text, hhmm)


# ---------------------------------------------------------------- DB 前置与推导

def read_sidecar(path):
    if not os.path.exists(path):
        raise InputError(
            "缺少 DB sidecar：%s\n"
            "（D371：先拉三件 DB、wal_checkpoint(TRUNCATE) 合并，并写入 MERGED_SHA256=）" % path)
    values = {}
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if "=" in line and not line.startswith("#"):
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return values


def open_ro(path):
    if not os.path.exists(path):
        raise InputError("库文件不存在：%s" % path)
    uri = "file:%s?mode=ro" % urllib.parse.quote(path.replace("\\", "/"))
    try:
        conn = sqlite3.connect(uri, uri=True, timeout=5)
        conn.row_factory = sqlite3.Row
        return conn
    except sqlite3.Error as e:
        raise InputError("打不开库（只读）：%s（%s）" % (path, e))


def verify_db(db_path, sidecar_path):
    """§0.5 共用前置：sha256 == sidecar MERGED_SHA256、integrity ok、version 2、9 表、关键表非空。"""
    sidecar = read_sidecar(sidecar_path)
    merged = sidecar.get("MERGED_SHA256", "").strip().lower()
    if not merged:
        raise InputError("sidecar 里没有 MERGED_SHA256=：%s" % sidecar_path)
    actual = sha256_file(db_path)
    if actual != merged:
        raise InputError(
            "DB sha256 与 sidecar 不符：实际 %s != MERGED_SHA256 %s（D371/D372：别用被改动过的副本）"
            % (actual, merged))
    conn = open_ro(db_path)
    try:
        integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise InputError("PRAGMA integrity_check != ok：%r" % (integrity,))
        user_version = conn.execute("PRAGMA user_version").fetchone()[0]
        if user_version != EXPECTED_USER_VERSION:
            raise InputError("user_version=%r != %d" % (user_version, EXPECTED_USER_VERSION))
        # Android 的 SQLiteDatabase 会自动建 android_metadata，Room 另建 room_master_table。
        # 计划「表数==9」指 8 张 @Entity 表 + room_master_table；android_metadata 与
        # sqlite_ 内部表不计入（设备真库实测 10 张表 = 9 + android_metadata）。
        tables = [r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT IN ('android_metadata') AND name NOT LIKE 'sqlite_%' "
            "ORDER BY name")]
        if len(tables) != EXPECTED_TABLES:
            raise InputError("表数=%d != %d（不含 android_metadata）：%r"
                             % (len(tables), EXPECTED_TABLES, tables))
        for table in ("novel_versions", "llm_calls"):
            count = conn.execute("SELECT COUNT(*) FROM %s" % table).fetchone()[0]
            if count <= 0:
                raise InputError("%s 为空（D357：这是 DB 与导出一致性的真正锚）" % table)
    finally:
        conn.close()
    return {"merged_sha256": merged, "device_sha256": sidecar.get("DEVICE_DB_SHA256", "")}


def derive_document(conn, dream_id):
    """按 Kotlin resolveFinalNovel + AndroidExporter.buildDocument 的口径从 DB 推导期望值。"""
    dream = conn.execute("SELECT * FROM dreams WHERE id = ?", (dream_id,)).fetchone()
    if dream is None:
        raise InputError("DB 里没有 dream id=%r" % dream_id)
    columns = set(dream.keys())
    dream = {key: dream[key] for key in columns}

    versions = [dict(row) for row in conn.execute(
        "SELECT version, text, promptId FROM novel_versions WHERE dreamId = ? ORDER BY version DESC",
        (dream_id,))]
    if versions:
        selected = dream.get("novelSelectedVersion")
        chosen = next((v for v in versions if v["version"] == selected), versions[0])
        body = chosen["text"]
        version = chosen["version"]
    else:
        text = dream.get("novelText")
        if text is None:
            text = dream.get("faithfulText")
        if text is None:
            text = dream.get("rawTranscript")
        body = text
        version = dream.get("novelVersion") or 0
    if body is None or body.strip() == "":
        raise InputError(
            "DB 推导正文为空（dream=%r）：按 §0.4 这篇不应该有导出文件" % dream_id)
    body = body.replace("\r\n", "\n").replace("\r", "\n")

    tag_rows = conn.execute(
        "SELECT tags.id AS id, tags.name AS name FROM tags "
        "INNER JOIN dream_tags ON tags.id = dream_tags.tagId "
        "WHERE dream_tags.dreamId = ?", (dream_id,)).fetchall()
    tags = [row["name"] for row in sorted(tag_rows, key=lambda row: row["id"])]

    raw = False
    if versions:
        raw = not any(v["promptId"] != "faithful" for v in versions)
    else:
        raw = (dream.get("novelText") is not None) or ((dream.get("novelVersion") or 0) > 0)

    created_at = dream.get("createdAt")
    return {
        "title_raw": dream.get("title"),
        "title": header_title(dream.get("title")),
        "date": dream.get("dreamDate"),
        "date_line": date_line(dream.get("dreamDate"), created_at),
        "tags": tags,
        "body": body,
        "version": version,
        "raw": raw,
    }


# ---------------------------------------------------------------- 期望串

def header_lines(doc):
    lines = ["# " + doc["title"], doc["date_line"]]
    if doc["tags"]:
        lines.append("标签：" + "、".join(doc["tags"]))
    return lines


def expected_txt(doc):
    text = "\n".join(header_lines(doc)) + "\n\n"
    text += doc["body"]
    text += "\n共 %d 字" % content_char_count(doc["body"])
    return text


def expected_paragraphs(doc):
    """D374：头部第 4 行空行不单独成段；正文每行一段（空行→空段）。"""
    return header_lines(doc) + doc["body"].split("\n")


# ---------------------------------------------------------------- 检查器

class Checker:
    def __init__(self, quiet=False):
        self.lines = []
        self.failures = []
        self.quiet = quiet

    def ok(self, label):
        if not self.quiet:
            self.lines.append("[OK] " + label)

    def fail(self, label, detail):
        self.failures.append((label, detail))
        if not self.quiet:
            self.lines.append("[MISMATCH] %s：%s" % (label, detail))

    def expect(self, condition, label, detail):
        if condition:
            self.ok(label)
        else:
            self.fail(label, detail)
        return bool(condition)

    def dump(self):
        for line in self.lines:
            print(line)


def check_file_name(checker, path, doc, extension, file_name_arg):
    expected = file_name(doc, extension)
    actual = os.path.basename(path)
    checker.expect(actual == expected, "文件名==DB 推导名",
                   "实际 %r，期望 %r" % (actual, expected))
    if file_name_arg:
        checker.expect(file_name_arg == expected, "--file-name==DB 推导名",
                       "--file-name=%r，期望 %r" % (file_name_arg, expected))


def decode_utf8(checker, data, label):
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError as e:
        checker.fail(label, "不是合法 UTF-8：%s" % e)
        return None


def check_txt(checker, path, doc, file_name_arg, expect_sha=None):
    data = read_bytes(path)
    expected = expected_txt(doc).encode("utf-8")
    checker.expect(not data.startswith(b"\xef\xbb\xbf"), "无 BOM",
                   "文件以 EF BB BF 开头")
    checker.expect(data == expected, "逐字节 == DB 推导期望串",
                   _first_diff(data, expected))
    checker.expect(b"\r" not in data, "\\r 计数为 0",
                   "文件含 %d 个 CR" % data.count(b"\r"))
    text = decode_utf8(checker, data, "可解码 UTF-8")
    if text is not None:
        checker.expect(text.startswith("#"), "首字符是 #", "首字符是 %r" % (text[:1],))
        match = re.search(r"共 (\d+) 字$", text)
        want_n = content_char_count(doc["body"])
        checker.expect(match is not None, "footer 形如 `共 N 字`", "找不到 footer")
        if match is not None:
            checker.expect(int(match.group(1)) == want_n, "footer N == contentCharCount(正文)",
                           "footer N=%s，正文算出 %d" % (match.group(1), want_n))
    if file_name_arg:
        check_file_name(checker, path, doc, "txt", file_name_arg)
    if expect_sha:
        actual = sha256_bytes(data)
        checker.expect(actual == expect_sha.strip().lower(), "--expect-sha 相符",
                       "实际 %s != %s" % (actual, expect_sha))


def _first_diff(actual, expected):
    limit = min(len(actual), len(expected))
    for i in range(limit):
        if actual[i] != expected[i]:
            return "首个差异 @%d：实际 %r…，期望 %r…" % (
                i, actual[max(0, i - 10):i + 20], expected[max(0, i - 10):i + 20])
    if len(actual) != len(expected):
        return "长度 %d != %d" % (len(actual), len(expected))
    return "内容相同"


def check_docx(checker, path, doc, file_name_arg):
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            checker.expect(set(names) == set(DOCX_PARTS), "zip 只含三件套",
                           "实际条目 %r" % (names,))
            document_raw = archive.read("word/document.xml")
            rels_raw = archive.read("_rels/.rels")
            content_types_raw = archive.read("[Content_Types].xml")
    except (zipfile.BadZipFile, KeyError) as e:
        checker.fail("zip 可打开且三件齐全", str(e))
        return
    try:
        root = ET.fromstring(document_raw)
        checker.ok("document.xml 可 xml.etree 解析")
    except ET.ParseError as e:
        checker.fail("document.xml 可 xml.etree 解析", str(e))
        return
    checker.expect(b'<w:rPr><w:b/></w:rPr>' in document_raw, "首段 run 带 <w:rPr><w:b/></w:rPr>",
                   "document.xml 里找不到加粗 run 属性")
    checker.expect(document_raw.count(b"<w:rPr><w:b/></w:rPr>") == 1,
                   "全文只有标题段加粗（<w:rPr><w:b/></w:rPr> 恰好 1 处）",
                   "加粗 run 属性出现 %d 次" % document_raw.count(b"<w:rPr><w:b/></w:rPr>"))

    paragraphs = []
    for para in root.iter("{%s}p" % W_NS):
        text = "".join(node.text or "" for node in para.iter("{%s}t" % W_NS))
        paragraphs.append(text)
    expected = expected_paragraphs(doc)
    checker.expect(len(paragraphs) == len(expected), "段数 == 头部段数 + 正文行数",
                   "实际 %d 段，期望 %d 段" % (len(paragraphs), len(expected)))
    if paragraphs:
        checker.expect(paragraphs[0] == expected[0], "首段 == 头部第 1 行（# 标题）",
                       "首段 %r，期望 %r" % (paragraphs[0], expected[0]))
    checker.expect(paragraphs == expected, "paragraph 序列 == DB 推导期望串（含空行空段）",
                   _first_diff("\n".join(paragraphs), "\n".join(expected)))

    joined = "\n".join(paragraphs)
    illegal = [ch for ch in joined if ord(ch) <= 0x1F and ord(ch) not in (0x09, 0x0A, 0x0D)]
    checker.expect(not illegal, "无非法控制字符",
                   "含 %r" % (illegal[:5],))
    checker.expect(not any(0xD800 <= ord(ch) <= 0xDFFF for ch in joined), "无孤立代理项",
                   "含孤立代理项")

    try:
        rels = ET.fromstring(rels_raw)
        targets = [rel.get("Target") for rel in rels.iter("{%s}Relationship" % REL_NS)
                   if (rel.get("Type") or "").endswith("/officeDocument")]
        checker.expect("word/document.xml" in targets, "_rels 关系完整（officeDocument）",
                       "rels 里找不到 word/document.xml：%r" % (targets,))
    except ET.ParseError as e:
        checker.fail("_rels 可解析", str(e))
    checker.expect(b"/word/document.xml" in content_types_raw, "[Content_Types] 覆盖 document.xml",
                   "内容类型缺 word/document.xml")
    if file_name_arg:
        check_file_name(checker, path, doc, "docx", file_name_arg)


def check_pdf(checker, path, file_name_arg=None):
    data = read_bytes(path)
    checker.expect(data[:5] == b"%PDF-", "%PDF- 在 byte 0", "开头 %r" % (data[:8],))
    checker.expect(b"%%EOF" in data[-200:], "尾部 200 字节内有 %%EOF",
                   "最后 200 字节无 %%EOF")
    checker.expect(re.search(rb"/Type\s*/Pages", data) is not None, "有 /Type /Pages",
                   "找不到 /Type /Pages")
    page_count = len(re.findall(rb"/Type\s*/Page\b", data))
    checker.expect(page_count >= 1, "至少一个 /Type /Page", "找到 0 个页对象")
    match = re.search(rb"/Count\s+(\d+)", data)
    checker.expect(match is not None, "有 /Count", "找不到 /Count")
    if match is not None:
        checker.expect(int(match.group(1)) == page_count, "/Count == /Type /Page 对象数",
                       "/Count=%s，页对象 %d 个" % (match.group(1), page_count))
    contents_refs = re.findall(rb"/Contents\s+(\d+)\s+0\s+R", data)
    checker.expect(len(contents_refs) >= page_count, "每页 /Contents 非空",
                   "/Contents 引用 %d 个 < 页数 %d" % (len(contents_refs), page_count))
    for ref in contents_refs:
        checker.expect(re.search(rb"(?m)^%s 0 obj" % re.escape(ref), data) is not None,
                       "/Contents 引用对象 %s 存在" % ref.decode(), "对象缺失")
    checker.expect(len(re.findall(rb"/MediaBox", data)) >= page_count, "每页有 MediaBox",
                   "MediaBox 少于页数")
    has_base_font = re.search(rb"/BaseFont", data) is not None
    # 偏差候选（待 Lead 分配 D 号）：计划 §0.5 写「至少一处 /BaseFont」，但 Android
    # android.graphics.pdf.PdfDocument 在设备上把 CJK 字形嵌入为 Type3 字体
    # （/Type /Font + /Subtype /Type3 + /FontMatrix），不产生 /BaseFont。仍要求字体对象存在，
    # 只是接受 Type3 作为文本绘制锚；其余 pdf 断言不变。
    type3_count = len(re.findall(rb"/Subtype\s*/Type3", data))
    has_type3_font = (type3_count >= 1
                      and re.search(rb"/Type\s*/Font", data) is not None
                      and re.search(rb"/FontMatrix", data) is not None)
    if not checker.quiet:
        if has_base_font:
            print("[INFO] PDF 字体锚：/BaseFont 存在")
        elif has_type3_font:
            print("[INFO] PDF 字体锚：Type3 字体对象 %d 处（Android PdfDocument 嵌入 CJK 字形，无 /BaseFont；偏差候选待编号）"
                  % type3_count)
    checker.expect(has_base_font or has_type3_font,
                   "至少一处字体绘制锚（/BaseFont 或 Type3 /Font+/FontMatrix）",
                   "找不到 /BaseFont，也没有 Type3 字体对象")
    checker.expect(len(data) > 2048, "字节数 > 2KB", "只有 %d 字节" % len(data))
    checker.expect(b"/Encrypt" not in data, "无 /Encrypt", "PDF 被加密")
    if file_name_arg:
        checker.expect(os.path.basename(path) == file_name_arg, "文件名 == --file-name",
                       "实际 %r != %r" % (os.path.basename(path), file_name_arg))


# ---------------------------------------------------------------- 变异自查

def _mutation_dir():
    os.makedirs(SCRATCH_DIR, exist_ok=True)
    return tempfile.mkdtemp(prefix="mut-", dir=SCRATCH_DIR)


def run_mutations(kind, path, doc, file_name_arg, expect_sha=None):
    """D372：只在 .scratch/ 副本上做负向；结束核对原件 sha256 未变。"""
    work = _mutation_dir()
    original_sha = sha256_file(path)
    cases = []
    try:
        if kind == "txt":
            data = read_bytes(path)
            text = data.decode("utf-8")
            index = len(text) // 2
            replacement = "X" if text[index] != "X" else "Y"
            mutated = (text[:index] + replacement + text[index + 1:]).encode("utf-8")
            cases.append(("改正文/期望串一个字符", mutated))
            cases.append(("CRLF 换行", data.replace(b"\n", b"\r\n")))
        elif kind == "docx":
            with open(path, "rb") as f:
                raw = f.read()
            buffer = _rewrite_docx_without_first_paragraph(raw, work)
            cases.append(("删 docx 一个 w:p", buffer))
        elif kind == "pdf":
            cases.append(("PDF 尾部追加垃圾（破坏 %%EOF）", read_bytes(path) + b"X" * 300))
        else:  # pragma: no cover
            raise InputError("未知变异类型：%r" % kind)

        all_red = True
        for label, mutated in cases:
            mutated_path = os.path.join(work, os.path.basename(path))
            with open(mutated_path, "wb") as f:
                f.write(mutated)
            checker = Checker(quiet=True)
            _run_checks(checker, kind, mutated_path, doc, file_name_arg, expect_sha)
            red = bool(checker.failures)
            all_red = all_red and red
            print("[MUTATION] %s → %s" % (
                label, "RED（符合预期）" if red else "GREEN（不符合预期！）"))
            if not red:
                print("           变异没被抓住：check-export 的断言有漏")
        return all_red
    finally:
        shutil.rmtree(work, ignore_errors=True)
        unchanged = sha256_file(path) == original_sha
        print("[MUTATION] 原件 sha256 未变：%s（%s）"
              % ("是" if unchanged else "否", original_sha))
        if not unchanged:
            raise InputError("负向对照污染了原件：%s" % path)


def _rewrite_docx_without_first_paragraph(raw, work):
    source = os.path.join(work, "source.docx")
    with open(source, "wb") as f:
        f.write(raw)
    with zipfile.ZipFile(source) as archive:
        entries = {name: archive.read(name) for name in archive.namelist()}
    document = entries["word/document.xml"]
    mutated_document = re.sub(rb"<w:p>.*?</w:p>", b"", document, count=1, flags=re.S)
    entries["word/document.xml"] = mutated_document
    out_path = os.path.join(work, "mutated.docx")
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in DOCX_PARTS:
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, entries[name])
    return read_bytes(out_path)


# ---------------------------------------------------------------- 子命令

def _run_checks(checker, kind, path, doc, file_name_arg, expect_sha):
    if kind == "txt":
        check_txt(checker, path, doc, file_name_arg, expect_sha)
    elif kind == "docx":
        check_docx(checker, path, doc, file_name_arg)
    elif kind == "pdf":
        check_pdf(checker, path, file_name_arg)
    else:  # pragma: no cover
        raise InputError("未知格式：%r" % kind)


def run_cli(a):
    mode = "txt" if a.txt else "docx" if a.docx else "pdf" if a.pdf else None
    if mode is None:
        raise InputError("需要且只允许一个：--txt / --docx / --pdf（自测用 --self-test）")
    path = {"txt": a.txt, "docx": a.docx, "pdf": a.pdf}[mode]
    if not os.path.exists(path):
        raise InputError("导出文件不存在：%s" % path)

    doc = None
    if mode in ("txt", "docx"):
        if not a.from_db or not a.dream_id:
            raise InputError("--txt/--docx 需要 --from-db 与 --dream-id")
        verify_db(a.from_db, a.sidecar)
        conn = open_ro(a.from_db)
        try:
            doc = derive_document(conn, a.dream_id)
        finally:
            conn.close()
    elif a.from_db and a.dream_id:
        # pdf 不强制 DB；若给了 DB 就顺便校验一遍，保持三子命令同一批证据可用同一条命令。
        verify_db(a.from_db, a.sidecar)
        conn = open_ro(a.from_db)
        try:
            doc = derive_document(conn, a.dream_id)
        finally:
            conn.close()

    checker = Checker()
    _run_checks(checker, mode, path, doc, a.file_name, a.expect_sha)
    good = not checker.failures

    if a.mutation:
        if not good:
            print("[MUTATION] 原件本身不绿，无法解读变异：先修正原件")
            good = False
        else:
            good = run_mutations(mode, path, doc, a.file_name, a.expect_sha) and good

    checker.dump()
    print("RESULT: %s" % ("GREEN" if good else "RED"))
    return 0 if good else 1


# ---------------------------------------------------------------- 自测（纯标准库夹具）

def _ref_render_txt(doc):
    return expected_txt(doc).encode("utf-8")


def _escape_xml(text):
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "&apos;"))


def _sanitize_xml(text):
    out = []
    for ch in text:
        cp = ord(ch)
        if cp <= 0x08 or cp in (0x0B, 0x0C) or 0x0E <= cp <= 0x1F:
            continue
        if 0xD800 <= cp <= 0xDFFF:
            continue
        out.append(ch)
    return "".join(out)


def _ref_render_docx(doc):
    def paragraph(text, bold):
        props = "<w:rPr><w:b/></w:rPr>" if bold else ""
        return ('<w:p><w:r>%s<w:t xml:space="preserve">%s</w:t></w:r></w:p>'
                % (props, _escape_xml(_sanitize_xml(text))))

    paragraphs = [paragraph("# " + doc["title"], True)]
    paragraphs.append(paragraph(doc["date_line"], False))
    if doc["tags"]:
        paragraphs.append(paragraph("标签：" + "、".join(doc["tags"]), False))
    for line in doc["body"].split("\n"):
        paragraphs.append(paragraph(line, False))
    document = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                '<w:document xmlns:w="%s"><w:body>%s</w:body></w:document>'
                % (W_NS, "".join(paragraphs)))
    content_types = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="%s">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/word/document.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
        '</Types>' % CT_NS)
    rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="%s">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
        'Target="word/document.xml"/></Relationships>' % REL_NS)
    entries = {
        "[Content_Types].xml": content_types,
        "_rels/.rels": rels,
        "word/document.xml": document,
    }
    buffer = tempfile.NamedTemporaryFile(suffix=".docx", delete=False)
    buffer.close()
    try:
        with zipfile.ZipFile(buffer.name, "w", zipfile.ZIP_DEFLATED) as archive:
            for name in DOCX_PARTS:
                info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, entries[name])
        return read_bytes(buffer.name)
    finally:
        os.unlink(buffer.name)


def _ref_render_pdf():
    body = (b"%PDF-1.4\n"
            b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
            b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
            b"3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
            b"/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n"
            b"4 0 obj\n<< /Length 2 >>\nstream\n \nendstream\nendobj\n"
            b"5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
    return body + b"% " + b"P" * 3000 + b"\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n"


def _build_self_test_fixture(workdir):
    db_path = os.path.join(workdir, "fixture.db")
    sidecar_path = os.path.join(workdir, "fixture-sidecar.txt")
    if os.path.exists(db_path):
        os.remove(db_path)
    conn = sqlite3.connect(db_path)
    conn.executescript("""
        CREATE TABLE dreams (
            id TEXT NOT NULL PRIMARY KEY, dreamDate TEXT NOT NULL, createdAt INTEGER NOT NULL,
            title TEXT, rawTranscript TEXT NOT NULL, faithfulText TEXT, novelText TEXT,
            novelVersion INTEGER NOT NULL, novelSelectedVersion INTEGER);
        CREATE TABLE tags (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE dream_tags (dreamId TEXT NOT NULL, tagId TEXT NOT NULL);
        CREATE TABLE media_assets (id TEXT NOT NULL PRIMARY KEY);
        CREATE TABLE novel_versions (
            dreamId TEXT NOT NULL, version INTEGER NOT NULL, text TEXT NOT NULL,
            promptId TEXT NOT NULL);
        CREATE TABLE novel_messages (id TEXT NOT NULL PRIMARY KEY);
        CREATE TABLE generation_jobs (id TEXT NOT NULL PRIMARY KEY);
        CREATE TABLE llm_calls (id TEXT NOT NULL PRIMARY KEY, error TEXT);
        CREATE TABLE room_master_table (identity_hash TEXT);
        PRAGMA user_version = 2;
    """)
    conn.execute("INSERT INTO dreams VALUES (?,?,?,?,?,?,?,?,?)",
                 ("dream-1", "2026-10-22", 0, "电梯", "我梦见电梯一直往下。", "我梦见电梯一直往下。",
                  None, 0, None))
    conn.execute("INSERT INTO dreams VALUES (?,?,?,?,?,?,?,?,?)",
                 ("dream-2", "2026-10-23", 0, None, "我从楼梯上醒来。", "我从楼梯上醒来。",
                  None, 0, None))
    conn.execute("INSERT INTO tags VALUES (?,?)", ("tag-b", "电梯"))
    conn.execute("INSERT INTO tags VALUES (?,?)", ("tag-a", "下坠的预感"))
    conn.execute("INSERT INTO dream_tags VALUES (?,?)", ("dream-1", "tag-b"))
    conn.execute("INSERT INTO dream_tags VALUES (?,?)", ("dream-1", "tag-a"))
    conn.execute("INSERT INTO novel_versions VALUES (?,?,?,?)",
                 ("dream-1", 2, "我梦见电梯一直往下。\n\n门开了。", "novel"))
    conn.execute("INSERT INTO novel_versions VALUES (?,?,?,?)",
                 ("dream-1", 1, "旧版本。", "novel"))
    conn.execute("INSERT INTO llm_calls VALUES (?,?)", ("call-1", None))
    conn.commit()
    conn.close()
    with open(sidecar_path, "w", encoding="utf-8") as f:
        f.write("APK_SHA256=%s\n" % ("a" * 64))
        f.write("DEVICE_TIME=host-only\n")
        f.write("DEVICE_DB_SHA256=%s\n" % ("b" * 64))
        f.write("MERGED_SHA256=%s\n" % sha256_file(db_path))
        f.write("DEVICE_DB_BYTES=0\nMERGED_DB_BYTES=%d\n" % os.path.getsize(db_path))
    return db_path, sidecar_path


def self_test():
    workdir = os.path.join(REPO_ROOT, ".scratch", "check-export-self-test")
    if os.path.isdir(workdir):
        shutil.rmtree(workdir)
    os.makedirs(workdir, exist_ok=True)
    db_path, sidecar_path = _build_self_test_fixture(workdir)
    failures = 0

    for dream_id, kind in (("dream-1", "txt"), ("dream-1", "docx"),
                           ("dream-2", "txt"), ("dream-2", "docx")):
        verify_db(db_path, sidecar_path)
        conn = open_ro(db_path)
        try:
            doc = derive_document(conn, dream_id)
        finally:
            conn.close()
        extension = {"txt": "txt", "docx": "docx"}[kind]
        name = file_name(doc, extension)
        path = os.path.join(workdir, name)
        with open(path, "wb") as f:
            f.write(_ref_render_txt(doc) if kind == "txt" else _ref_render_docx(doc))
        checker = Checker(quiet=True)
        if kind == "txt":
            check_txt(checker, path, doc, name)
        else:
            check_docx(checker, path, doc, name)
        status = "GREEN" if not checker.failures else "RED"
        print("[SELF-TEST] fixture %s/%s → %s（期望 GREEN）" % (dream_id, kind, status))
        failures += 0 if status == "GREEN" else 1
        for label, detail in checker.failures:
            print("           - %s：%s" % (label, detail))
        if kind == "txt":
            mutations_ok = run_mutations("txt", path, doc, name)
            failures += 0 if mutations_ok else 1
        else:
            mutations_ok = run_mutations("docx", path, doc, name)
            failures += 0 if mutations_ok else 1

    pdf_path = os.path.join(workdir, "20261022-电梯-v2.pdf")
    with open(pdf_path, "wb") as f:
        f.write(_ref_render_pdf())
    checker = Checker(quiet=True)
    check_pdf(checker, pdf_path, os.path.basename(pdf_path))
    status = "GREEN" if not checker.failures else "RED"
    print("[SELF-TEST] fixture PDF → %s（期望 GREEN）" % status)
    failures += 0 if status == "GREEN" else 1
    for label, detail in checker.failures:
        print("           - %s：%s" % (label, detail))
    mutations_ok = run_mutations("pdf", pdf_path, None, os.path.basename(pdf_path))
    failures += 0 if mutations_ok else 1

    bad_db = os.path.join(workdir, "bad-sha.db")
    shutil.copyfile(db_path, bad_db)
    with open(bad_db, "ab") as f:
        f.write(b"\0")  # 只改 sha256，不改可解析性；前置必须在打开库之前就红
    try:
        verify_db(bad_db, sidecar_path)
        print("[SELF-TEST] 错 sha 的 DB → GREEN（期望 RED）")
        failures += 1
    except InputError:
        print("[SELF-TEST] 错 sha 的 DB → RED（符合预期）")

    print("RESULT: %s" % ("GREEN" if failures == 0 else "RED"))
    return 0 if failures == 0 else 1


# ---------------------------------------------------------------- CLI

def main(argv):
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:  # pragma: no cover - 老 Python / 非常规流
            pass
    ap = argparse.ArgumentParser(description="梦匣 P3 导出验证闸门（D357/D358/D372）")
    ap.add_argument("--txt", metavar="FILE", help="校验 txt 导出")
    ap.add_argument("--docx", metavar="FILE", help="校验 docx 导出")
    ap.add_argument("--pdf", metavar="FILE", help="校验 pdf 导出（只做结构级）")
    ap.add_argument("--file-name", metavar="NAME", help="导出文件名（与文件名推导比对）")
    ap.add_argument("--from-db", metavar="DB", help="证据用合并后 DB 副本")
    ap.add_argument("--dream-id", metavar="ID", help="被导出的 dream id")
    ap.add_argument("--sidecar", metavar="TXT", default=DEFAULT_SIDECAR,
                    help="DB sidecar 路径（默认 p3-e2e-08-db-sidecar.txt）")
    ap.add_argument("--expect-sha", metavar="HEX", help="可选辅助：断言文件 sha256 未变")
    ap.add_argument("--mutation", action="store_true", help="负向自查：副本变异必 RED 后恢复")
    ap.add_argument("--self-test", dest="self_test", action="store_true",
                    help="纯标准库夹具自测（不需要设备/真库）")
    a = ap.parse_args(argv)

    if a.self_test:
        return self_test()
    try:
        return run_cli(a)
    except InputError as e:
        print("[MISMATCH] 输入错误：%s" % e, file=sys.stderr)
        print("RESULT: RED")
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
