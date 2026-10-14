package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class JourneyStepsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val board = TransitStop("board", "Via Roma", 45.07, 7.68)
    private val exit = TransitStop("exit", "Piazza Castello", 45.08, 7.69)
    private val pattern = TransitPattern("p", "r4", "4", "Falchera", "0", setOf("trip"), listOf(board, exit))
    private val vehicle = LiveVehicle("v", "r4", "trip", board.latitude, board.longitude, null,
        System.currentTimeMillis() / 1000)

    private fun bus(
        walk: Float = 250f,
        lastWalk: Float = 120f,
        eta: Float = 240f,
        leg: JourneyLeg? = null,
        transfer: TransitStop? = null,
        transferWalk: Float = 0f
    ) = JourneyChoice(pattern, vehicle, board, 0, exit, 100f, walk, 3, lastWalk,
        exit.latitude, exit.longitude, boardingEtaSeconds = eta, estimatedTotalSeconds = 900f,
        secondLeg = leg, transferAt = transfer, transferWalkMetres = transferWalk)

    private val trainStart = TrainStop("t1", "Torino Porta Susa", 45.0711, 7.6648, 34200, 34200)
    private val trainEnd = TrainStop("t2", "Chieri", 45.0124, 7.8183, 34920, 34980)
    private fun train(walk: Float = 300f, lastWalk: Float = 500f, dayOffset: Int = 0) = ScheduledTrainJourney(
        "2121", "REGIONALE", "Chieri", trainStart, trainEnd, listOf(trainStart, trainEnd), 0, 1,
        walk, lastWalk, 2_400f, 34200, 34920, dayOffset)

    private fun texts(guide: JourneyGuide) = guide.steps.map { it.text }

    /** What a row shows: its text, then its detail line after " | ". */
    private fun lines(guide: JourneyGuide) = guide.steps.map { listOfNotNull(it.text, it.detail).joinToString(" | ") }

    private fun noteTexts(guide: JourneyGuide) = guide.notes.map { it.text }

    @Test fun aBusJourneyReadsWalkBoardRideWalk() {
        val guide = JourneySteps.forBus(context, bus())

        assertEquals(listOf(
            "250 m → Via Roma | leave now",
            "Bus 4 | ~4 min",
            "Piazza Castello",
            "120 m → destination"
        ), lines(guide))
        assertEquals(listOf(StepKind.WALK, StepKind.BUS, StepKind.BUS, StepKind.WALK), guide.steps.map { it.kind })
        assertEquals(listOf(false, false, true, false), guide.steps.map { it.getOff })
    }

    @Test fun theFirstWalkStartsWhereTheJourneyDoesAndTheLastStartsAtTheExit() {
        val guide = JourneySteps.forBus(context, bus())

        val first = guide.steps.first()
        assertEquals(board.latitude, first.target!!.latitude, 1e-9)
        assertNull(first.origin)
        val last = guide.steps.last()
        assertEquals(exit.latitude, last.origin!!.latitude, 1e-9)
        assertNotNull(last.target)
        assertTrue(guide.steps.filter { it.kind != StepKind.WALK }.all { it.target == null })
    }

    @Test fun aTransferWalkGoesFromTheStopYouGetOffAtToTheNextBoardingStop() {
        val transfer = TransitStop("x", "Corso Vittorio", 45.075, 7.685)
        val next = TransitStop("n", "Corso Vittorio Nord", 45.076, 7.686)
        val secondExit = TransitStop("y", "Piazza Statuto", 45.09, 7.7)
        val second = TransitPattern("p2", "r9", "9", "Nord", "0", emptySet(), listOf(next, secondExit))
        val leg = JourneyLeg(second, vehicle, next, secondExit, 1)
        val walk = JourneySteps.forBus(context, bus(leg = leg, transfer = transfer, transferWalk = 80f))
            .steps.first { it.text == "80 m → Corso Vittorio Nord" }

        assertEquals(transfer.latitude, walk.origin!!.latitude, 1e-9)
        assertEquals(next.latitude, walk.target!!.latitude, 1e-9)
    }

    @Test fun leaveByIsTheBusArrivalLessTheWalk() {
        // 600 s until the bus, 300 m (250 s) to walk: set off in about 5 minutes.
        assertEquals("leave in ~5 min", JourneySteps.leaveBy(context, bus(walk = 300f, eta = 600f)))
        assertEquals("300 m → Via Roma | leave in ~5 min",
            lines(JourneySteps.forBus(context, bus(walk = 300f, eta = 600f))).first())
    }

    @Test fun leaveNowWhenTheWalkTakesAsLongAsTheBusDoes() {
        assertEquals("leave now", JourneySteps.leaveBy(context, bus(walk = 300f, eta = 250f)))
        assertEquals("leave now", JourneySteps.leaveBy(context, bus(walk = 300f, eta = 100f)))
    }

    @Test fun aNegligibleWalkIsNotAStep() {
        val guide = JourneySteps.forBus(context, bus(walk = 5f, lastWalk = 0f))

        assertEquals(listOf("Bus 4 | ~4 min · leave in ~4 min", "Piazza Castello"), lines(guide))
    }

    @Test fun aTransferAddsTheChangeAndTheSecondRide() {
        val transfer = TransitStop("x", "Corso Vittorio", 45.075, 7.685)
        val secondExit = TransitStop("y", "Piazza Statuto", 45.09, 7.7)
        val second = TransitPattern("p2", "r9", "9", "Nord", "0", emptySet(), listOf(transfer, secondExit))
        val leg = JourneyLeg(second, vehicle, transfer, secondExit, 2)
        val guide = JourneySteps.forBus(context, bus(leg = leg, transfer = transfer, transferWalk = 80f))

        assertEquals(listOf(
            "250 m → Via Roma | leave now",
            "Bus 4 | ~4 min",
            "Corso Vittorio",
            "80 m → Corso Vittorio",
            "Bus 9 · Corso Vittorio",
            "Piazza Statuto",
            "120 m → destination"
        ), lines(guide))
    }

    @Test fun anEstimatedDirectionAndAnOldPositionBecomeNotes() {
        // Far enough from a minute boundary that the clock ticking mid-test cannot change the label.
        val stale = vehicle.copy(tripId = "other", timestampSeconds = System.currentTimeMillis() / 1000 - 250)
        val guide = JourneySteps.forBus(context, bus().copy(vehicle = stale))

        assertEquals(listOf("Estimated direction", "Position 5 min ago"), noteTexts(guide))
        assertEquals(listOf(R.drawable.ic_explore, R.drawable.ic_schedule), guide.notes.map { it.icon })
    }

    @Test fun aTrainJourneyListsTimesAndCreditsTheTimetable() {
        val guide = JourneySteps.forTrain(context, train())

        assertEquals(listOf(
            "300 m → Torino Porta Susa",
            "R 2121 → Chieri | 09:30",
            "Chieri | 09:42",
            "500 m → destination"
        ), lines(guide))
        assertEquals(listOf("Timetable times"), noteTexts(guide))
        assertEquals(StepKind.TRAIN, guide.steps[1].kind)
    }

    @Test fun trainCategoriesAreAbbreviated() {
        assertEquals("R", JourneySteps.trainCategory("REGIONALE"))
        assertEquals("RV", JourneySteps.trainCategory("REGIONALE VELOCE"))
        assertEquals("Intercity", JourneySteps.trainCategory("INTERCITY"))
    }

    @Test fun aTrainThatRunsTomorrowSaysSo() {
        val guide = JourneySteps.forTrain(context, train(dayOffset = 1))

        assertEquals("09:30 (+1)", guide.steps[1].detail)
    }

    @Test fun busThenTrainWalksBetweenTheTwoStops() {
        val guide = JourneySteps.forMixed(context,
            MixedJourney(bus(lastWalk = 150f), train(walk = 0f), busFirst = true, estimatedTotalSeconds = 3_000f))

        assertEquals(listOf(
            "250 m → Via Roma | leave now",
            "Bus 4 | ~4 min",
            "Piazza Castello",
            "150 m → Torino Porta Susa",
            "R 2121 → Chieri | 09:30",
            "Chieri | 09:42",
            "500 m → destination"
        ), lines(guide))
        assertEquals(StepKind.WALK, guide.steps[3].kind)
        assertEquals(exit.latitude, guide.steps[3].origin!!.latitude, 1e-9)
        assertNotNull(guide.steps[3].target)
        assertTrue(noteTexts(guide).contains("Timetable times"))
    }

    @Test fun trainThenBusDoesNotPromiseABusArrivalTime() {
        val guide = JourneySteps.forMixed(context,
            MixedJourney(bus(walk = 200f), train(lastWalk = 0f), busFirst = false, estimatedTotalSeconds = 3_000f))

        assertEquals(listOf(
            "300 m → Torino Porta Susa",
            "R 2121 → Chieri | 09:30",
            "Chieri | 09:42",
            "200 m → Via Roma",
            "Bus 4",
            "Piazza Castello",
            "120 m → destination"
        ), lines(guide))
        // Directions lead to where the rider starts, the train station.
        assertEquals(trainStart.latitude, guide.steps.first().target!!.latitude, 1e-9)
    }

    @Test fun walkingIsOneStepWithDirectionsAndAnEstimate() {
        val destination = GeoPoint(45.07, 7.68)
        val guide = JourneySteps.forWalking(context, 900, 13, destination)

        assertEquals(listOf("900 m → destination"), texts(guide))
        assertEquals(destination, guide.steps.single().target)
        assertTrue(guide.notes.single().text.contains("900 m"))
    }

    @Test @Config(qualifiers = "it") fun stepsAreInItalian() {
        val guide = JourneySteps.forBus(context, bus())

        assertEquals(listOf("250 m → Via Roma | parti ora", "Bus 4 | ~4 min", "Piazza Castello",
            "120 m → arrivo"), lines(guide))
    }

    @Test fun longWalksAreInKilometres() {
        assertEquals("950 m", formatDistance(context, 950f))
        assertEquals("1.2 km", formatDistance(context, 1_240f))
        assertEquals("2 km", formatDistance(context, 1_960f))
    }

    @Test @Config(qualifiers = "it") fun italianKilometresUseADecimalComma() {
        assertEquals("1,2 km", formatDistance(context, 1_240f))
    }

    @Test fun capitalisedStopNamesReadNormally() {
        assertEquals("Chiesa della Salute", TransitIndex.cleanStopName("CHIESA DELLA SALUTE"))
        assertEquals("Corso XX Settembre", TransitIndex.cleanStopName("CORSO XX SETTEMBRE"))
        assertEquals("Massimo D'Azeglio", TransitIndex.cleanStopName("MASSIMO D'AZEGLIO"))
        assertEquals("Porta Nuova FS", TransitIndex.cleanStopName("PORTA NUOVA FS"))
        assertEquals("P.zza Statuto", TransitIndex.cleanStopName("P.ZZA STATUTO"))
        assertEquals("Via Roma", TransitIndex.cleanStopName("Via Roma"))
        assertEquals("Montalenghe", TransitIndex.cleanStopName("Fermata 123 - MONTALENGHE"))
    }
}
