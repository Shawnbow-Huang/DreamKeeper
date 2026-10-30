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
 *
 * 单位说明：`R` 是「微元 / 1M tokens」，token 数乘以它得到「微元 × 1e6」的整数量，
 * 所以最后除以 `1_000_000` 才是微元。中间任何一步都不除，避免截断漂移。
 */
fun costMicroYuan(rates: Rates, hit: Long, miss: Long, out: Long): Long {
    val hitUnits = Math.multiplyExact(hit, rates.cacheHit)
    val missUnits = Math.multiplyExact(miss, rates.cacheMiss)
    val outUnits = Math.multiplyExact(out, rates.output)
    val sum = Math.addExact(Math.addExact(hitUnits, missUnits), outUnits)
    // 半格四舍五入：+500_000 后整除 1_000_000；这是整个函数里唯一的取整点
    return Math.addExact(sum, 500_000L) / 1_000_000L
}
