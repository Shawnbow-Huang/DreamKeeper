#!/usr/bin/env python3
"""小说化 v3 闸门 N1/N2/N3（Python 是语义真源，Kotlin 只做对齐）。"""
import argparse, json, re, sys, statistics

# N2 文风清单（spec §4.3）
A7 = ["仿佛", "宛如", "不禁", "不由得", "心中一震", "五味杂陈", "难以言喻",
      "意味深长", "若有所思", "嘴角微微上扬", "眼神深邃"]
DECODE = ["象征着", "意味着", "其实是在说", "代表着", "暗示着"]
CLICHES = ["不仅仅", "值得注意", "总而言之", "让我们拭目以待", "奠定基础",
           "充满活力", "令人叹为观止", "专家认为", "在当今"]
FILTER = ["看见", "听到", "感觉到", "意识到", "发现", "觉得", "注意到"]
TELL = ["吼道", "冷哼道", "嗤笑道", "怒道", "沉声道", "笑道"]
NOTES_TYPES = {"借用", "舍弃", "新造", "主题"}


def content_chars(text: str) -> int:
    """与 Kotlin 同口径：去掉空白与标点，只数汉字/字母/数字。"""
    return len(re.findall(r"[\u4e00-\u9fffA-Za-z0-9]", text or ""))


def _fail(code, detail):
    return {"gate": code, "passed": False, "detail": detail}


def _ok(code):
    return {"gate": code, "passed": True, "detail": ""}


def gate_n1(raw: str, novel: dict):
    problems = []
    title = (novel.get("title") or "").strip()
    body = (novel.get("body") or "").strip()
    notes = novel.get("notes") or []
    mood = (novel.get("mood") or "").strip()

    if not 4 <= len(title) <= 14:
        problems.append(f"标题 {len(title)} 字，不在 4–14")
    n = content_chars(body)
    if not 800 <= n <= 5000:
        problems.append(f"正文 {n} 内容字，不在 800–5000")
    paragraphs = [p for p in re.split(r"\n\s*\n", body) if p.strip()]
    if len(paragraphs) < 3:
        problems.append(f"正文只有 {len(paragraphs)} 段，需 ≥3")
    if not 3 <= len(notes) <= 6:
        problems.append(f"创作手记 {len(notes)} 条，需 3–6")
    for i, note in enumerate(notes, 1):
        if not isinstance(note, dict):
            problems.append(f"手记 {i} 不是对象")
            continue
        if note.get("type") not in NOTES_TYPES:
            problems.append(f"手记 {i} type={note.get('type')} 不在 {sorted(NOTES_TYPES)}")
        d = (note.get("detail") or "").strip()
        if not 1 <= len(d) <= 60:
            problems.append(f"手记 {i} detail {len(d)} 字，需 1–60")
    if not 1 <= len(mood) <= 6:
        problems.append(f"mood {len(mood)} 字，需 1–6")
    return _ok("N1") if not problems else _fail("N1", "；".join(problems))


def gate_n2(body: str):
    n = content_chars(body)
    problems = []
    if n == 0:
        return _fail("N2", "正文为空")
    per_k = lambda count: count * 1000.0 / n

    for word in A7:
        if word in body:
            problems.append(f"A7 禁词：{word}")
    for phrase in DECODE:
        if phrase in body:
            problems.append(f"解码句：{phrase}")
    for phrase in CLICHES:
        if phrase in body:
            problems.append(f"套话：{phrase}")

    fw = sum(body.count(w) for w in FILTER)
    if per_k(fw) > 10:
        problems.append(f"filter words {per_k(fw):.1f}/千字（上限 10）")
    de = len(re.findall(r"[\u4e00-\u9fff]地", body))
    if per_k(de) > 12:
        problems.append(f"「地」副词 {per_k(de):.1f}/千字（上限 12）")
    tags = sum(body.count(w) for w in TELL)
    if tags > 2:
        problems.append(f"说书标签 {tags} 次（上限 2）")
    dashes = body.count("—")
    if dashes > 3:
        problems.append(f"破折号 {dashes} 个（上限 3）")
    return _ok("N2") if not problems else _fail("N2", "；".join(problems))


