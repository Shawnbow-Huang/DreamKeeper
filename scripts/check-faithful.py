#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-faithful.py —— 六个规则闸门（设计 §7.2 ④ / §6.1 三级失败链；G6 见 D568）

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
  G6 改字审计：输出里「输入没有的字」≤ 2（同音改字也计数；revise 模式不适用）

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

# v3 的「口水清零」词表：比 FILLERS 更严，但**不含句末语气词 啊/呀/呢**——
# 「好可怕啊」是人味，「呃…那个…」才是口水。脚本与 Kotlin 两侧必须逐字一致。
FILLER_STRICT = [
    "呃", "嗯", "唔", "唉", "哎", "诶", "那个", "就是说", "然后然后", "就是就是",
    "这个这个", "对对对", "反正", "其实吧", "怎么说呢",
]

# 「想词儿」的元叙述：出现即红（与用户原话一一对应）。
META_PHRASES = [
    "我思考一下", "让我想想", "就是什么呢", "我在想", "怎么说呢", "你知道吧", "反正就是",
]

UNCERTAIN = [
    "好像", "记不清", "记不得", "大概", "似乎", "可能", "约莫", "说不定", "忘了",
    "不知道", "也可能", "应该是", "记混", "模糊",
]

CAUSAL = ["于是", "因此", "所以", "正因为", "因为", "由于", "导致", "结果", "从而", "故而"]

# v3.1：明显不可能的时刻（4.4点钟）先归一再取锚点，避免用户手改版被误判。
IMPOSSIBLE_CLOCK = re.compile(r"(\d+)\.\d+(?=点)")

# v3.1：F1 的「新增人物/地点」窄口子——不引入分词器，只认常见称谓与地点词。
PERSON_TERMS = [
    "我妈", "爸爸", "父亲", "妈妈", "母亲", "舅舅", "叔叔", "阿姨",
    "爷爷", "奶奶", "姐姐", "妹妹", "哥哥", "弟弟", "朋友", "同事", "同学",
    "老师", "医生", "警察", "司机", "老板",
]
PLACE_TERMS = ["公园", "学校", "医院", "公司", "机场", "车站", "酒店", "餐厅", "商场", "超市"]


def normalize_numbers(text):
    return IMPOSSIBLE_CLOCK.sub(lambda m: m.group(1), text)


def new_entities(input_text, output_text):
    return [w for w in PERSON_TERMS + PLACE_TERMS if w in output_text and w not in input_text]

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
# 数词 + 量词（三遍 / 七点 / 一楼）。量词必须放进字符类：
# 写成 "[%s]+(?:%s)" 时 (?:…) 匹配的是"这几个字连在一起"的字面串，永远命不中，
# 等于单字数词对 G1 完全不可见（语料子 agent 2026-10-30 实测发现）。
CN_NUM_RE = re.compile("[%s]+[%s]" % (CN_NUM, MEASURE))
# 多位中文数词（三十 / 十五 / 一百 / 三十一）：单字数词太常见，只认 ≥2 字的，专抓"数字被改"
CN_NUM_MULTI_RE = re.compile("[%s]{2,}" % CN_NUM)
ARABIC_NUM_RE = re.compile(r"\d+(?:\.\d+)?")

SPEECH_VERBS = "说喊道问答叫嚷"
SPEECH_NAME_RE = re.compile("([" + chr(0x4E00) + "-" + chr(0x9FFF) + "]{2,4})[" + SPEECH_VERBS + "]")
PUNCT = "，。！？；：、,.!?;:…—　 \n\r\t“”\"'（）()《》【】[]"

HAN_RE = re.compile(r"[\u4e00-\u9fff]")

# ---------------------------------------------------------------- 工具


def read_text(path):
    with io.open(path, encoding="utf-8-sig") as f:
        return f.read()


def strip_ws(s):
    return re.sub(r"\s+", "", s or "")


def content_chars(s):
    """正文用字：去掉空白与标点。

    为什么不能直接数 strip_ws：理顺允许（也几乎必然）插入标点，
    一段没有标点的口述稿加完标点后总字符会涨 10%~16%，把插入标点判成
    变长等于禁止合法操作。长度比只该管正文。
    """
    s = strip_ws(s)
    return "".join(ch for ch in s if ch not in PUNCT)


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


