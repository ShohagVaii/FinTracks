package com.fintracks.app

import com.fintracks.app.ui.FuelEntry
import com.fintracks.app.ui.MaintEntry
import com.fintracks.app.ui.autoFuel
import com.fintracks.app.ui.averageMileage
import com.fintracks.app.ui.bikeStats
import com.fintracks.app.ui.costPerKm
import com.fintracks.app.ui.dailyKm
import com.fintracks.app.ui.dueDate
import com.fintracks.app.ui.fillMileages
import com.fintracks.app.ui.nearestPoint
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeMathTest {
    private fun fuel(id: String, date: String, odo: Double?, total: Double, liters: Double? = null, price: Double? = null) =
        FuelEntry(id = id, date = date, odo = odo, total = total, liters = liters, pricePerL = price)

    @Test fun normalMileageIsKmPerLitre() {
        val list = fillMileages(listOf(
            fuel("f1", "2026-11-01", 1000.0, 1000.0, 10.0),
            fuel("f2", "2026-11-05", 1300.0, 1500.0, 15.0)
        ))
        assertEquals(1, list.size)
        assertEquals(20.0, list[0].kmPerL!!, 0.001)
        assertFalse(list[0].approx)
    }

    @Test fun missingLitresAreEstimatedFromAveragePrice() {
        val list = fillMileages(listOf(
            fuel("f1", "2026-11-01", 1000.0, 1000.0, 10.0, 100.0),
            fuel("f2", "2026-11-05", 1200.0, 1500.0, null, null)
        ))
        // 1500 ÷ 100 (average price) = 15 L; 200 km ÷ 15 L
        assertEquals(200.0 / 15.0, list[0].kmPerL!!, 0.001)
        assertTrue(list[0].approx)
    }

    @Test fun odometerThatWentBackwardsIsApproximateAndUsesNormalAverage() {
        val list = fillMileages(listOf(
            fuel("f1", "2026-11-01", 1000.0, 1000.0, 10.0),
            fuel("f2", "2026-11-05", 1200.0, 1000.0, 10.0),
            fuel("f3", "2026-11-10", 1100.0, 1000.0, 10.0)
        ))
        assertFalse(list[0].approx)
        assertEquals(20.0, list[0].kmPerL!!, 0.001)
        assertTrue(list[1].approx)
        assertEquals(20.0, list[1].kmPerL!!, 0.001)
    }

    @Test fun noLitresNoPriceGivesNoValueButNoCrash() {
        val list = fillMileages(listOf(
            fuel("f1", "2026-11-01", 1000.0, 1000.0),
            fuel("f2", "2026-11-05", 1200.0, 1000.0)
        ))
        assertNull(list[0].kmPerL)
        assertTrue(list[0].approx)
    }

    @Test fun entriesWithoutOdometerAndSingleFillGiveNothing() {
        assertTrue(fillMileages(listOf(fuel("f1", "2026-11-01", null, 1000.0, 10.0))).isEmpty())
        assertTrue(fillMileages(listOf(fuel("f1", "2026-11-01", 1000.0, 1000.0, 10.0))).isEmpty())
    }

    @Test fun averageUsesNormalFillsFirst() {
        val normal = fillMileages(listOf(
            fuel("f1", "2026-11-01", 1000.0, 1000.0, 10.0),
            fuel("f2", "2026-11-05", 1200.0, 1000.0, 10.0),
            fuel("f3", "2026-11-10", 1100.0, 1000.0, 10.0)
        ))
        val (avg, approx) = averageMileage(normal)
        assertEquals(20.0, avg!!, 0.001)
        assertFalse(approx)
        val onlyApprox = fillMileages(listOf(
            fuel("f1", "2026-11-01", 1000.0, 1000.0, 10.0, 100.0),
            fuel("f2", "2026-11-05", 1200.0, 1500.0, null, null)
        ))
        val (a2, approx2) = averageMileage(onlyApprox)
        assertEquals(200.0 / 15.0, a2!!, 0.001)
        assertTrue(approx2)
    }

    @Test fun autoLitresAndPrice() {
        assertEquals(10.0 to 100.0, autoFuel(1000.0, null, 100.0))
        assertEquals(8.0 to 125.0, autoFuel(1000.0, 8.0, null))
        assertEquals(8.0 to 100.0, autoFuel(1000.0, 8.0, 100.0))
    }

    @Test fun costPerKmNeedsDistance() {
        assertEquals(10.0, costPerKm(5000.0, 500.0)!!, 0.001)
        assertNull(costPerKm(5000.0, 0.0))
    }

    @Test fun dailyKmFromDatedReadings() {
        assertEquals(50.0, dailyKm(listOf(LocalDate.of(2026, 11, 1) to 1000.0, LocalDate.of(2026, 11, 11) to 1500.0))!!, 0.001)
        assertNull(dailyKm(listOf(LocalDate.of(2026, 11, 1) to 1000.0)))
    }

    @Test fun serviceDateFromRate() {
        assertEquals(LocalDate.of(2026, 11, 24), dueDate(LocalDate.of(2026, 11, 19), 500.0, 100.0))
        assertNull(dueDate(LocalDate.of(2026, 11, 19), 500.0, null))
        assertNull(dueDate(LocalDate.of(2026, 11, 19), -5.0, 100.0))
    }

    @Test fun statsCostsAndMonthlyPerKm() {
        val fuel = listOf(FuelEntry(id = "f1", date = "2026-11-02", odo = 1000.0, total = 1000.0))
        val maint = listOf(MaintEntry(id = "m1", date = "2026-11-05", kind = "mobil", item = "", cost = 500.0, odo = 1500.0))
        val s = bikeStats(fuel, maint, "2026-11-19")
        assertEquals(1500.0, s.totalCost, 0.001)
        assertEquals(1500.0, s.monthCost, 0.001)
        assertEquals(3.0, s.costPerKm!!, 0.001)
        assertEquals(3.0, s.monthPerKm!!, 0.001)
    }

    @Test fun serviceReminderUsesNewestServiceAndShowsOverdue() {
        val fuel = listOf(FuelEntry(id = "f1", date = "2026-11-10", odo = 5000.0, total = 800.0))
        val old = MaintEntry(id = "m1", date = "2026-06-01", kind = "service", item = "", cost = 900.0, odo = 2000.0, nextKm = 4000.0)
        val newer = MaintEntry(id = "m2", date = "2026-10-01", kind = "service", item = "", cost = 900.0, odo = 4500.0, nextKm = 8000.0)
        val s = bikeStats(fuel, listOf(old, newer), "2026-11-19")
        assertEquals(8000.0, s.nextKm!!.second, 0.001)
        assertEquals(3000.0, s.nextKm!!.second - s.latestOdo, 0.001)
        // Passed the service km: still shown, as overdue (negative km left).
        val late = MaintEntry(id = "m3", date = "2026-10-01", kind = "service", item = "", cost = 900.0, odo = 4000.0, nextKm = 4500.0)
        val s2 = bikeStats(fuel, listOf(late), "2026-11-19")
        assertEquals(-500.0, s2.nextKm!!.second - s2.latestOdo, 0.001)
        assertNull(dueDate(LocalDate.of(2026, 11, 19), -500.0, 100.0))
    }

    @Test fun noServiceKmMeansNoReminder() {
        val fuel = listOf(FuelEntry(id = "f1", date = "2026-11-10", odo = 5000.0, total = 800.0))
        val m = MaintEntry(id = "m1", date = "2026-10-01", kind = "repair", item = "", cost = 300.0, odo = 4000.0)
        assertNull(bikeStats(fuel, listOf(m), "2026-11-19").nextKm)
        assertNull(bikeStats(fuel, emptyList(), "2026-11-19").nextKm)
    }

    @Test fun nearestPointPicksTheTappedColumn() {
        assertEquals(0, nearestPoint(0f, 100f, 5))
        assertEquals(4, nearestPoint(100f, 100f, 5))
        assertEquals(2, nearestPoint(50f, 100f, 5))
        assertEquals(0, nearestPoint(70f, 100f, 1))
    }
}