def gate_n3(body: str):
    """只给提示，绝不进 failed 集合（spec §4.3）。"""
    hints = []
    paragraphs = [p for p in re.split(r"\n\s*\n", body) if p.strip()]
    if len(paragraphs) < 3:
        hints.append("段落少于 3 段")
    if "「" not in body and "“" not in body:
        hints.append("没有对白")
    sentences = [s for s in re.split(r"[。！？!?]", body) if s.strip()]
    if len(sentences) >= 20:
        lengths = [content_chars(s) for s in sentences]
        if statistics.pstdev(lengths) < 5:
            hints.append("句长变化不足（标准差 < 5）")
    return hints


def run_case(case: dict):
    raw = case.get("raw", "")
    body = case.get("text", "")
    if case.get("kind") == "n1":
        novel = case.get("novel") or {}
        result = gate_n1(raw, novel)
    elif case.get("kind") == "n2":
        result = gate_n2(body)
    else:
        hints = gate_n3(body)
        return sorted(set(hints)) == sorted(set(case.get("expect_hints", []))), hints
    expected = case.get("expect_failed", [])
    got = [] if result["passed"] else [result["gate"]]
    return got == expected, result


def self_test() -> int:
    long_body = "他推开门。" * 200
    ok_novel = {
        "title": "下不完的楼梯",
        "body": "\n\n".join([long_body, long_body, long_body]),
        "notes": [
            {"type": "借用", "detail": "沿用了楼梯"},
            {"type": "舍弃", "detail": "舍掉了吃饭"},
            {"type": "主题", "detail": "落脚在回不去的家"},
        ],
        "mood": "发怵",
    }
    cases = [
        {"name": "N1 合格", "kind": "n1", "novel": ok_novel, "expect_failed": []},
        {"name": "N1 标题过短", "kind": "n1", "novel": {**ok_novel, "title": "梯"}, "expect_failed": ["N1"]},
        {"name": "N1 段数不足", "kind": "n1", "novel": {**ok_novel, "body": long_body},
         "expect_failed": ["N1"]},
        {"name": "N1 手记类型错", "kind": "n1",
         "novel": {**ok_novel, "notes": [{"type": "补写", "detail": "x"}] * 3}, "expect_failed": ["N1"]},
        {"name": "N2 干净", "kind": "n2", "text": long_body, "expect_failed": []},
        {"name": "N2 A7 禁词", "kind": "n2", "text": long_body + "仿佛", "expect_failed": ["N2"]},
        {"name": "N2 filter 超标", "kind": "n2",
         "text": long_body + "看见" * 40, "expect_failed": ["N2"]},
        {"name": "N2 说书标签", "kind": "n2",
         "text": long_body + "笑道" * 3, "expect_failed": ["N2"]},
        {"name": "N3 只提示", "kind": "n3", "text": "只有一段话。", "expect_failed": [],
         "expect_hints": ["段落少于 3 段", "没有对白"]},
    ]
    failed = 0
    for case in cases:
        ok, detail = run_case(case)
        if not ok:
            failed += 1
            print(f"FAIL {case['name']}: {detail}")
    print(f"self-test {len(cases) - failed}/{len(cases)} 通过")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--vectors")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.vectors:
        data = json.loads(open(args.vectors, encoding="utf-8").read())
        cases = data["cases"] if isinstance(data, dict) else data
        failed = 0
        for case in cases:
            ok, detail = run_case(case)
            if not ok:
                failed += 1
                print(f"FAIL {case['name']}: {detail}")
        print(f"vectors {len(cases) - failed}/{len(cases)} 通过")
        return 1 if failed else 0
    ap.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
