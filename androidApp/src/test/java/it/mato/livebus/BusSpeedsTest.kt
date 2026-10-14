package it.mato.livebus

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class SpeedEstimatorTest {
    private val day = 20_000L
    private val default = SpeedEstimator.DEFAULT_METRES_PER_SECOND

    @Test fun withoutSamplesEveryRouteRunsAtTheDefault() {
        val estimator = SpeedEstimator()
        for (bucket in SpeedBucket.values()) assertEquals(default, estimator.speed("7", bucket, day), 0.001f)
    }

    @Test fun fewSamplesOnlyNudgeTheEstimateAwayFromTheDefault() {
        val estimator = SpeedEstimator()
        repeat(3) { estimator.observe("7", SpeedBucket.MIDDAY, day, 3.0) }
        val speed = estimator.speed("7", SpeedBucket.MIDDAY, day)
        assertTrue("Three samples must not replace the prior, was $speed", speed > 3.5f && speed < default)
    }

    @Test fun plentyOfSamplesConvergeOnWhatBusesDo() {
        val estimator = SpeedEstimator()
        repeat(200) { estimator.observe("7", SpeedBucket.MIDDAY, day, 3.5) }
        assertEquals(3.5f, estimator.speed("7", SpeedBucket.MIDDAY, day), 0.25f)
    }

    @Test fun aRouteWithoutDataBorrowsTheCityFigureForThatPartOfTheDay() {
        val estimator = SpeedEstimator()
        repeat(300) { estimator.observe("7", SpeedBucket.EVENING_PEAK, day, 3.0) }
        val other = estimator.speed("12", SpeedBucket.EVENING_PEAK, day)
        assertTrue("City-wide rush hour slowness carries over: $other", other < default - 1f)
        assertEquals(default, estimator.speed("12", SpeedBucket.MIDDAY, day), 0.001f)
    }

    @Test fun partsOfTheDayAreLearnedSeparately() {
        val estimator = SpeedEstimator()
        repeat(100) { estimator.observe("7", SpeedBucket.NIGHT, day, 8.0) }
        repeat(100) { estimator.observe("7", SpeedBucket.EVENING_PEAK, day, 3.0) }
        assertTrue(estimator.speed("7", SpeedBucket.NIGHT, day) > estimator.speed("7", SpeedBucket.EVENING_PEAK, day) + 3f)
    }

    @Test fun oneWildSampleDoesNotMoveARouteWithATrackRecord() {
        val estimator = SpeedEstimator()
        repeat(60) { estimator.observe("7", SpeedBucket.MIDDAY, day, 4.5) }
        val before = estimator.speed("7", SpeedBucket.MIDDAY, day)
        estimator.observe("7", SpeedBucket.MIDDAY, day, 15.0)
        assertEquals(before, estimator.speed("7", SpeedBucket.MIDDAY, day), 0.15f)
    }

    @Test fun impossibleSamplesAreIgnored() {
        val estimator = SpeedEstimator()
        estimator.observe("7", SpeedBucket.MIDDAY, day, 0.2)
        estimator.observe("7", SpeedBucket.MIDDAY, day, 40.0)
        estimator.observe("7", SpeedBucket.MIDDAY, day, Double.NaN)
        assertEquals(0, estimator.entryCount)
    }

    @Test fun oldEvidenceFadesBackToThePriors() {
        val estimator = SpeedEstimator()
        repeat(100) { estimator.observe("7", SpeedBucket.MIDDAY, day, 3.0) }
        val fresh = estimator.speed("7", SpeedBucket.MIDDAY, day)
        val monthLater = estimator.speed("7", SpeedBucket.MIDDAY, day + 60)
        assertTrue(fresh < monthLater && monthLater < default)
        assertTrue(estimator.evidence("7", SpeedBucket.MIDDAY, day + 60) < 10)
    }

    @Test fun speedsSurviveASaveAndLoad() {
        val estimator = SpeedEstimator()
        repeat(80) { estimator.observe("7", SpeedBucket.MORNING_PEAK, day, 3.2) }
        val restored = SpeedEstimator().apply { restore(estimator.serialize(day)) }
        assertEquals(estimator.speed("7", SpeedBucket.MORNING_PEAK, day),
            restored.speed("7", SpeedBucket.MORNING_PEAK, day), 0.01f)
    }

    @Test fun theSavedFileIsBoundedAndKeepsTheBestSupportedEntries() {
        val estimator = SpeedEstimator()
        repeat(SpeedEstimator.MAX_ENTRIES + 400) { estimator.observe("route$it", SpeedBucket.MIDDAY, day, 4.0) }
        repeat(30) { estimator.observe("busy", SpeedBucket.MIDDAY, day, 3.0) }
        val text = estimator.serialize(day)
        assertTrue(text.lines().size <= SpeedEstimator.MAX_ENTRIES + 2)
        assertTrue("busy\t" in text)
    }

    @Test fun garbageInTheSavedFileIsSkipped() {
        val estimator = SpeedEstimator()
        estimator.restore("bus-speeds-1\nbroken\n7\t2\tnan\t3\t5\n7\t9\t4.0\t3.0\t5\n7\t2\t4.0\t3.0\t5\n")
        assertEquals(1, estimator.entryCount)
        SpeedEstimator().restore("something else")
    }

    @Test fun bucketsFollowRomeTime() {
        val morning = java.time.ZonedDateTime.of(2026, 10, 14, 8, 30, 0, 0, java.time.ZoneId.of("Europe/Rome"))
        assertEquals(SpeedBucket.MORNING_PEAK, SpeedBucket.at(morning.toEpochSecond()))
        assertEquals(SpeedBucket.MIDDAY, SpeedBucket.atMinute(12 * 60))
        assertEquals(SpeedBucket.EVENING_PEAK, SpeedBucket.atMinute(18 * 60))
        assertEquals(SpeedBucket.EVENING, SpeedBucket.atMinute(21 * 60))
        assertEquals(SpeedBucket.NIGHT, SpeedBucket.atMinute(23 * 60))
        assertEquals(SpeedBucket.NIGHT, SpeedBucket.atMinute(5 * 60))
    }
}

