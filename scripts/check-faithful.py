#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-faithful.py —— 五个规则闸门（设计 §7.2 ④ / §6.1 三级失败链）

用法：
  python scripts/check-faithful.py --input raw.txt --output clean.txt
  python scripts/check-faithful.py --input raw.txt --output clean.txt --json
  python scripts/check-faithful.py --input old.txt --output new.txt --kind revise   # G2 放宽到 115%
  python scripts/check-faithful.py --self-test                                      # 变异自测

退出码：0 全绿；1 有闸门红；2 用法/读文件错误。

闸门口径（全部可复算，没有"感觉"）：
  G1 新增 数量 / 颜色 / 方位 / 疑似专名 = 0
  G2 长度比（输出/输入 去空白字数）≤ 110%（revise 模式 ≤ 115%）
  G3 原文关键 2-gram 覆盖率 ≥ 95%（近似"名词动词覆盖率"，见下）
  G4 跨场景因果连接词 = 0
  G5 不确定词保留率 = 100%

设计取舍（必须写清楚，否则过严会误伤）：
  * 中文没有分词器可用（不联网、不引依赖）。G3 用**字符 2-gram 覆盖率**近似"关键名词与动词覆盖率"：
    2-gram 对词序和用词变化敏感、对单字同义替换宽容，正好对应"别把事实改掉"。
  * G1 的"专名"无法靠词性判断。这里只把**引号里的新词**与**紧邻说/喊/道/问的新词**当作硬违规；
    其余新增片段只进 advisory 列表，喂给重试提示词，不直接判红——过严会把正当理顺判成违规。
  * G4 只惩罚"跨场景"的因果词：段落开头或场景硬切词之后一小段内出现的因果词，
    且原文同一位置没有对应因果词，才算违规。同段内部的"所以"不动它（人说话本来就带）。
