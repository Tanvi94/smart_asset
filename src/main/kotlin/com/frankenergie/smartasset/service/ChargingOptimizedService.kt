package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.client.MarketOrderClient
import com.frankenergie.smartasset.client.SteeringSignalClient
import com.frankenergie.smartasset.domain.OrderBook
import com.frankenergie.smartasset.model.*
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Service
class ChargingOptimizerService(
    private val chargingGroups: List<ChargingGroup>,
    private val steeringSignalClient: SteeringSignalClient,
    private val marketOrderClient: MarketOrderClient,
    private val purchaseTrackerService: PurchaseTrackerService
) {
    private val quarterDurationHours = BigDecimal("0.25")
    private data class GroupAllocationKey(
        val groupId: String,
        val startTime: LocalDateTime,
        val endTime: LocalDateTime,
    )
    private var previousAllocations: Map<GroupAllocationKey, BigDecimal> = emptyMap()
    private val lock = Any()

    fun optimizePlan(
        orderBook: OrderBook,
        chargedSoFar: Map<String, BigDecimal> = emptyMap()
    ): ChargingPlan {
        val supplyUsed = mutableMapOf<DeliveryPeriod, BigDecimal>()
        val groupAllocations = chargingGroups.map { group ->
            optimizeGroup(group, orderBook, chargedSoFar[group.id] ?: BigDecimal.ZERO, supplyUsed)
        }

        val totalCost = groupAllocations.sumOf { it.totalCost }
        return ChargingPlan(groupAllocations, totalCost)
    }

    fun optimize(orderBook: OrderBook, chargedSoFar: Map<String, BigDecimal> = emptyMap()): ChargingPlan = synchronized(lock) {
        val chargedSoFar = purchaseTrackerService.chargedSoFarPerGroup()
        val plan = optimizePlan(orderBook, chargedSoFar)

        val newAllocations = buildAllocation(plan)
        val allocationDiff = getAllocationDifference(previousAllocations, newAllocations)

        for ((key, change) in allocationDiff) {
            val price = getBestAskPrice(orderBook, key) ?: BigDecimal.ZERO
            when {
                change > BigDecimal.ZERO -> {
                    marketOrderClient.sendOrder(
                        MarketOrder(
                            orderId = UUID.randomUUID().toString(),
                            groupId = key.groupId,
                            deliveryStart = key.startTime,
                            deliveryEnd = key.endTime,
                            side = OrderSide.BUY,
                            quantity = change,
                            price = price
                        )
                    )
                    purchaseTrackerService.recordPurchase(
                        ExecutedPurchase(
                            groupId = key.groupId,
                            deliveryStart = key.startTime,
                            deliveryEnd = key.endTime,
                            quantity = change,
                            pricePerMWh = price
                        )
                    )
                }
                change < BigDecimal.ZERO -> {
                    val sellPrice = getBestBidPrice(orderBook, key) ?: price
                    marketOrderClient.sendOrder(
                        MarketOrder(
                            orderId = UUID.randomUUID().toString(),
                            groupId = key.groupId,
                            deliveryStart = key.startTime,
                            deliveryEnd = key.endTime,
                            side = OrderSide.SELL,
                            quantity = change.negate(),
                            price = sellPrice
                        )
                    )
                    purchaseTrackerService.updatePurchase(
                        key.groupId, key.startTime, key.endTime, change.negate(), sellPrice
                    )
                }
            }

            steeringSignalClient.sendSignal(
                SteeringSignal(
                    groupId = key.groupId,
                    quarterStart = key.startTime,
                    quarterEnd = key.endTime,
                    chargePowerMW = (newAllocations[key] ?: BigDecimal.ZERO).divide(quarterDurationHours)
                )
            )
        }

        previousAllocations = newAllocations
        return plan
    }

    private fun getBestAskPrice(orderBook: OrderBook, key: GroupAllocationKey ): BigDecimal? {
        val period = DeliveryPeriod(key.startTime, key.endTime)
        return orderBook.getBestAsk(period)?.price
    }

    private fun getBestBidPrice(orderBook: OrderBook, key: GroupAllocationKey): BigDecimal? {
        val period = DeliveryPeriod(key.startTime, key.endTime)
        return orderBook.getBestBid(period)?.price
    }

    private fun optimizeGroup(
        group: ChargingGroup,
        orderBook: OrderBook,
        alreadyCharged: BigDecimal,
        supplyUsed: MutableMap<DeliveryPeriod, BigDecimal>
    ): GroupAllocation {
        val remainingNeed = (group.neededChargeMWh - alreadyCharged).max(BigDecimal.ZERO)
        val maxPerQuarter = group.maxPowerMW * quarterDurationHours
        val quarters = getQuartersInWindow(group.startTime, group.endTime)

        val quartersWithPrices = quarters
            .flatMap { period ->
                val bestAsk = orderBook.getBestAsk(period)
                bestAsk?.let { listOf(Triple(period, it.price, it.quantity)) } ?: emptyList()
            }
            .sortedBy { it.second }

        val allocations = mutableListOf<QuarterAllocation>()
        var allocated = BigDecimal.ZERO

        for ((period, price, bestAskQuantity) in quartersWithPrices) {
            if (allocated >= remainingNeed) break

            val alreadyUsed = supplyUsed[period] ?: BigDecimal.ZERO
            val availableSupply = (bestAskQuantity - alreadyUsed).max(BigDecimal.ZERO)

            if (availableSupply <= BigDecimal.ZERO) continue

            // remove available supply to get exact allocation per quarter
            val toAllocate = maxPerQuarter.min(remainingNeed - allocated).min(availableSupply)

            if (toAllocate <= BigDecimal.ZERO) continue

            allocations.add(
                QuarterAllocation(
                    startTime = period.startTime,
                    endTime = period.endTime,
                    mwh = toAllocate,
                    pricePerMWh = price
                )
            )
            allocated += toAllocate
            supplyUsed[period] = alreadyUsed + toAllocate
        }

        return GroupAllocation(
            groupId = group.id,
            allocations = allocations,
            totalMWh = allocated,
            totalCost = allocations.sumOf { it.cost }
        )
    }

    private fun getQuartersInWindow(start: LocalDateTime, end: LocalDateTime): List<DeliveryPeriod> {
        val quarters = mutableListOf<DeliveryPeriod>()
        var current = start
        while (current < end) {
            val quarterEnd = current.plusMinutes(15)
            quarters.add(DeliveryPeriod(current, quarterEnd.coerceAtMost(end)))
            current = quarterEnd
        }
        return quarters
    }

    // required to build allocation plan groupwise per quarter
    private fun buildAllocation(plan: ChargingPlan): Map<GroupAllocationKey, BigDecimal> {
        val allocationMap = mutableMapOf<GroupAllocationKey, BigDecimal>()
        plan.groupAllocations.forEach { groupAllocation ->
            groupAllocation.allocations.forEach { allocation ->
                allocationMap [GroupAllocationKey(groupAllocation.groupId, allocation.startTime, allocation.endTime)] = allocation.mwh
            }
        }
        return allocationMap
    }

    // optimizer need difference between current and previous allocation
    // positive diff - buy more, negative diff - sell back
    private fun getAllocationDifference(previous: Map<GroupAllocationKey, BigDecimal>,
                                current: Map<GroupAllocationKey, BigDecimal>): Map<GroupAllocationKey, BigDecimal> {

        val difference = mutableMapOf<GroupAllocationKey, BigDecimal>()
        val allKeys = previous.keys.union(current.keys)
        for (key in allKeys) {
            val currentValue = current[key] ?: BigDecimal.ZERO
            val previousValue = previous[key] ?: BigDecimal.ZERO
            val diff = currentValue - previousValue
            if (diff.compareTo(BigDecimal.ZERO) != 0) {
                difference[key] = diff
            }
    }
        return difference
    }
}