class SpeedSamplerTest {
    private fun fix(t: Long, along: Double, stops: Int = 1, off: Double = 5.0, pattern: String = "p") =
        SpeedSampler.Fix(t, pattern, along, stops, off)

    @Test fun measuresProgressAlongTheRoute() {
        assertEquals(5.0, SpeedSampler.sample(fix(0, 100.0), fix(30, 250.0), 18f)!!, 0.001)
    }

    @Test fun takesTheTimeAtPassedStopsOut() {
        // 40 s, one stop in between (18 s standing): 150 m in 22 s.
        assertEquals(150.0 / 22, SpeedSampler.sample(fix(0, 100.0, 1), fix(40, 250.0, 2), 18f)!!, 0.001)
    }

    @Test fun rejectsWhatIsNotDriving() {
        assertNull("same fix again", SpeedSampler.sample(fix(10, 100.0), fix(10, 100.0), 18f))
        assertNull("jitter on a stationary bus", SpeedSampler.sample(fix(0, 100.0), fix(30, 108.0), 18f))
        assertNull("backwards", SpeedSampler.sample(fix(0, 100.0), fix(30, 60.0), 18f))
        assertNull("long gap", SpeedSampler.sample(fix(0, 100.0), fix(400, 900.0), 18f))
        assertNull("line changed (turnaround)", SpeedSampler.sample(fix(0, 100.0), fix(30, 250.0, pattern = "q"), 18f))
        assertNull("off the line", SpeedSampler.sample(fix(0, 100.0), fix(30, 250.0, off = 150.0), 18f))
        assertNull("mostly dwell", SpeedSampler.sample(fix(0, 100.0, 1), fix(30, 150.0, 2), 18f))
        assertNull("teleport", SpeedSampler.sample(fix(0, 100.0), fix(30, 2_500.0), 18f))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LearnedSpeedPlanningTest {
    private val now = System.currentTimeMillis() / 1000
    private val line = TransitPattern("l", "l", "L", "L", "0", setOf("l"), listOf(
        TransitStop("a", "a", 45.000, 7.0), TransitStop("b", "b", 45.010, 7.0),
        TransitStop("c", "c", 45.020, 7.0), TransitStop("d", "d", 45.030, 7.0)))

    private fun bus(lat: Double, timestamp: Long) =
        LiveVehicle("bus", "l", "l", lat, 7.0, null, timestamp)

    private fun index() = TransitIndex::class.java.getDeclaredConstructor(List::class.java)
        .apply { isAccessible = true }.newInstance(listOf(line))

    @Test fun consecutiveFeedsTeachTheSpeedOfTheLine() = runBlocking {
        val index = index()
        // 0.0003 degrees of latitude is 33 m; 3 s apart would be too short, so use 10 s: 3.3 m/s
        // faster than nothing, then steady 330 m per 30 s feeds: 11 m/s of road at 1.15 circuity.
        repeat(30) { step ->
            index.trackVehicles(listOf(bus(45.0002 + 0.0003 * step, now - 900 + step * 30L)), now)
        }
        val learned = index.speedOf(line, now)
        assertTrue("learned $learned", index.speeds.entryCount > 0 && learned != SpeedEstimator.DEFAULT_METRES_PER_SECOND)
    }

    @Test fun theSameFeedTrackedTwiceCountsOnce() = runBlocking {
        val index = index()
        index.trackVehicles(listOf(bus(45.001, now - 60)), now)
        index.trackVehicles(listOf(bus(45.004, now - 30)), now)
        val revision = index.speeds.revision
        index.trackVehicles(listOf(bus(45.004, now - 30)), now)
        assertEquals(revision, index.speeds.revision)
    }

    @Test fun aStationaryBusTeachesNothing() = runBlocking {
        val index = index()
        for (step in 0 until 5) index.trackVehicles(listOf(bus(45.001, now - 200 + step * 30L)), now)
        assertEquals(0, index.speeds.entryCount)
    }

    @Test fun slowerLearnedSpeedMakesBusesArriveLater() = runBlocking {
        val fast = index()
        val slow = index()
        repeat(100) { slow.speeds.observe("l", SpeedBucket.at(now), Math.floorDiv(now, 86_400L), 2.5) }
        val here = listOf(bus(45.001, now))
        val etaFast = fast.trackVehicles(here, now).single().arrivalSeconds[3]
        val etaSlow = slow.trackVehicles(here, now).single().arrivalSeconds[3]
        assertTrue("fast $etaFast slow $etaSlow", etaSlow > etaFast * 1.4f)
    }
}
