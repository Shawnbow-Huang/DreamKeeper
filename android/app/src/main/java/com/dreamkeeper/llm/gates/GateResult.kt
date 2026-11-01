package com.dreamkeeper.llm.gates

/**
 * 闸门的公共数据模型。
 *
 * - G1–G6：revise 链（整体改写小说稿）沿用旧口径；
 * - F1–F3：原样整理 v3 的事实锚点 / 压缩率 / 口水清零（2026-11-01 新增）。
 *
 * 逐字对齐 `scripts/check-faithful.py`（唯一语义真源），不做"顺手改进"。
 */
enum class Gate { G1, G2, G3, G4, G5, G6, F1, F2, F3 }

enum class GateMode { FAITHFUL, REVISE }

data class GateResult(
    val gate: Gate,
    val passed: Boolean,
    val value: Double?,
    val detail: List<String>,
    val advisory: List<String> = emptyList(),
)

data class GateReport(
    val mode: GateMode,
    val results: List<GateResult>,
) {
    /** 失败闸门，按 G1→G6 顺序；与 Python 的 failed 列表同序。 */
    val failed: Set<Gate> = results.filter { !it.passed }.mapTo(LinkedHashSet()) { it.gate }

    val passed: Boolean get() = failed.isEmpty()
}
