package com.dreamkeeper.llm.gates

/**
 * Plan 3 Task 7：五闸门（G1–G5）的公共数据模型。
 *
 * 逐字对齐 `scripts/check-faithful.py`（唯一语义真源），不做"顺手改进"。
 */
enum class Gate { G1, G2, G3, G4, G5 }

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
    /** 失败闸门，按 G1→G5 顺序；与 Python 的 failed 列表同序。 */
    val failed: Set<Gate> = results.filter { !it.passed }.mapTo(LinkedHashSet()) { it.gate }

    val passed: Boolean get() = failed.isEmpty()
}