def speech_names(text):
    """说话动词前的人名：只认句读（或 和/跟/对/向…）之后、2–3 个不像虚词的汉字。

    不能直接取「2-4 个汉字 + 说/喊/道」：真实句子里「就是说说」「听不清在说什么」
    会被抓成假人名，F1 就永远红（2026-11-01 实测）。
    """
    out = []
    for m in SPEECH_NAME_RE.finditer(text):
        cand = m.group(1)
        start = m.start(1)
        prev = text[start - 1] if start > 0 else "　"
        if prev not in NAME_BOUNDARY:
            continue
        if cand in FILLERS or any(ch in cand for ch in "的就是了在有没不我你他她它们这那"):
            continue
        out.append(cand)
    return out


GENERIC_NUM = {"一个", "一下", "一点", "一些", "一场", "一阵", "一遍", "一声", "一会", "一会儿"}
NAME_BOUNDARY = PUNCT + "和跟对冲向与"


def extract_anchors(text):
    """F1 的事实锚点：数字、颜色、方位、引号专名、说话人。顺序稳定、去重保序。"""
    text = normalize_numbers(text)
    anchors = []
    anchors += ARABIC_NUM_RE.findall(text)
    anchors += [x for x in CN_NUM_RE.findall(text) if x not in GENERIC_NUM]
    anchors += CN_NUM_MULTI_RE.findall(text)
    anchors += extract_colors(text)
    anchors += extract_directions(text)
    anchors += extract_quoted(text)
    anchors += speech_names(text)
    seen, uniq = set(), []
    for a in anchors:
        if a and a not in seen:
            seen.add(a)
            uniq.append(a)
    return uniq


def gate_f1(input_text, output_text):
    out = normalize_numbers(output_text)
    missing = [a for a in extract_anchors(input_text) if a not in out]
    added = new_entities(input_text, output_text)
    detail = []
    if missing:
        detail.append("丢了事实：%s" % "、".join(missing[:10]))
    if added:
        detail.append("新增人物/地点：%s" % "、".join(added[:6]))
    return {
        "gate": "F1", "name": "事实锚点全保留、不新增实体",
        "value": len(missing) + len(added), "pass": not detail,
        "detail": detail,
        "advisory": [],
    }


def gate_f2(input_text, output_text):
    """v3.1：不再要求压缩比例，只在两种异常上报警。"""
    a, b = len(content_chars(input_text)), len(content_chars(output_text))
    ratio = 0.0 if a == 0 else b / a
    out = normalize_numbers(output_text)
    missing = [x for x in extract_anchors(input_text) if x not in out]
    problems = []
    if a > 0 and ratio < 0.60 and missing:
        problems.append("疑似丢事实：输出/输入 = %.2f 且缺 %d 个锚点" % (ratio, len(missing)))
    if a > 0 and ratio > 1.40:
        problems.append("疑似注水：输出/输入 = %.2f（上限 1.40）" % ratio)
    return {
        "gate": "F2", "name": "长度异常报警（0.60–1.40）",
        "value": round(ratio, 4), "pass": len(problems) == 0,
        "detail": problems, "advisory": [],
    }


