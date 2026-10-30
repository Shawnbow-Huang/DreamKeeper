package com.dreamkeeper.llm

/**
 * 成本计算：整数微元，只在最后取整一次。
 *
 * `costMicroYuan = (hit*Rhit + miss*Rmiss + out*Rout + 500_000) / 1_000_000`
 *
 * 三个刻意写死的细节（brief §0.3 / §0.8）：
 * 1. 参数与返回值全是 `Long`——`Int` 下 `20000/1_000_000 == 0`；
 * 2. 全程 `Math.multiplyExact` / `Math.addExact`，溢出抛 [ArithmeticException]，由 [price] 归为
 *    `Priced.Overflow`，不许静默回绕；
 * 3. 只有最后加半格 `+500_000` 后除一次——先除再加会引入两次截断，`4.65 → 4` 就是漏半格。
 */
fun costMicroYuan(rates: Rates, hit: Long, miss: Long, out: Long): Long =
    TODO("Task 3 实现待写：Math.multiplyExact/addExact + 单次 +500_000 取整")
