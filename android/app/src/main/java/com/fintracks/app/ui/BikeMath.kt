package com.fintracks.app.ui

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/** Mileage of one fill. approx = estimated (litres were missing, or the odometer did not go up). value is null when nothing can be estimated. */
internal class FillMileage(val entry: FuelEntry, val kmPerL: Double?, val approx: Boolean)

/** Litres = total ÷ price, or price = total ÷ litres, when only one of them is given. Both given: kept as typed. */
internal fun autoFuel(total: Double?, liters: Double?, price: Double?): Pair<Double?, Double?> {
    val effLiters = liters ?: if (price != null && price > 0 && total != null) total / price else null
    val effPrice = price ?: if (liters != null && liters > 0 && total != null) total / liters else null
    return effLiters to effPrice
}

/**
 * Mileage of every fill after the first, in odometer order. Fills with no odometer (from the + form) are skipped.
 * Normal: (this odometer − previous odometer) ÷ this fill's litres. Approximate (orange): litres estimated from
 * the average pump price, or the odometer did not go up (then the average of normal fills is used).
 */
internal fun fillMileages(fuel: List<FuelEntry>): List<FillMileage> {
    // Previous fill = the one before it by date, so an odometer that went backwards can be seen.
    val f = fuel.filter { it.odo != null }.sortedWith(compareBy({ it.date }, { it.odo }))
    if (f.size < 2) return emptyList()
    val prices = f.mapNotNull { it.pricePerL?.takeIf { p -> p > 0 } }
    val avgPrice = if (prices.isEmpty()) null else prices.average()
    val raw = (1 until f.size).map { i ->
        val e = f[i]
        val km = (e.odo ?: 0.0) - (f[i - 1].odo ?: 0.0)
        val known = e.liters?.takeIf { it > 0 }
        val litres = known ?: avgPrice?.let { e.total / it }?.takeIf { it > 0 }
        when {
            km <= 0 || litres == null -> RawFill(e, null, true)
            else -> RawFill(e, km / litres, known == null)
        }
    }
    val normal = raw.filter { !it.approx }.mapNotNull { it.value }
    val normalAvg = if (normal.isEmpty()) null else normal.average()
    // A fill whose odometer did not go up, or that has no litres, takes the normal average as its estimate.
    return raw.map { r -> FillMileage(r.entry, r.value ?: (if (r.approx) normalAvg else null), r.approx) }
}

private class RawFill(val entry: FuelEntry, val value: Double?, val approx: Boolean)

/** Average mileage of normal fills; with none, the approximate ones (second value true). */
internal fun averageMileage(list: List<FillMileage>): Pair<Double?, Boolean> {
    val normal = list.filter { !it.approx }.mapNotNull { it.kmPerL }
    if (normal.isNotEmpty()) return normal.average() to false
    val approx = list.mapNotNull { it.kmPerL }
    return (if (approx.isEmpty()) null else approx.average()) to true
}

/** Cost per km over the distance covered. Null when no distance. */
internal fun costPerKm(cost: Double, km: Double): Double? = if (km > 0) cost / km else null

/** Km per day from the first and last dated odometer readings. Null when they are not at least a day apart. */
internal fun dailyKm(points: List<Pair<LocalDate, Double>>): Double? {
    if (points.size < 2) return null
    val sorted = points.sortedBy { it.first }
    val first = sorted.first()
    val last = sorted.last()
    val days = ChronoUnit.DAYS.between(first.first, last.first)
    val km = last.second - first.second
    return if (days > 0 && km > 0) km / days else null
}

/** Estimated date when [kmLeft] more km are due at [perDay] km per day. Null when there is no rate. */
internal fun dueDate(today: LocalDate, kmLeft: Double, perDay: Double?): LocalDate? {
    if (perDay == null || perDay <= 0 || kmLeft < 0) return null
    return today.plusDays(ceil(kmLeft / perDay).toLong())
}

class BikeStats(
    val monthCost: Double,
    val totalCost: Double,
    val latestOdo: Double,
    val costPerKm: Double?,
    val monthPerKm: Double?,
    val avgMileage: Double?,
    val avgApprox: Boolean,
    val mileages: List<FillMileage>,
    val dailyKm: Double?,
    val nextKm: Pair<MaintEntry, Double>?,
    val nextDate: MaintEntry?
)

private fun isoDate(s: String): LocalDate? = runCatching { LocalDate.parse(s.take(10)) }.getOrNull()

/** Costs, distance and mileage for the bike tool, from the fuel and maintenance entries. Entries without an odometer count for cost only. */
fun bikeStats(fuel: List<FuelEntry>, maint: List<MaintEntry>, today: String): BikeStats {
    val month = today.take(7)
    val totalCost = fuel.sumOf { it.total } + maint.sumOf { it.cost }
    val fuelMonth = fuel.filter { it.date.startsWith(month) }
    val maintMonth = maint.filter { it.date.startsWith(month) }
    val monthCost = fuelMonth.sumOf { it.total } + maintMonth.sumOf { it.cost }
    val odos = fuel.mapNotNull { it.odo } + maint.mapNotNull { it.odo }
    val km = if (odos.size >= 2) (odos.maxOrNull() ?: 0.0) - (odos.minOrNull() ?: 0.0) else 0.0
    val monthOdos = fuelMonth.mapNotNull { it.odo } + maintMonth.mapNotNull { it.odo }
    val monthKm = if (monthOdos.size >= 2) (monthOdos.maxOrNull() ?: 0.0) - (monthOdos.minOrNull() ?: 0.0) else 0.0
    val latest = odos.maxOrNull() ?: 0.0
    val mileages = fillMileages(fuel)
    val (avg, approx) = averageMileage(mileages)
    val nextKm = maint.mapNotNull { m -> m.nextKm?.let { m to it } }
        .filter { it.second >= latest }
        .minByOrNull { it.second }
    val nextDate = maint.filter { it.nextDate.isNotEmpty() && it.nextDate >= today }.minByOrNull { it.nextDate }
    val points = (fuel.mapNotNull { f -> f.odo?.let { isoDate(f.date) to it } } +
        maint.mapNotNull { m -> m.odo?.let { isoDate(m.date) to it } }).mapNotNull { (d, o) -> d?.let { it to o } }
    return BikeStats(
        monthCost = monthCost,
        totalCost = totalCost,
        latestOdo = latest,
        costPerKm = costPerKm(totalCost, km),
        monthPerKm = costPerKm(monthCost, monthKm),
        avgMileage = avg,
        avgApprox = approx,
        mileages = mileages,
        dailyKm = dailyKm(points),
        nextKm = nextKm,
        nextDate = nextDate
    )
}