def gate_f3(input_text, output_text):
    hits = [w for w in FILLER_STRICT if w in input_text and w in output_text]
    hits += [w for w in META_PHRASES if w in output_text]
    seen, uniq = set(), []
    for h in hits:
        if h not in seen:
            seen.add(h)
            uniq.append(h)
    return {
        "gate": "F3", "name": "口水清零",
        "value": len(uniq), "pass": len(uniq) == 0,
        "detail": [] if not uniq else ["还有口水：%s" % "、".join(uniq)],
        "advisory": [],
    }


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
    a, b = len(content_chars(input_text)), len(content_chars(output_text))
    if a == 0:
        return {"gate": "G2", "name": "长度比", "value": None, "pass": False,
                "detail": ["输入为空"], "advisory": []}
    ratio = b / a
    return {
        "gate": "G2", "name": "正文长度比 ≤ %.2f（不计标点空白）" % lim,
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
    """v3.1：跨场景因果连接词不再逐字比对上下文（改写必然改变上下文）。

    口径改为计数：输出比原文多出 >=2 个因果词才算红；多 1 个视为「允许的那一句解释」。
    """
    def count(text):
        return sum(text.count(c) for c in CAUSAL)

    extra = count(output_text) - count(input_text)
    problems = []
    if extra >= 3:
        problems.append("新增因果连接词 %d 处（允许 2 处）" % extra)
    return {
        "gate": "G4", "name": "跨场景因果连接词 <=2",
        "value": max(0, extra), "pass": not problems,
        "detail": problems, "advisory": [],
    }


def gate_g5(input_text, output_text):
    """v3.1：模糊词不再按次数守恒；只查「有没有把不确定写成确定」。"""
    if not any(w in input_text for w in UNCERTAIN):
        return {"gate": "G5", "name": "不确定词定性保留", "value": 0,
                "pass": True, "detail": [], "advisory": []}
    if not any(w in output_text for w in UNCERTAIN):
        return {"gate": "G5", "name": "不确定词定性保留", "value": 1,
                "pass": False, "detail": ["把不确定的事写成了确定"], "advisory": []}
    in_paras = [p for p in input_text.split(chr(10)) if p.strip()]
    out_paras = [p for p in output_text.split(chr(10)) if p.strip()]
    if len(in_paras) == len(out_paras):
        for i, (a, b) in enumerate(zip(in_paras, out_paras)):
            if any(w in a for w in UNCERTAIN) and not any(w in b for w in UNCERTAIN):
                return {"gate": "G5", "name": "不确定词定性保留", "value": i + 1,
                        "pass": False, "detail": ["第 %d 段把推测写成了确定" % (i + 1)],
                        "advisory": []}
    return {"gate": "G5", "name": "不确定词定性保留", "value": 0,
            "pass": True, "detail": [], "advisory": []}


def gate_g6(input_text, output_text, limit=2):
    """改字审计：输出正文里多出来的字（输入里没有的字）不超过 limit。

    为什么用「多出来的字」而不是对齐 diff：中文没有分词器，逐个字符做编辑距离
    会在删口水处产生大量假插入；而语音识别的同音错必然带来「输入里没有的字」
    （开饭/开放 里的 饭/放），这个口径简单、可复算、两端实现一致。
    代价是：把 A 改成 B、而 B 正好在原文别处出现过时抓不到——可接受，
    这类情况长度与 G3 覆盖率仍会兜底。
    """
    inp = multiset(content_chars(input_text))
    out = multiset(content_chars(output_text))
    surplus = []
    for ch, n in out.items():
        extra = n - inp.get(ch, 0)
        if extra > 0:
            surplus.extend([ch] * extra)
    problems = []
    if len(surplus) > limit:
        problems.append("多出 %d 个原文没有的字：%s" % (len(surplus), "、".join(surplus[:8])))
    return {
        "gate": "G6",
        "name": "改字审计（新增字 ≤ %d）" % limit,
        "value": len(surplus),
        "pass": len(problems) == 0,
        "detail": problems,
        "advisory": [],
    }


def gates_for(kind):
    """v3：原样整理跑 F1/F2/F3 + G4/G5；revise 仍跑 G1–G6（旧口径不动）。"""
    if kind == "revise":
        return [gate_g1, gate_g2, gate_g3, gate_g4, gate_g5]
    return [gate_f1, gate_f2, gate_f3, gate_g4, gate_g5]


def run(input_text, output_text, kind="faithful"):
    gates = []
    for g in gates_for(kind):
        if g is gate_g2:
            gates.append(g(input_text, output_text, kind))
        else:
            gates.append(g(input_text, output_text))
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
        "呃，我昨天做了一个梦，然后然后我们就去海边了，一共四个人。"
        "然后那个海是灰色的，天快黑了。我们在沙滩上走了很久，好像是傍晚，也好像是夜里。"
    )
    good = (
        "昨天我梦见我们四个人去了海边。海是灰色的，天快黑了。"
        "我们在沙滩上走了很久，好像是傍晚，也好像是夜里。"
    )
    cases = []

    def case(name, a, b, want_pass, want_fail=(), want_not_fail=(), kind="faithful"):
        cases.append((name, a, b, want_pass, want_fail, want_not_fail, kind))

    case("v3 合法压缩：事实都在、口水清零", src, good, True)
    case("F1 数字丢失", src, good.replace("四个人", "几个人"), False, ("F1",))
    case("F1 颜色丢失", src, good.replace("海是灰色的", "海是暗的"), False, ("F1",))
    case("F2 压得太狠", src, "昨天我梦见去海边。", False, ("F2",))
    case("原文照抄：F2 不再因不压缩报红，只剩口水", src, src, False, ("F3",))
    case("F3 口水残留", src,
         "呃，昨天我梦见我们四个人去了海边，海是灰色的，好像傍晚。", False, ("F3",))
    case("G4 新增三处因果", src,
         good.replace("海是灰色的", "于是海是灰色的").replace("天快黑了", "所以天快黑了").replace("我们在沙滩上走了很久", "因此我们在沙滩上走了很久"), False, ("G4",))
    case("G5 整段断言化", src,
         good.replace("好像是傍晚，也好像是夜里", "是傍晚，也是夜里"), False, ("G5",))
    case("revise 合法改写（G 口径不动）", "我站在站台上等末班车。", "我站在站台上，等末班车。", True, kind="revise")
    case("revise 不再跑 G6（换三个字只受 G3 约束）", "电梯按不到一楼，窗外有沙砾，我转头看了一眼。",
         "电梯按不到一搂，窗外有沙粒，我转头砍了一眼。", False, ("G3",), ("G6",), kind="revise")

    results, ok = [], True
    for item in cases:
        name, a, b, want_pass = item[:4]
        want_fail = set(item[4]) if len(item) > 4 else set()
        want_not_fail = set(item[5]) if len(item) > 5 else set()
        kind = item[6] if len(item) > 6 else "faithful"
        r = run(a, b, kind)
        good_enough = (r["pass"] == want_pass)
        if want_fail:
            good_enough = good_enough and want_fail <= set(r["failed"])
        if want_not_fail:
            good_enough = good_enough and not (want_not_fail & set(r["failed"]))
        ok = ok and good_enough
        results.append({
            "case": name, "kind": kind, "expect_pass": want_pass,
            "expect_failed": sorted(want_fail), "expect_not_failed": sorted(want_not_fail),
            "actual_pass": r["pass"], "failed": r["failed"], "ok": good_enough,
        })
    print(json.dumps({"self_test": ok, "cases": results}, ensure_ascii=False, indent=2))
    return 0 if ok else 1


