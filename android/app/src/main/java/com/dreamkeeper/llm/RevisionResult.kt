package com.dreamkeeper.llm

/**
 * 一轮「对话改稿」的结果（Task 9 §0.2）。
 *
 * [action] 只看模型 JSON 的 `action` 字段，**不拿「正文变没变」判**：`body` 与当前稿逐字相同
 * 也是合法的 REVISE（D331 / D335，「没改也交一版」）。
 *
 * [version] 非空 = 这一轮真的建了一个新版本（`novel_messages.versionId` 指向它）；
 * ASK 与失败都为 null。
 */
enum class RevisionAction {
    ASK,
    REVISE,
}

data class RevisionResult(
    /** 失败时为 null：这一轮没有走到可判定的 ASK/REVISE 分支。 */
    val action: RevisionAction? = null,
    val version: Int? = null,
    val text: String? = null,
    val summary: String = "",
    val changeLogKept: Int = 0,
    val changeLogDropped: Int = 0,
    val costMicroYuan: Long = 0,
    val failure: GenerationFailure? = null,
) {
    val ok: Boolean get() = failure == null
}
