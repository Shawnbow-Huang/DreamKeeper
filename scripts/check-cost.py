#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-cost.py —— Plan 3 成本复算闸门（Task 3）

用法：
  python scripts/check-cost.py --vectors docs/evidence/plan3/p3-cost-01-vectors.json
  python scripts/check-cost.py --db-copy path/to/dream.db          # sqlite3 只读
  python scripts/check-cost.py --db-copy path/to/dream.db --require-rows  # 空表即红（D366）
  python scripts/check-cost.py --self-test                         # 变异自测
  python scripts/check-cost.py --vectors ... --json                # 机器可读

退出码：
  0  全部行通过
  1  有行不通过（mismatches >= 1）
  2  输入/用法错误；或某行 formulaVersion 不在白名单（无法复算，必须升级处理）

五条断言（brief §0.11，逐行都真跑，没有一条是"看起来对"）：
  ① cost 复算相符：用行内的 priceSnapshotJson 重新算 costMicroYuan，必须等于行里的值。
  ② isPeak 复算相符：createdAt 按 Asia/Shanghai 判峰谷，必须等于行里的 isPeak。
  ③ usage 自洽：prompt = hit+miss、total = prompt+completion、completion = out、
     prompt_cache_hit_tokens = hit、prompt_cache_miss_tokens = miss。
  ④ formulaVersion 必须在白名单（当前 {"cost-v1"}），否则 exit 2。
  ⑤ 精确有理数交叉比对：同一笔账用 fractions.Fraction 按"半格四舍五入"再算一遍，
     必须等于 ① 的整数结果。

关键设计取舍（写下来是有原因的）：
  * 复算真源是**行内** priceSnapshotJson，不是当前资产。价卡调价后，历史账目仍按当时快照复算；
    读资产复算会被"改快照 cacheMiss+1 必须红"的变异抓住（D260）。
  * 闸门**不**在这里再校验 offPeak*2==peak。那是加载器的不变量（Kotlin 侧硬校验 + 单测 +
    p3-cost-05-peak2x.txt）；闸门的活是"账算得对不对"。若把被篡改的快照判成"无法检查"（exit 2），
    恰好会漏掉这本该报成 mismatch 的行。
  * 单行快照解析失败按 mismatch 记（这本书不能复算），不升级成 exit 2；exit 2 只留给
    "整个输入读不了/用法错/未知公式版本"。
  * 整数口径与 Kotlin 一致：(hit*Rhit + miss*Rmiss + out*Rout + 500_000) / 1_000_000，
    只在最后取整一次。Python 大整数不会溢出，故本闸门不建模 Long 溢出（Kotlin 侧归 Priced.Overflow）。