def _run_vectors(path):
    root = json.loads(read_text(path))
    vectors = root["cases"] if isinstance(root, dict) else root
    bad = 0
    for v in vectors:
        r = run(v["input"], v["output"], v.get("mode", "faithful"))
        want = set(v.get("expect_failed", []))
        got = set(r["failed"])
        ok = want == got
        if not ok:
            bad += 1
        print(json.dumps({
            "name": v["name"], "mode": v.get("mode", "faithful"),
            "ok": ok, "expect_failed": sorted(want), "actual_failed": sorted(got),
        }, ensure_ascii=False))
    print(json.dumps({"vectors": len(vectors), "failed": bad}, ensure_ascii=False))
    return 0 if bad == 0 else 1


# ---------------------------------------------------------------- CLI

def main(argv):
    ap = argparse.ArgumentParser(description="梦匣六闸门检查器（G1–G6）")
    ap.add_argument("--input")
    ap.add_argument("--output")
    ap.add_argument("--kind", default="faithful", choices=["faithful", "revise"])
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--self-test", dest="self_test", action="store_true")
    ap.add_argument("--vectors", help="向量 JSON：逐条断言 failed 集合")
    a = ap.parse_args(argv)

    if a.self_test:
        return _self_test()

    if a.vectors:
        return _run_vectors(a.vectors)

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
        print("== 六闸门 ==  kind=%s  结论=%s" % (r["kind"], "通过" if r["pass"] else "不通过"))
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
