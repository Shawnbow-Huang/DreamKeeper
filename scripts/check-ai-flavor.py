#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""梦匣「去 AI 味」表面特征检查器。

用法：
    python scripts/check-ai-flavor.py <文件...> [--json] [--max-hard N]

规则定义见 docs/eval/anti-ai-patterns.md，代码中的 A1–A21 与文档编号一一对应。
本脚本只检查可判定的表面症状，不判断文本是否由 AI 生成，也不替代人工评分。
"""

from __future__ import annotations

import argparse
import glob
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


# 规则编号 -> (严重度, 名称)。名称必须与 docs/eval/anti-ai-patterns.md 一致。
RULES: dict[str, tuple[str, str]] = {
    "A1": ("HARD", "结尾升华/总结陈词"),
    "A2": ("HARD", "「不是……而是……」对偶句"),
    "A3": ("MED", "三段式排比"),
    "A4": ("HARD", "顿悟句"),
    "A5": ("HARD", "「仿佛整个世界」"),
    "A6": ("HARD", "「不禁」"),
    "A7": ("HARD", "「涌上心头」/「五味杂陈」"),
    "A8": ("MED", "空洞形容词/宣传语"),
    "A9": ("MED", "模糊归因"),
    "A10": ("MED", "破折号滥用（密度）"),
    "A11": ("MED", "段长均匀"),
    "A12": ("MED", "段内句长均匀"),
    "A13": ("MED", "同段过度换词"),
    "A14": ("SOFT", "AI 高频连接词（密度）"),
    "A15": ("MED", "解释隐喻/象征"),
    "A16": ("HARD", "元话语/客服腔"),
    "A17": ("SOFT", "单句成段/戏剧性收尾"),
    "A18": ("MED", "情绪直说并解释"),
    "A19": ("SOFT", "格言/伪深刻"),
    "A20": ("SOFT", "「进行＋动词」名词化"),
    "A21": ("SOFT", "「随着……的发展」式开头"),
}

RULE_ORDER = sorted(RULES, key=lambda rule_id: int(rule_id[1:]))


@dataclass(frozen=True)
class Hit:
    """一条命中：规则编号 + 原文偏移 + 展示片段。"""

    rule_id: str
    offset: int
    snippet: str


@dataclass(frozen=True)
class Paragraph:
    """一个正文段落：在原文中的起止偏移、行号、列号。"""

    start: int
    end: int
    text: str
    eff_len: int


class Document:
    def __init__(self, display_name: str, raw: str) -> None:
        self.display_name = display_name
        self.raw = raw
        # 注释和代码块只做掩码，不删除，保证所有偏移与行号仍对应原文。
        self.text = _mask_non_prose(raw)
        self.paras = _build_paragraphs(self.text)
        self.chars = len(re.sub(r"\s", "", self.text))


def _mask_non_prose(text: str) -> str:
    """把 HTML 注释与围栏代码块替换为等长空白，保留换行。"""

    def blank(match: re.Match[str]) -> str:
        return "".join("\n" if ch == "\n" else " " for ch in match.group(0))

    text = re.sub(r"<!--.*?-->", blank, text, flags=re.S)
    text = re.sub(r"```.*?```", blank, text, flags=re.S)
    return text


def _is_prose_line(stripped: str) -> bool:
    if not stripped:
        return False
    if stripped.startswith(("#", ">", "|", "```", "~~~")):
        return False
    if re.match(r"^[-*+]\s+", stripped):
        return False
    if re.match(r"^\d+[.)、]\s*", stripped):
        return False
    return True


def _build_paragraphs(text: str) -> list[Paragraph]:
    """按空行切段；标题、列表、引用、表格、代码块不算正文段落。"""
    lines = text.splitlines(keepends=True)
    paragraphs: list[Paragraph] = []
    start: int | None = None
    end = 0
    offset = 0

    def flush() -> None:
        nonlocal start, end
        if start is None:
            return
        body = text[start:end]
        eff_len = len(re.sub(r"\s", "", body))
        if eff_len:
            paragraphs.append(Paragraph(start, end, body, eff_len))
        start = None

    for line in lines:
        content = line.rstrip("\r\n")
        stripped = content.strip()
        if _is_prose_line(stripped):
            if start is None:
                start = offset + (len(content) - len(content.lstrip()))
            end = offset + len(content)
        else:
            flush()
        offset += len(line)
    flush()
    return paragraphs


def _line_col(text: str, offset: int) -> tuple[int, int]:
    line = text.count("\n", 0, offset) + 1
    last_newline = text.rfind("\n", 0, offset)
    column = offset - last_newline
    return line, column


def _clip(value: str, limit: int = 48) -> str:
    value = re.sub(r"\s+", " ", value).strip()
    if len(value) <= limit:
        return value
    return value[: limit - 1] + "…"


def _matches(doc: Document, pattern: str, rule_id: str) -> list[Hit]:
    return [
        Hit(rule_id, match.start(), _clip(match.group(0)))
        for match in re.finditer(pattern, doc.text)
    ]


def _dedupe(hits: list[Hit]) -> list[Hit]:
    seen: set[tuple[str, int]] = set()
    result: list[Hit] = []
    for hit in sorted(hits, key=lambda item: item.offset):
        key = (hit.rule_id, hit.offset)
        if key not in seen:
            seen.add(key)
            result.append(hit)
    return result


def _dedupe_contained(records: list[tuple[int, int, Hit]]) -> list[Hit]:
    """同一规则里去掉被前一条匹配包含的重叠命中（保留最长的一条）。"""
    records.sort(key=lambda item: (item[0], -item[1]))
    result: list[Hit] = []
    last_end = -1
    for start, end, hit in records:
        if start < last_end:
            continue
        result.append(hit)
        last_end = end
    return result


# A1 结尾升华/总结陈词（HARD）—— 只检查最后一个正文段落，避免误伤中段叙述。
def rule_a1(doc: Document) -> list[Hit]:
    if not doc.paras:
        return []
    para = doc.paras[-1]
    patterns = [
        r"(?:也许|或许|大概|可能)(?:这|那)?就是[^。！？\n]{0,30}(?:梦|意义|答案|原因|安排|礼物)",
        r"(?:这|那|一切|这一切)(?:就)?是[^。！？\n]{0,20}(?:意义|答案|启示|礼物|安排)",
        r"让我(?:明白|懂得|意识到|学会)",
        r"我(?:终于|突然|忽然|这才)?(?:明白|懂得|意识到|领悟)(?:了|到)?",
        r"梦(?:让|教|告诉|想告诉)我",
        r"(?:醒来|醒了)(?:以后|后)[^。！？\n]{0,20}(?:明白|懂|想通)",
    ]
    records: list[tuple[int, int, Hit]] = []
    for pattern in patterns:
        for match in re.finditer(pattern, para.text):
            start = para.start + match.start()
            end = para.start + match.end()
            records.append((start, end, Hit("A1", start, _clip(match.group(0)))))
    return _dedupe_contained(records)


# A2 「不是……而是……」类对偶句（HARD）
def rule_a2(doc: Document) -> list[Hit]:
    return _matches(
        doc,
        r"(?:不是|并非|不只是|不仅仅是)[^。！？\n]{1,40}?(?:[，,；;]\s*)?(?:而是|却是|反而是|更是)",
        "A2",
    )


def _common_prefix(values: list[str]) -> str:
    if not values:
        return ""
    prefix = values[0]
    for value in values[1:]:
        idx = 0
        while idx < len(prefix) and idx < len(value) and prefix[idx] == value[idx]:
            idx += 1
        prefix = prefix[:idx]
        if not prefix:
            break
    return prefix


def _normalize_clause(clause: str) -> str:
    clause = clause.strip(" \t，,、；;：:")
    return re.sub(r"^(?:我们|他们|她们|它们|咱们|我|他|她|它|大家|自己)", "", clause)


# A3 三段式排比（MED）—— 三个连续分句共享 >=2 字前缀。
def rule_a3(doc: Document) -> list[Hit]:
    sentence_re = re.compile(r"[^。！？!?…\n]+[。！？!?…]*")
    clause_re = re.compile(r"[^，,、；;：:\n]+")
    stop_prefixes = {
        "一个",
        "一只",
        "一条",
        "一件",
        "一次",
        "一场",
        "一片",
        "一种",
        "这个",
        "那个",
        "然后",
        "后来",
    }
    hits: list[Hit] = []
    for para in doc.paras:
        for sentence_match in sentence_re.finditer(para.text):
            sentence = sentence_match.group(0)
            clauses = [
                (sentence_match.start() + match.start(), match.group(0))
                for match in clause_re.finditer(sentence)
            ]
            if len(clauses) < 3:
                continue
            index = 0
            while index <= len(clauses) - 3:
                group = clauses[index : index + 3]
                normalized = [_normalize_clause(clause) for _, clause in group]
                if min(len(item) for item in normalized) < 3:
                    index += 1
                    continue
                prefix = _common_prefix(normalized)
                if (
                    len(prefix) >= 2
                    and prefix not in stop_prefixes
                    and all(len(item) > len(prefix) for item in normalized)
                ):
                    snippet = "，".join(clause.strip() for _, clause in group)
                    hits.append(
                        Hit("A3", para.start + group[0][0], _clip(snippet))
                    )
                    index += 3
                else:
                    index += 1
    return _dedupe(hits)


# A4 顿悟句（HARD）
def rule_a4(doc: Document) -> list[Hit]:
    patterns = [
        r"(?:这一刻|那一刻|这时|就在这时|忽然|突然|猛然|终于|一瞬间|刹那间|下一秒)"
        r"[^。！？\n]{0,24}?(?:明白|懂了|意识到|醒悟|想通|看透|领悟)",
        r"我(?:才|终于|突然|忽然|这才)?(?:明白|懂了|意识到|想通|醒悟)(?:了|过来)?",
        r"仿佛(?:明白|懂)了",
    ]
    records: list[tuple[int, int, Hit]] = []
    for pattern in patterns:
        for match in re.finditer(pattern, doc.text):
            records.append(
                (
                    match.start(),
                    match.end(),
                    Hit("A4", match.start(), _clip(match.group(0))),
                )
            )
    return _dedupe_contained(records)


# A5 「仿佛整个世界」（HARD）
def rule_a5(doc: Document) -> list[Hit]:
    return _matches(
        doc,
        r"(?:仿佛|好像|似乎|宛如|犹如|如同)[^。！？\n]{0,10}(?:整个世界|全世界|天地万物|万物)",
        "A5",
    )


# A6 「不禁」（HARD）
def rule_a6(doc: Document) -> list[Hit]:
    return _matches(doc, r"不禁", "A6")


# A7 「涌上心头」/「五味杂陈」（HARD）
def rule_a7(doc: Document) -> list[Hit]:
    return _matches(
        doc,
        r"涌上心头|五味杂陈|百感交集|心头一(?:酸|暖|紧|颤)|眼眶(?:一热|湿润)|泪水(?:模糊|夺眶)",
        "A7",
    )


# A8 空洞形容词/宣传语（MED）
def rule_a8(doc: Document) -> list[Hit]:
    return _matches(
        doc,
        r"令人叹为观止|叹为观止|充满活力|至关重要|无与伦比|美轮美奂|震撼人心|"
        r"难以忘怀|刻骨铭心|油然而生|扑面而来|淋漓尽致|熠熠生辉|五彩斑斓|"
        r"如诗如画|心旷神怡|流连忘返|难以言喻|无法言喻|无以言表|无法用语言形容|"
        r"说不出的(?:滋味|感觉|情绪)",
        "A8",
    )


# A9 模糊归因（MED）
def rule_a9(doc: Document) -> list[Hit]:
    return _matches(
        doc,
        r"(?:专家|学者|研究人员|心理学家|科学家|医生|有人|有些人|人们|大家|众人|"
        r"旁观者|观察者|评论家|网友)(?:们)?(?:认为|指出|表示|说|觉得|发现|证明|相信|常说)|"
        r"据(?:说|闻|传|研究|统计|报道)|"
        r"有(?:人|研究|专家|调查)(?:说|认为|表明|显示)",
        "A9",
    )


# A10 破折号滥用（MED）—— 按密度：超过每 300 字 2 处才算。
def rule_a10(doc: Document) -> list[Hit]:
    dashes = list(re.finditer(r"—+", doc.text))
    count = len(dashes)
    chars = max(doc.chars, 1)
    if count >= 3 and count * 300 > 2 * chars:
        return [
            Hit(
                "A10",
                dashes[0].start(),
                f"{dashes[0].group(0)} ×{count}（全文 {chars} 字）",
            )
        ]
    return []


# A11 段长均匀（MED）—— 连续 3 段字数差 < 10%（段长 >=40 字才参与）。
def rule_a11(doc: Document) -> list[Hit]:
    paras = [para for para in doc.paras if para.eff_len >= 40]
    if len(paras) < 3:
        return []
    hits: list[Hit] = []
    index = 0
    while index <= len(paras) - 3:
        lengths = [para.eff_len for para in paras[index : index + 3]]
        if max(lengths) - min(lengths) < 0.10 * max(lengths):
            end = index + 2
            while end + 1 < len(paras):
                next_lengths = [para.eff_len for para in paras[index : end + 2]]
                if max(next_lengths) - min(next_lengths) < 0.10 * max(next_lengths):
                    end += 1
                else:
                    break
            run = [para.eff_len for para in paras[index : end + 1]]
            hits.append(
                Hit("A11", paras[index].start, _clip(f"段长 {'/'.join(map(str, run))} 字"))
            )
            index = end + 1
        else:
            index += 1
    return hits


# A12 段内句长均匀（MED）—— 一段 >=4 句且句长差 < 20%。
def rule_a12(doc: Document) -> list[Hit]:
    sentence_re = re.compile(r"[^。！？!?…\n]+[。！？!?…]*")
    hits: list[Hit] = []
    for para in doc.paras:
        sentences = [match.group(0) for match in sentence_re.finditer(para.text)]
        lengths = [
            len(re.sub(r"[\s。！？!?…，,、；;：:]", "", sentence))
            for sentence in sentences
        ]
        lengths = [length for length in lengths if length >= 8]
        if len(lengths) < 4:
            continue
        if max(lengths) - min(lengths) < 0.20 * max(lengths):
            hits.append(
                Hit("A12", para.start, _clip(f"句长 {'/'.join(map(str, lengths))} 字"))
            )
    return hits


# A13 同段过度换词（MED）—— 同一段里对同一事物使用 3 种叫法。
SYNONYM_GROUPS: dict[str, list[str]] = {
    "梦": ["梦境", "梦里", "梦中", "梦乡", "梦"],
    "走廊": ["走廊", "过道", "甬道", "长廊"],
    "房屋": ["房子", "房屋", "屋子", "老宅", "宅子"],
    "光": ["光线", "光亮", "光芒", "光"],
    "声音": ["声音", "声响", "响动", "动静"],
    "恐惧": ["恐惧", "惧怕", "惶恐", "害怕"],
    "电梯": ["电梯", "升降机"],
    "门": ["门扉", "门扇", "门"],
    "路": ["道路", "小路", "路径", "路"],
    "水": ["河水", "水流", "水面", "水"],
}


def rule_a13(doc: Document) -> list[Hit]:
    hits: list[Hit] = []
    for para in doc.paras:
        for group_name, terms in SYNONYM_GROUPS.items():
            pattern = re.compile(
                "|".join(re.escape(term) for term in sorted(terms, key=len, reverse=True))
            )
            found: list[tuple[int, str]] = []
            for match in pattern.finditer(para.text):
                term = match.group(0)
                if term not in [item[1] for item in found]:
                    found.append((match.start(), term))
            if len(found) >= 3:
                snippet = f"{group_name}：" + " / ".join(item[1] for item in found)
                hits.append(Hit("A13", para.start + found[0][0], _clip(snippet)))
    return _dedupe(hits)


# A14 AI 高频连接词（SOFT）—— 全文 >=3 处才报，单词不构成理由。
def rule_a14(doc: Document) -> list[Hit]:
    matches = list(
        re.finditer(
            r"值得注意的是|与此同时|总而言之|综上所述|显而易见|毋庸置疑|不可否认|"
            r"值得一提的是|由此可见|此外|然而|因此|综上|换言之",
            doc.text,
        )
    )
    if len(matches) < 3:
        return []
    return [
        Hit("A14", match.start(), _clip(match.group(0))) for match in matches[:8]
    ]


# A15 解释隐喻/象征（MED）
def rule_a15(doc: Document) -> list[Hit]:
    return _matches(
        doc,
        r"象征着|隐喻着|是[^。！？\n]{0,10}的象征|一种(?:象征|隐喻)|"
        r"仿佛在(?:告诉|提醒|暗示|诉说)|像是在(?:告诉|提醒|暗示|警告)|"
        r"意味着[^。！？\n]{0,12}(?:人生|命运|内心|过去|未来)|"
        r"(?:梦里的?|梦中的?)[^。！？\n]{0,10}(?:其实|象征|代表|说明)",
        "A15",
    )


# A16 元话语/客服腔（HARD）
def rule_a16(doc: Document) -> list[Hit]:
    return _matches(
        doc,
        r"希望(?:这|它|以上)[^。！？\n]{0,10}(?:帮助|有用)|以下是|"
        r"下面(?:让|我们|将)|让我(?:为|来)|当然(?:可以|没问题)|"
        r"如果你(?:需要|想要)|请告诉我|不难发现|可以看出",
        "A16",
    )


# A17 单句成段/戏剧性收尾（SOFT）
def rule_a17(doc: Document) -> list[Hit]:
    if len(doc.paras) < 2:
        return []
    last = doc.paras[-1]
    sentences = re.findall(r"[^。！？!?…\n]+[。！？!?…]*", last.text)
    if len(sentences) == 1 and last.eff_len <= 14:
        return [Hit("A17", last.start, _clip(last.text))]
    return []


# A18 情绪直说并解释（MED）
def rule_a18(doc: Document) -> list[Hit]:
    return _matches(
        doc,
        r"(?:内心|心里|心中)(?:充满|涌起|升起|泛起|生出)了?"
        r"[^。！？\n]{0,14}(?:恐惧|害怕|悲伤|喜悦|感动|温暖|酸楚|不安|惆怅|孤独)|"
        r"感到(?:一阵|一种|一股)[^。！？\n]{0,14}|"
        r"说不出的(?:感觉|滋味|情绪)",
        "A18",
    )


# A19 格言/伪深刻（SOFT）
def rule_a19(doc: Document) -> list[Hit]:
    return _matches(
        doc,
        r"(?:人生|生活|时间|记忆|命运|梦)(?:就)?(?:像|是)(?:一场|一面|一条|一座|一次|一种)|"
        r"有些[^。！？\n]{0,8}(?:注定|是用来|是留给)|"
        r"原来[^。！？\n]{0,15}(?:就是|不过是|只是)|"
        r"我们(?:都|总)(?:在|是)[^。！？\n]{0,20}",
        "A19",
    )


# A20 「进行＋动词」名词化（SOFT）
def rule_a20(doc: Document) -> list[Hit]:
    return _matches(
        doc,
        r"进行(?:了)?(?:一(?:次|番|系列))?[^。！？\n]{0,8}"
        r"(?:检查|测试|调整|处理|分析|观察|思考|整理|评估|确认)|"
        r"对[^。！？\n]{0,10}进行",
        "A20",
    )


# A21 「随着……的发展」式开头（SOFT）
def rule_a21(doc: Document) -> list[Hit]:
    return _matches(
        doc,
        r"(?:^|\n)[ \t]*随着[^。！？\n]{0,25}(?:的发展|的进步|的变迁|的到来|的普及)",
        "A21",
    )


RULE_FUNCS = {
    "A1": rule_a1,
    "A2": rule_a2,
    "A3": rule_a3,
    "A4": rule_a4,
    "A5": rule_a5,
    "A6": rule_a6,
    "A7": rule_a7,
    "A8": rule_a8,
    "A9": rule_a9,
    "A10": rule_a10,
    "A11": rule_a11,
    "A12": rule_a12,
    "A13": rule_a13,
    "A14": rule_a14,
    "A15": rule_a15,
    "A16": rule_a16,
    "A17": rule_a17,
    "A18": rule_a18,
    "A19": rule_a19,
    "A20": rule_a20,
    "A21": rule_a21,
}


def _expand_paths(arguments: list[str]) -> list[str]:
    """Windows PowerShell 不展开通配符，这里自己展开 *.md。"""
    expanded: list[str] = []
    for argument in arguments:
        candidate = Path(argument)
        if candidate.is_dir():
            matches = sorted(str(path) for path in candidate.glob("*.md"))
        elif any(ch in argument for ch in "*?["):
            matches = sorted(glob.glob(argument))
        else:
            matches = [argument]
        if not matches:
            raise FileNotFoundError(argument)
        for match in matches:
            if match not in expanded:
                expanded.append(match)
    return expanded


def _check_file(display_name: str) -> list[Hit]:
    raw = Path(display_name).read_text(encoding="utf-8-sig")
    doc = Document(display_name, raw)
    hits: list[Hit] = []
    for rule_id in RULE_ORDER:
        hits.extend(RULE_FUNCS[rule_id](doc))
    hits.sort(key=lambda hit: (hit.offset, int(hit.rule_id[1:])))
    return hits


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(
        description="检查中文文本里的 AI 写作表面特征（规则见 docs/eval/anti-ai-patterns.md）。"
    )
    parser.add_argument("files", nargs="+", help="待检查文件，支持 *.md 通配符")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    parser.add_argument(
        "--max-hard",
        type=int,
        default=0,
        metavar="N",
        help="允许的 HARD 命中数；超过才返回 1（默认 0）",
    )
    args = parser.parse_args(argv)

    if args.max_hard < 0:
        parser.error("--max-hard 不能为负数")

    try:
        paths = _expand_paths(args.files)
    except FileNotFoundError as exc:
        print(f"错误：找不到文件或通配符无匹配：{exc}", file=sys.stderr)
        return 2

    file_payloads: list[dict[str, object]] = []
    counts = {"HARD": 0, "MED": 0, "SOFT": 0}

    for display_name in paths:
        try:
            hits = _check_file(display_name)
        except FileNotFoundError:
            print(f"错误：找不到文件：{display_name}", file=sys.stderr)
            return 2
        except UnicodeDecodeError as exc:
            print(f"错误：{display_name} 不是合法 UTF-8：{exc}", file=sys.stderr)
            return 2
        except OSError as exc:
            print(f"错误：读取 {display_name} 失败：{exc}", file=sys.stderr)
            return 2

        text = Path(display_name).read_text(encoding="utf-8-sig")
        payload_hits: list[dict[str, object]] = []
        for hit in hits:
            severity, name = RULES[hit.rule_id]
            line, column = _line_col(text, hit.offset)
            counts[severity] += 1
            payload_hits.append(
                {
                    "line": line,
                    "column": column,
                    "id": hit.rule_id,
                    "severity": severity,
                    "name": name,
                    "snippet": hit.snippet,
                }
            )
        file_payloads.append({"path": display_name, "hits": payload_hits})

    hard_count = counts["HARD"]
    exit_code = 1 if hard_count > args.max_hard else 0

    if args.json:
        payload = {
            "files": file_payloads,
            "summary": {
                "HARD": counts["HARD"],
                "MED": counts["MED"],
                "SOFT": counts["SOFT"],
                "total": sum(counts.values()),
                "files": len(file_payloads),
                "max_hard": args.max_hard,
            },
            "exit_code": exit_code,
        }
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return exit_code

    for file_payload in file_payloads:
        display_name = str(file_payload["path"])
        for hit in file_payload["hits"]:
            print(
                f"{display_name}:{hit['line']}:{hit['column']} "
                f"{hit['id']} {hit['severity']} {hit['name']} | {hit['snippet']}"
            )
    print(f"HARD={counts['HARD']} MED={counts['MED']} SOFT={counts['SOFT']}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
