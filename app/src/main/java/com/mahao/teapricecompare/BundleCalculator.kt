package com.mahao.teapricecompare

data class BundlePlan(
    val items: List<ProductCandidate>,
    val total: Double,
)

/** Chooses a small, verifiable add-on bundle without asking the Agent to invent products. */
object BundleCalculator {

    private const val MAX_ITEMS = 3

    fun choose(candidates: List<ProductCandidate>, gap: Double): BundlePlan? {
        if (!gap.isFinite() || gap <= 0.0) return null
        val eligible = candidates.filter { candidate ->
            candidate.isAddable &&
                !candidate.isTarget &&
                candidate.price?.let { it.isFinite() && it >= 0.0 } == true
        }
        if (eligible.isEmpty()) return null

        var best: BundlePlan? = null
        fun visit(start: Int, chosen: List<ProductCandidate>, total: Double) {
            if (chosen.isNotEmpty() && total >= gap) {
                val plan = BundlePlan(chosen, total)
                if (best == null || isBetter(plan, best!!, gap)) best = plan
                return
            }
            if (chosen.size == MAX_ITEMS) return

            for (index in start until eligible.size) {
                val candidate = eligible[index]
                val nextTotal = total + candidate.price!!
                if (nextTotal.isFinite()) {
                    visit(index + 1, chosen + candidate, nextTotal)
                }
            }
        }

        visit(start = 0, chosen = emptyList(), total = 0.0)
        return best
    }

    private fun isBetter(candidate: BundlePlan, current: BundlePlan, gap: Double): Boolean {
        val candidateOvershoot = candidate.total - gap
        val currentOvershoot = current.total - gap
        return when {
            candidateOvershoot < currentOvershoot -> true
            candidateOvershoot > currentOvershoot -> false
            candidate.items.size < current.items.size -> true
            candidate.items.size > current.items.size -> false
            candidate.total < current.total -> true
            candidate.total > current.total -> false
            else -> candidate.items.joinToString("\u0000") { it.name } <
                current.items.joinToString("\u0000") { it.name }
        }
    }
}
