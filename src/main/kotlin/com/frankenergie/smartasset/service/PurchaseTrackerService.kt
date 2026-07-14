package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.model.ExecutedPurchase
import com.frankenergie.smartasset.model.PurchaseSummary
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue

@Service
class PurchaseTrackerService {

    private val purchases: ConcurrentLinkedQueue<ExecutedPurchase> = ConcurrentLinkedQueue()

    fun recordPurchase(purchase: ExecutedPurchase) {
        purchases.add(purchase)
    }

    fun updatePurchase(groupId: String,
                       deliveryStart: LocalDateTime,
                       deliveryEnd: LocalDateTime,
                       quantity: BigDecimal,
                       pricePerMWh: BigDecimal) {
        purchases.add(
            ExecutedPurchase(
                groupId = groupId,
                deliveryStart = deliveryStart,
                deliveryEnd = deliveryEnd,
                quantity = quantity.negate(),
                pricePerMWh = pricePerMWh
            )
        )
    }


    fun getAllPurchases(): List<ExecutedPurchase> = purchases.toList()

    fun getSummary(): PurchaseSummary {
        val allPurchases = purchases.toList()
        val totalMWh = allPurchases.sumOf { it.quantity }.max(BigDecimal.ZERO)
        val totalCost = allPurchases.sumOf { it.totalCost }
        val averagePrice = if (totalMWh > BigDecimal.ZERO) {
            totalCost.divide(totalMWh, 2, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

        return PurchaseSummary(totalMWh, totalCost, averagePrice)
    }

    fun clear() {
        purchases.clear()
    }

    // energy used to charge per group
    fun chargedSoFarPerGroup(): Map<String, BigDecimal> {
        return purchases.groupBy { it.groupId }.mapValues { groupPurchase ->
            groupPurchase.value.sumOf { it.quantity }
        }
    }
}