"""

import argparse
import io
import json
import os
import re
import sys

# ---------------------------------------------------------------- 词表

# 语气词与口水（G1/G3 计算时从"新增片段"里排除，避免把正常的删口水当成新增实词）
FILLERS = [
    "呃", "啊", "嗯", "唉", "那个", "就是说", "然后然后", "就是就是", "这个这个",
    "对对对", "反正", "其实吧", "怎么说呢",
]

UNCERTAIN = [
    "好像", "记不清", "记不得", "大概", "似乎", "可能", "约莫", "说不定", "忘了",
    "不知道", "也可能", "应该是", "记混", "模糊",
]

CAUSAL = ["于是", "因此", "所以", "正因为", "导致", "结果", "从而", "故而"]

# 场景硬切词：梦里换场的线索，出现时强制分段
SCENE_MARKERS = [
    "后来切换到了", "画面一转", "画面又一转", "画面再一转", "然后我就", "紧接着",
    "再后来", "这时候", "此时", "过了一会儿", "突然",
]

COLOR_RE = re.compile(r"[红橙黄绿青蓝紫灰粉棕褐黑白银金彩]色")

DIRECTION_WORDS = [
    "上面", "下面", "里面", "外面", "前面", "后面", "左边", "右边", "旁边", "中间",
    "头顶", "脚下", "楼上", "楼下", "左边", "右边", "东边", "西边", "南边", "北边",
    "往上", "往下", "往左", "往右", "往上走", "往下掉",
]

MEASURE = "个次条根层楼岁点分米人只块栋间道扇截群遍下天年号步声口"
CN_NUM = "一二三四五六七八九十百千万两半几"
CN_NUM_RE = re.compile("[%s]+(?:%s)" % (CN_NUM, MEASURE))
# 多位中文数词（三十 / 十五 / 一百 / 三十一）：单字数词太常见，只认 ≥2 字的，专抓"数字被改"
CN_NUM_MULTI_RE = re.compile("[%s]{2,}" % CN_NUM)
ARABIC_NUM_RE = re.compile(r"\d+(?:\.\d+)?")

SPEECH_VERBS = "说喊道问答叫嚷"
PUNCT = "，。！？；：、,.!?;:…—　 \n\r\t“”\"'（）()《》【】[]"

HAN_RE = re.compile(r"[\u4e00-\u9fff]")

# ---------------------------------------------------------------- 工具


def read_text(path):
    with io.open(path, encoding="utf-8-sig") as f:
        return f.read()


def strip_ws(s):
    return re.sub(r"\s+", "", s or "")


def bigrams(s):
    s = strip_ws(s)
    s = "".join(ch for ch in s if ch not in PUNCT)
    return [s[i:i + 2] for i in range(len(s) - 1)]


def multiset(items):
    m = {}
    for it in items:
        m[it] = m.get(it, 0) + 1
    return m


def remove_fillers(s):
    out = s
    for f in FILLERS:
        out = out.replace(f, "")
    return out


# ---------------------------------------------------------------- 抽取


def extract_numbers(text):
    found = []
    found += ARABIC_NUM_RE.findall(text)
    found += CN_NUM_RE.findall(text)
    found += CN_NUM_MULTI_RE.findall(text)
    return found


def extract_colors(text):
    return COLOR_RE.findall(text)


def extract_directions(text):
    return [w for w in DIRECTION_WORDS if w in text]


def extract_quoted(text):
    return re.findall(r"[“\"「『]([^”\"」』]{2,8})[”\"」』]", text)


def new_name_like(input_text, output_text):
    """紧邻说/喊道/问 的 2-4 字新片段，视为新增专名（硬）。"""
    hits = []
    for m in re.finditer(r"([\u4e00-\u9fff]{2,4})[%s]" % SPEECH_VERBS, output_text):
        cand = m.group(1)
        if cand not in input_text:
            hits.append(cand)
    for q in extract_quoted(output_text):
        if q not in input_text and not COLOR_RE.search(q):
            hits.append("“%s”" % q)
    # 去重但保序
    seen, uniq = set(), []
    for h in hits:
        if h not in seen:
            seen.add(h)
            uniq.append(h)
    return uniq


def advisory_new_fragments(input_text, output_text, limit=12):
    """输出里出现、原文没有的汉字 2-gram（去掉口水后），只作 advisory，不判红。"""
    inp = set(bigrams(remove_fillers(input_text)))
    out = bigrams(remove_fillers(output_text))
    counts = multiset(out)
    cands = [(bg, c) for bg, c in counts.items()
             if bg not in inp and HAN_RE.search(bg) and bg not in FILLERS]
    cands.sort(key=lambda x: -x[1])
    return [bg for bg, _ in cands[:limit]]


# ---------------------------------------------------------------- 闸门


def gate_g1(input_text, output_text):
    problems = []
    for label, pat in (
        ("数量", extract_numbers),
        ("颜色", extract_colors),
        ("方位", extract_directions),
    ):
        for tok in sorted(set(pat(output_text)) - set(pat(input_text))):
            problems.append("%s「%s」" % (label, tok))
    for name in new_name_like(input_text, output_text):
        problems.append("疑似专名「%s」" % name)
    return {
        "gate": "G1",
        "name": "新增 数量/颜色/方位/专名",
        "value": len(problems),
        "pass": len(problems) == 0,
        "detail": problems[:10],
        "advisory": advisory_new_fragments(input_text, output_text),
    }


def gate_g2(input_text, output_text, kind):
    lim = 1.15 if kind == "revise" else 1.10
    a, b = len(strip_ws(input_text)), len(strip_ws(output_text))
    if a == 0:
        return {"gate": "G2", "name": "长度比", "value": None, "pass": False,
                "detail": ["输入为空"], "advisory": []}
    ratio = b / a
    return {
        "gate": "G2", "name": "长度比 ≤ %.2f" % lim,
        "value": round(ratio, 4), "pass": ratio <= lim,
        "detail": [] if ratio <= lim else ["输出/输入 = %.1f%%（上限 %.0f%%）" % (ratio * 100, lim * 100)],
        "advisory": [],
    }


def gate_g3(input_text, output_text):
    src = bigrams(input_text)
    if not src:
        return {"gate": "G3", "name": "原文 2-gram 覆盖率", "value": 1.0, "pass": True,
                "detail": [], "advisory": []}
    out = set(bigrams(output_text))
    hit = sum(1 for bg in src if bg in out)
    cov = hit / len(src)
    missing = multiset([bg for bg in src if bg not in out])
    worst = sorted(missing.items(), key=lambda x: -x[1])[:6]
    return {
        "gate": "G3", "name": "原文 2-gram 覆盖率 ≥ 95%",
        "value": round(cov, 4), "pass": cov >= 0.95,
        "detail": [] if cov >= 0.95 else ["覆盖率 %.1f%%，掉得最多：%s" % (
            cov * 100, "、".join(bg for bg, _ in worst))],
        "advisory": [],
    }


def _scene_splits(text):
    """按空行 + 场景硬切词切成若干段，返回每段起始偏移。"""
    idx = {0}
    for m in re.finditer(r"\n\s*\n", text):
        idx.add(m.end())
    for mk in SCENE_MARKERS:
        for m in re.finditer(re.escape(mk), text):
            idx.add(m.start())
    return sorted(idx)


def gate_g4(input_text, output_text):
    # 原文里出现过的因果词位置集合（用来判断是不是"新增"）
    input_has = any(c in input_text for c in CAUSAL)
    bad = []
    for start in _scene_splits(output_text):
        window = output_text[start:start + 24]
        for c in CAUSAL:
            pos = window.find(c)
            if pos == -1:
                continue
            ctx = window[max(0, pos - 6):pos + len(c) + 6]
            if ctx not in input_text:
                bad.append("跨场景新增因果「%s」（…%s…）" % (c, ctx.replace("\n", " ")))
    # 原文一个因果词都没有、输出却出现了 => 全算新增
    if not input_has:
        for c in CAUSAL:
            for m in re.finditer(c, output_text):
                ctx = output_text[max(0, m.start() - 6):m.end() + 6]
                if ctx not in input_text and ("跨场景新增因果「%s」" % c) not in " ".join(bad) + " ":
                    bad.append("新增因果「%s」（…%s…）" % (c, ctx.replace("\n", " ")))
                    break
    return {
        "gate": "G4", "name": "跨场景因果连接词 = 0",
        "value": len(bad), "pass": len(bad) == 0,
        "detail": bad[:6], "advisory": [],
    }


def gate_g5(input_text, output_text):
    inp = multiset([w for w in UNCERTAIN if w in input_text for _ in range(input_text.count(w))])
    bad = []
    for w in UNCERTAIN:
        need, got = input_text.count(w), output_text.count(w)
        if need and got < need:
            bad.append("「%s」原文 %d 处，输出只剩 %d 处" % (w, need, got))
    return {
        "gate": "G5", "name": "不确定词保留率 = 100%",
        "value": len(inp), "pass": len(bad) == 0,
        "detail": bad[:6], "advisory": [],
    }


def run(input_text, output_text, kind="faithful"):
    gates = [
        gate_g1(input_text, output_text),
        gate_g2(input_text, output_text, kind),
        gate_g3(input_text, output_text),
        gate_g4(input_text, output_text),
        gate_g5(input_text, output_text),
    ]
    return {
        "kind": kind,
        "pass": all(g["pass"] for g in gates),
        "failed": [g["gate"] for g in gates if not g["pass"]],
        "gates": gates,
    }


# ---------------------------------------------------------------- 自测（变异）

def _self_test():
    """每条都是一个"应该变红"的变异；全绿则说明闸门没牙。"""
    src = (
        "呃…我在电梯里，电梯一直往上，按不到一楼。那个…数字过了三十还在涨。"
        "我有点失重，眼睛也看不清东西，好像头顶到一块板。\n\n"
        "后来切换到了教室里面，窗外有沙砾涌进来。我转头看了一眼就不敢再看了。"
        "（我好像并没有这么做，有点傻orz）"
    )
    good = (
        "我在电梯里，电梯一直往上，按不到一楼。数字过了三十还在涨。"
        "我有点失重，眼睛也看不清东西，好像头顶到一块板。\n\n"
        "后来切换到了教室里面，窗外有沙砾涌进来。我转头看了一眼就不敢再看了。"
        "（我好像并没有这么做，有点傻orz）"
    )
    cases = []

    cases.append(("合法理顺（只删口水+标点）", src, good, True))

    cases.append(("G1 新增数量", src,
                  good.replace("数字过了三十还在涨", "数字过了四十还在涨"), False))

    cases.append(("G1 新增颜色", src,
                  good.replace("窗外有沙砾", "窗外有灰色沙砾"), False))

    cases.append(("G1 新增方位", src,
                  good.replace("我转头看了一眼", "我往右边转头看了一眼"), False))

    cases.append(("G1 疑似专名", src,
                  good.replace("后来切换到了教室里面", "后来切换到了教室里面，小王说快跑"), False))

    cases.append(("G2 长度膨胀", src,
                  good + "我还能感觉到电梯井深处有一阵风，从脚底一直吹到后颈，像有人在耳边呼气。", False))

    cases.append(("G3 丢事实", src,
                  "我在电梯里，电梯一直往上。我有点失重，眼睛看不清东西，好像头顶到一块板。", False))

    cases.append(("G4 新增因果", src,
                  good.replace("后来切换到了教室里面", "于是我切换到了教室里面"), False))

    cases.append(("G5 丢不确定词", src,
                  good.replace("好像头顶到一块板", "头顶到一块板"), False))

    results, ok = [], True
    for name, a, b, want_pass in cases:
        r = run(a, b)
        good_enough = (r["pass"] == want_pass)
        ok = ok and good_enough
        results.append({
            "case": name,
            "expect_pass": want_pass,
            "actual_pass": r["pass"],
            "failed": r["failed"],
            "ok": good_enough,
        })
    print(json.dumps({"self_test": ok, "cases": results}, ensure_ascii=False, indent=2))
    return 0 if ok else 1


# ---------------------------------------------------------------- CLI

def main(argv):
    ap = argparse.ArgumentParser(description="梦匣五闸门检查器")
    ap.add_argument("--input")
    ap.add_argument("--output")
    ap.add_argument("--kind", default="faithful", choices=["faithful", "revise"])
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--self-test", dest="self_test", action="store_true")
    a = ap.parse_args(argv)

    if a.self_test:
        return _self_test()

    if not a.input or not a.output:
        ap.error("需要 --input 与 --output（或使用 --self-test）")

    try:
        src = read_text(a.input)
        out = read_text(a.output)
    except OSError as e:
        print("读取失败：%s" % e, file=sys.stderr)
        return 2

    r = run(src, out, a.kind)

    if a.json:
        print(json.dumps(r, ensure_ascii=False, indent=2))
    else:
        print("== 五闸门 ==  kind=%s  结论=%s" % (r["kind"], "通过" if r["pass"] else "不通过"))
        for g in r["gates"]:
            mark = "OK  " if g["pass"] else "FAIL"
            print("  [%s] %-6s %-28s value=%s" % (mark, g["gate"], g["name"], g["value"]))
            for d in g["detail"]:
                print("         - %s" % d)
        adv = next((g["advisory"] for g in r["gates"] if g["gate"] == "G1"), [])
        if adv:
            print("  提示（不判红，喂给重试提示词）：%s" % "、".join(adv))
    return 0 if r["pass"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