已知未验证项：llm_calls 表 Task 4 才建、行 Task 8 才写、真库 Task 10 才跑；本轮的 --db-copy
只做了"表不存在就明确报错"的路径（见 docs/evidence/plan3/p3-cost-06-dbcopy.txt 的 PENDING）。
createdAt 的存储形态（epoch 毫秒 / ISO 字符串）待 Task 4 定表时确认，这里两种都兼容。
"""

import argparse
import json
import os
import sqlite3
import sys
import urllib.parse
from datetime import datetime, timedelta, timezone
from fractions import Fraction

try:  # Python >= 3.9；Windows 上需要 tzdata，没有就退回固定 +08:00
    from zoneinfo import ZoneInfo

    CST = ZoneInfo("Asia/Shanghai")
except Exception:  # pragma: no cover - 兜底路径
    # 1991 年后上海无夏令时，2026 夹具下固定 +08:00 与 Asia/Shanghai 等价
    CST = timezone(timedelta(hours=8))

UTC = timezone.utc

FORMULA_VERSIONS = {"cost-v1", "price-missing"}
CURRENCY = "CNY"
UNIT = "microYuanPerMillionTokens"
PROVENANCE = "project-convention"
RATE_FIELDS = ("cacheHit", "cacheMiss", "output")
MICRO = 1_000_000
HALF = 500_000

MORNING = (9 * 60, 12 * 60)      # [09:00, 12:00) 本地分钟数
AFTERNOON = (14 * 60, 18 * 60)   # [14:00, 18:00)


class InputError(Exception):
    """整个输入不可用：用法错、文件读不了、结构坏到无法逐行检查 → exit 2。"""


# ---------------------------------------------------------------- 时间与峰谷

def parse_instant(text):
    """解析 ISO-8601（Z 或带偏移），返回 tz-aware datetime。"""
    if not isinstance(text, str) or not text.strip():
        raise InputError("时间必须是 ISO-8601 字符串：%r" % (text,))
    s = text.strip()
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    try:
        at = datetime.fromisoformat(s)
    except ValueError as e:
        raise InputError("时间解析失败：%r（%s）" % (text, e))
    if at.tzinfo is None:
        raise InputError("时间缺少时区：%r" % (text,))
    return at


def is_peak(at, zone=CST):
    """给定时钟意义上的工作日 [09:00,12:00) / [14:00,18:00)；周末全天谷。

    [zone] 必须是显式参数：检查 ② 时按 Asia/Shanghai，校验向量的 UTC 反事实时按 [UTC]
    （若这里写死 CST，`at.astimezone(UTC)` 之后再判定仍会转回 CST，反事实就永远等于正事实）。
    """
    local = at.astimezone(zone)
    if local.weekday() >= 5:  # 周六=5、周日=6
        return False
    minutes = local.hour * 60 + local.minute
    return MORNING[0] <= minutes < MORNING[1] or AFTERNOON[0] <= minutes < AFTERNOON[1]


# ---------------------------------------------------------------- 价卡与算术

def _positive_int(value, where):
    if isinstance(value, bool) or not isinstance(value, int):
        raise InputError("%s 必须是整数：%r" % (where, value))
    if value <= 0:
        raise InputError("%s 必须为正：%r" % (where, value))
    return value


def _non_negative_int(value, where):
    if isinstance(value, bool) or not isinstance(value, int):
        raise InputError("%s 必须是非负整数：%r" % (where, value))
    if value < 0:
        raise InputError("%s 必须是非负整数：%r" % (where, value))
    return value


def parse_card(obj, where):
    """把价卡 JSON 对象校验成 {version, effectiveFrom, models:{m:{peak,offPeak}}}。"""
    if not isinstance(obj, dict):
        raise InputError("%s 不是 JSON 对象" % where)
    for key in ("version", "effectiveFrom", "provenance", "currency", "unit", "models"):
        if key not in obj:
            raise InputError("%s 缺少 %s" % (where, key))
    if not isinstance(obj["version"], str) or not obj["version"].strip():
        raise InputError("%s.version 不能为空" % where)
    try:
        effective_from = datetime.strptime(obj["effectiveFrom"], "%Y-%m-%d").date()
    except (TypeError, ValueError):
        raise InputError("%s.effectiveFrom 不是 yyyy-MM-dd：%r"
                         % (where, obj["effectiveFrom"]))
    if obj["provenance"] != PROVENANCE:
        raise InputError("%s.provenance 不认识：%r" % (where, obj["provenance"]))
    if obj["currency"] != CURRENCY:
        raise InputError("%s.currency 不认识：%r（只认 %s）"
                         % (where, obj["currency"], CURRENCY))
    if obj["unit"] != UNIT:
        raise InputError("%s.unit 不认识：%r（只认 %s）" % (where, obj["unit"], UNIT))
    models = obj["models"]
    if not isinstance(models, dict) or not models:
        raise InputError("%s.models 不能为空" % where)
    parsed = {}
    for name, branches in models.items():
        if not isinstance(branches, dict) or set(branches) != {"peak", "offPeak"}:
            raise InputError("%s.models.%s 只允许 peak/offPeak 两支，实际 %r"
                             % (where, name, list(branches) if isinstance(branches, dict) else branches))
        parsed[name] = {}
        for branch in ("peak", "offPeak"):
            rates = branches[branch]
            if not isinstance(rates, dict) or set(rates) != set(RATE_FIELDS):
                raise InputError("%s.models.%s.%s 只允许 %s，实际 %r"
                                 % (where, name, branch, "/".join(RATE_FIELDS),
                                    list(rates) if isinstance(rates, dict) else rates))
            parsed[name][branch] = {
                f: _positive_int(rates[f], "%s.models.%s.%s.%s" % (where, name, branch, f))
                for f in RATE_FIELDS
            }
    return {
        "version": obj["version"],
        "effectiveFrom": effective_from,
        "models": parsed,
    }


def rates_for(card, model, at):
    """按峰谷取费率；模型不在快照价卡里返回 None。"""
    branches = card["models"].get(model)
    if branches is None:
        return None
    return branches["peak" if is_peak(at) else "offPeak"]


def integer_micro(rates, hit, miss, out):
    """Kotlin costMicroYuan 的等价式：整数大数，只在最后加半格取整一次。"""
    units = hit * rates["cacheHit"] + miss * rates["cacheMiss"] + out * rates["output"]
    return (units + HALF) // MICRO


def fraction_micro(rates, hit, miss, out):
    """⑤ 精确有理数交叉比对：Fraction(units,1e6) 半格四舍五入 → 整数。"""
    units = hit * rates["cacheHit"] + miss * rates["cacheMiss"] + out * rates["output"]
    exact = Fraction(units, MICRO)
    # round-half-up：floor(x + 1/2) == floor((2*num + den) / (2*den))
    return (2 * exact.numerator + exact.denominator) // (2 * exact.denominator)


# ---------------------------------------------------------------- 行检查

def _usage_problems(usage, hit, miss, out):
    if usage is None:
        return ["usage 缺失（usageJson/usage 为空）"]
    if not isinstance(usage, dict):
        return ["usage 不是 JSON 对象：%r" % (usage,)]
    expect = {
        "prompt_tokens": hit + miss,
        "completion_tokens": out,
        "total_tokens": hit + miss + out,
        "prompt_cache_hit_tokens": hit,
        "prompt_cache_miss_tokens": miss,
    }
    problems = []
    for key, want in expect.items():
        if key not in usage:
            problems.append("usage.%s 缺失（应为 %d）" % (key, want))
        elif usage[key] != want:
            problems.append("usage.%s=%r 但 hit/miss/out 口径应为 %d"
                            % (key, usage[key], want))
    return problems


UNPRICED_FORMULA = "price-missing"


def row_problems(row):
    """返回 (problems, unknown_formula)。每行独立检查，不短路。"""
    problems = []
    if not row.get("failed") and row["formula_version"] == UNPRICED_FORMULA:
        # Plan 6 D522：非 DeepSeek 供应商没有价卡，成功行也记 0 元。
        # 这类行不许带快照、不许有非零费用，也不做 ①–⑤ 复算。
        if row["card"] is not None:
            problems.append("price-missing 行不应带 priceSnapshotJson")
        if row["cost"] != 0:
            problems.append("price-missing 行 costMicroYuan 应为 0，实际 %r" % (row["cost"],))
        return problems, False
    if row.get("failed"):
        # D366/D316：失败行（llm_calls.error 非 NULL）豁免 ①～⑤，改断言
        # costMicroYuan==0 且 usageJson IS NULL；其余列允许为空（四 token 列本就 NULL）。
        if row["cost"] != 0:
            problems.append("失败行 costMicroYuan 应为 0，实际 %r" % (row["cost"],))
        if row["usage"] is not None:
            problems.append("失败行 usageJson 应为 NULL，实际有 usage：%r" % (row["usage"],))
        return problems, False

    unknown_formula = row["formula_version"] not in FORMULA_VERSIONS
    if unknown_formula:
        problems.append("formulaVersion=%r 不在白名单 %s，无法复算"
                        % (row["formula_version"], sorted(FORMULA_VERSIONS)))

    card = row["card"]
    rates = rates_for(card, row["model"], row["at"])
    if rates is None:
        problems.append("model=%r 不在该行 priceSnapshotJson 的价卡里" % (row["model"],))
    else:
        micro = integer_micro(rates, row["hit"], row["miss"], row["out"])
        exact = fraction_micro(rates, row["hit"], row["miss"], row["out"])
        if exact != micro:
            problems.append("⑤ 有理数复算 %d != 整数复算 %d（实现有第二次取整？）"
                            % (exact, micro))
        want_cost = row["cost"]
        if want_cost is None:
            problems.append("costMicroYuan 为 NULL（未知 model 的行不许写成 0 或 NULL）")
        elif micro != want_cost:
            problems.append("① cost 复算 %d != 行内 costMicroYuan %d（差 %+d）"
                            % (micro, want_cost, want_cost - micro))
        elif exact != want_cost:
            problems.append("⑤ 有理数 %d != 行内 costMicroYuan %d" % (exact, want_cost))

    peak = is_peak(row["at"])
    if peak != row["is_peak"]:
        problems.append("② isPeak 复算 %s != 行内 isPeak %s（createdAt=%s, Asia/Shanghai）"
                        % (peak, row["is_peak"], row["at"].astimezone(CST).isoformat()))

    problems.extend("③ " + p for p in _usage_problems(row["usage"], row["hit"], row["miss"], row["out"]))

    if "expect_peak_utc" in row:
        utc_peak = is_peak(row["at"], UTC)
        if utc_peak != row["expect_peak_utc"]:
            problems.append("fixture expect_peak_utc=%r 与 UTC 判定 %r 不一致"
                            % (row["expect_peak_utc"], utc_peak))
    return problems, unknown_formula


def check_rows(rows):
    """逐行检查，返回 (details, mismatches, unknown_formula_rows)。"""
    details = []
    mismatches = 0
    unknown = 0
    for row in rows:
        problems, unknown_formula = row_problems(row)
        if unknown_formula:
            unknown += 1
        if problems:
            mismatches += 1
            details.append((row["id"], row.get("source", "?"), problems))
    return details, mismatches, unknown


def summarize(rows, details, mismatches, unknown):
    for row_id, source, problems in details:
        print("[MISMATCH] %-24s (%s)" % (row_id, source))
        for p in problems:
            print("           - %s" % p)
    failed_rows = [row for row in rows if row.get("failed")]
    for row in rows:
        if row.get("failed"):
            # D366：失败行单独一行输出，httpStatus 照抄行内值（可能为 NULL），
            # cost/usage 必须分别是 0 / NULL。
            print("[FAILED] %s %s cost=%s usage=%s" % (
                row.get("http_status") if row.get("http_status") is not None else "none",
                row.get("error") or "UNKNOWN",
                row["cost"],
                "NULL" if row["usage"] is None else "SET",
            ))
        elif not any(d[0] == row["id"] for d in details):
            print("[OK] %-24s cost=%s peak=%s" % (row["id"], row["cost"], row["is_peak"]))
    # failed=N 是 §0.6 步骤 10 的对账口径：必须等于 error 行数，且与 logcat DKLLM
    # status=failed 的生成链条数相等（D392）。
    print("rows=%d mismatches=%d failed=%d" % (len(rows), mismatches, len(failed_rows)))
    if unknown:
        return 2
    return 1 if mismatches else 0


# ---------------------------------------------------------------- 向量模式

def load_vectors(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except OSError as e:
        raise InputError("读不到向量文件 %s：%s" % (path, e))
    except ValueError as e:
        raise InputError("向量文件不是合法 JSON：%s" % e)
    if not isinstance(data, dict):
        raise InputError("向量文件顶层不是对象")
    if data.get("schema") != "dreamkeeper.p3.cost.vectors/v1":
        raise InputError("schema 不认识：%r" % (data.get("schema"),))
    card = parse_card(data.get("card"), "vectors.card")
    formula_version = data.get("formulaVersion")
    entries = data.get("vectors")
    if not isinstance(entries, list) or not entries:
        raise InputError("vectors 必须是非空数组")
    rows = []
    for i, entry in enumerate(entries):
        where = "vectors[%d]" % i
        if not isinstance(entry, dict):
            raise InputError("%s 不是对象" % where)
        for key in ("id", "model", "at", "hit", "miss", "out",
                    "expect_peak", "expect_micro", "usage"):
            if key not in entry:
                raise InputError("%s 缺少 %s" % (where, key))
        row = {
            "id": str(entry["id"]),
            "source": "%s#%d" % (os.path.basename(path), i),
            "at": parse_instant(entry["at"]),
            "model": entry["model"],
            "hit": entry["hit"],
            "miss": entry["miss"],
            "out": entry["out"],
            "cost": entry["expect_micro"],
            "is_peak": entry["expect_peak"],
            "usage": entry["usage"],
            "formula_version": formula_version,
            "card": card,
        }
        # 夹具自带 UTC 反事实：作为该行的附加断言（不升级成 exit 2，
        # 否则 isPeak 变异会被"输入错误"掩盖，本应报 mismatch 的行就漏了）
        if "expect_peak_utc" in entry:
            row["expect_peak_utc"] = entry["expect_peak_utc"]
        for key in ("hit", "miss", "out"):
            row[key] = _non_negative_int(row[key], "%s.%s" % (where, key))
        if not isinstance(row["cost"], int) or isinstance(row["cost"], bool):
            raise InputError("%s.expect_micro 必须是整数：%r" % (where, row["cost"]))
        if not isinstance(row["is_peak"], bool):
            raise InputError("%s.expect_peak 必须是布尔：%r" % (where, row["is_peak"]))
        rows.append(row)
    return rows


# ---------------------------------------------------------------- 真库模式

def parse_created_at(value):
    if isinstance(value, bool) or value is None:
        raise InputError("createdAt 不可解析：%r" % (value,))
    if isinstance(value, (int, float)):
        raw = int(value)
        # >= 1e12 视为毫秒（2001-09-09 起），否则视为秒
        seconds = raw / 1000.0 if raw >= 1_000_000_000_000 else float(raw)
        return datetime.fromtimestamp(seconds, tz=UTC)
    if isinstance(value, str):
        s = value.strip()
        if s.isdigit():
            return parse_created_at(int(s))
        return parse_instant(s)
    raise InputError("createdAt 类型不支持：%r" % (type(value),))


def load_db_copy(path):
    if not os.path.exists(path):
        raise InputError("库文件不存在：%s" % path)
    uri = "file:%s?mode=ro" % urllib.parse.quote(path.replace("\\", "/"))
    try:
        conn = sqlite3.connect(uri, uri=True, timeout=5)
    except sqlite3.Error as e:
        raise InputError("打不开库（只读）：%s（%s）" % (path, e))
    try:
        names = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        if "llm_calls" not in names:
            raise InputError(
                "库中没有 llm_calls 表：Task 4 才建表、Task 8 才写行、Task 10 才真跑；"
                "本轮 --db-copy 只允许 PENDING（见 docs/evidence/plan3/p3-cost-06-dbcopy.txt）")
        conn.row_factory = sqlite3.Row
        records = conn.execute("SELECT * FROM llm_calls").fetchall()
    except sqlite3.Error as e:
        raise InputError("查询 llm_calls 失败：%s（%s）" % (path, e))
    finally:
        conn.close()

    rows = []
    for i, rec in enumerate(records):
        where = "llm_calls[%d]" % i
        keys = set(rec.keys())

        def need(name):
            if name not in keys:
                raise InputError("%s 缺少列 %s（行结构按 Plan §3 LlmCall）" % (where, name))
            return rec[name]

        snapshot = need("priceSnapshotJson")
        error_value = rec["error"] if "error" in keys else None
        failed = error_value is not None
        http_status = rec["httpStatus"] if "httpStatus" in keys else None
        formula_value = need("formulaVersion")
        if snapshot is None and (failed or formula_value == UNPRICED_FORMULA):
            # D366：失败行豁免 ①～⑤，不要求快照可复算；D316 虽照写快照，这里仍允许 NULL。
            card = None
        else:
            if snapshot is None:
                raise InputError("%s.priceSnapshotJson 为 NULL（历史账目必须自带快照）" % where)
            try:
                snapshot_obj = json.loads(snapshot) if isinstance(snapshot, str) else snapshot
            except ValueError as e:
                raise InputError("%s.priceSnapshotJson 不是合法 JSON：%s" % (where, e))
            card = parse_card(snapshot_obj, "%s.priceSnapshotJson" % where)
        usage_raw = need("usageJson")
        try:
            usage = json.loads(usage_raw) if isinstance(usage_raw, str) else usage_raw
        except ValueError as e:
            raise InputError("%s.usageJson 不是合法 JSON：%s" % (where, e))

        rows.append({
            "id": str(need("id")),
            "source": "%s#%d" % (os.path.basename(path), i),
            "at": parse_created_at(need("createdAt")),
            "model": need("model"),
            "hit": need("cacheHitTokens"),
            "miss": need("cacheMissTokens"),
            "out": need("outputTokens"),
            "cost": need("costMicroYuan"),
            "is_peak": bool(need("isPeak")),
            "usage": usage,
            "formula_version": need("formulaVersion"),
            "card": card,
            "failed": failed,
            "http_status": http_status,
            "error": error_value,
        })
    return rows


# ---------------------------------------------------------------- 自测

def require_rows_problem(rows):
    """--require-rows 的空表硬门（D366）：返回问题文案，非空表返回 None。"""
    if not rows:
        return "llm_calls 0 行（--require-rows 要求至少 1 行）"
    return None


def _canaries():
    """六条人造账目：一条好账 + 五类坏账（含三条实现/数据变异）。"""
    card = {
        "version": "2026-10-30",
        "effectiveFrom": "2026-10-30",
        "provenance": PROVENANCE,
        "currency": CURRENCY,
        "unit": UNIT,
        "models": {
            "deepseek-flash": {
                "peak": {"cacheHit": 20000, "cacheMiss": 1000000, "output": 4000000},
                "offPeak": {"cacheHit": 10000, "cacheMiss": 500000, "output": 2000000},
            },
            "deepseek-v4-pro": {
                "peak": {"cacheHit": 150000, "cacheMiss": 4500000, "output": 13500000},
                "offPeak": {"cacheHit": 75000, "cacheMiss": 2250000, "output": 6750000},
            },
        },
    }
    at = parse_instant("2026-10-30T01:00:00Z")  # 周五 09:00 上海 → 峰
    usage = {"prompt_tokens": 2, "completion_tokens": 1, "total_tokens": 3,
             "prompt_cache_hit_tokens": 1, "prompt_cache_miss_tokens": 1}

    def row(**kw):
        base = {
            "id": "canary", "source": "self-test", "at": at,
            "model": "deepseek-flash", "hit": 1, "miss": 1, "out": 1,
            "cost": 5, "is_peak": True, "usage": dict(usage),
            "formula_version": "cost-v1", "card": card,
        }
        base.update(kw)
        return base

    import copy
    big_card = copy.deepcopy(card)          # 变异 3：快照 cacheMiss+1，只有 1M 行会变
    big_card["models"]["deepseek-flash"]["peak"]["cacheMiss"] += 1

    return [
        ("baseline", [row()], 0, 0),
        ("mutation cost+1", [row(cost=6)], 1, 1),               # ①
        ("mutation isPeak flipped", [row(is_peak=False)], 1, 1),  # ②
        ("mutation snapshot cacheMiss+1",
         [row(hit=1_000_000, miss=1_000_000, out=1_000_000,
              cost=5_020_000,
              usage={"prompt_tokens": 2_000_000, "completion_tokens": 1_000_000,
                     "total_tokens": 3_000_000, "prompt_cache_hit_tokens": 1_000_000,
                     "prompt_cache_miss_tokens": 1_000_000},
              card=big_card)], 1, 1),                            # ① 快照变异
        ("mutation usage inconsistent",
         [row(usage={"prompt_tokens": 3, "completion_tokens": 1, "total_tokens": 3,
                     "prompt_cache_hit_tokens": 1, "prompt_cache_miss_tokens": 1})], 1, 1),  # ③
        ("unknown formulaVersion", [row(formula_version="cost-v2")], 1, 2),  # ④
    ]


def self_test():
    cases = []
    bad = 0
    rows_seen = 0
    for name, rows, want_mismatches, want_exit in _canaries():
        rows_seen += len(rows)
        details, mismatches, unknown = check_rows(rows)
        exit_code = 2 if unknown else (1 if mismatches else 0)
        ok = mismatches == want_mismatches and exit_code == want_exit
        bad += 0 if ok else 1
        cases.append({
            "case": name,
            "expect_mismatches": want_mismatches,
            "actual_mismatches": mismatches,
            "expect_exit": want_exit,
            "actual_exit": exit_code,
            "ok": ok,
            "problems": [p for _, _, ps in details for p in ps],
        })

    # D366 自测：失败行豁免 + cost/usage 硬断言 + --require-rows 空表硬门。
    failed_ok = {
        "id": "failed-ok", "source": "self-test", "failed": True,
        "http_status": None, "error": "NETWORK",
        "cost": 0, "usage": None, "is_peak": False,
        "formula_version": "", "card": None, "at": None, "model": "deepseek-flash",
        "hit": None, "miss": None, "out": None,
    }
    failed_cost = dict(failed_ok, cost=1)
    failed_usage = dict(failed_ok, usage={"prompt_tokens": 1})
    d366_checks = [
        ("failed row (cost=0/usage=NULL) 通过", not row_problems(failed_ok)[0]),
        ("failed row cost=1 报错", bool(row_problems(failed_cost)[0])),
        ("failed row usage 非 NULL 报错", bool(row_problems(failed_usage)[0])),
        ("--require-rows 空表报错", require_rows_problem([]) is not None),
        ("--require-rows 非空表不报错", require_rows_problem([failed_ok]) is None),
    ]
    for name, ok in d366_checks:
        bad += 0 if ok else 1
        print("[SELF-TEST] %s → %s" % (name, "OK" if ok else "FAILED"))

    print(json.dumps({"self_test": bad == 0, "cases": cases,
                      "d366": [{"case": name, "ok": ok} for name, ok in d366_checks]},
                     ensure_ascii=False, indent=2))
    print("rows=%d mismatches=%d" % (rows_seen, bad))
    return 0 if bad == 0 else 1


# ---------------------------------------------------------------- CLI

def main(argv):
    # 证据文件必须是 UTF-8：Windows 重定向到文件时默认用 GBK，会把中文写成乱码
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:  # pragma: no cover - 老 Python / 非常规流
            pass
    ap = argparse.ArgumentParser(description="梦匣 P3 成本复算闸门")
    ap.add_argument("--vectors", metavar="JSON", help="校验向量文件")
    ap.add_argument("--db-copy", metavar="DB", help="校验库副本（sqlite3 只读）")
    ap.add_argument("--self-test", dest="self_test", action="store_true",
                    help="内置变异自测")
    ap.add_argument("--require-rows", dest="require_rows", action="store_true",
                    help="--db-copy 专用：llm_calls 至少 1 行，空表不许假绿（D366）")
    ap.add_argument("--json", action="store_true", help="额外输出机器可读摘要")
    a = ap.parse_args(argv)

    if a.require_rows and not a.db_copy:
        ap.error("--require-rows 只能与 --db-copy 一起用")

    if a.self_test:
        if a.vectors or a.db_copy:
            ap.error("--self-test 不能与 --vectors/--db-copy 同时使用")
        return self_test()

    if bool(a.vectors) == bool(a.db_copy):
        ap.error("需要且只允许一个：--vectors 或 --db-copy（自测用 --self-test）")

    try:
        rows = load_vectors(a.vectors) if a.vectors else load_db_copy(a.db_copy)
    except InputError as e:
        print("输入错误：%s" % e, file=sys.stderr)
        print("rows=0 mismatches=0 failed=0")
        return 2

    if a.require_rows:
        problem = require_rows_problem(rows)
        if problem:
            # D366：空表曾经会打印 rows=0 mismatches=0 并 exit 0，等于假绿；
            # 真库硬门要求 llm_calls 至少一行（失败行也算）。
            print("[MISMATCH] %s" % problem)
            print("rows=0 mismatches=1 failed=0")
            return 1

    details, mismatches, unknown = check_rows(rows)
    exit_code = 2 if unknown else (1 if mismatches else 0)
    failed_count = sum(1 for row in rows if row.get("failed"))

    if a.json:
        print(json.dumps({
            "source": a.vectors or a.db_copy,
            "rows": len(rows),
            "mismatches": mismatches,
            "failed": failed_count,
            "unknown_formula_rows": unknown,
            "exit": exit_code,
            "details": [{"id": i, "source": s, "problems": ps}
                        for i, s, ps in details],
        }, ensure_ascii=False, indent=2))
    else:
        summarize(rows, details, mismatches, unknown)
        return exit_code
    print("rows=%d mismatches=%d failed=%d" % (len(rows), mismatches, failed_count))
    return exit_code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
