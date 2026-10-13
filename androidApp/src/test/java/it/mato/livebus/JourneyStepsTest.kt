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

    @Test fun aBusJourneyReadsWalkBoardRideWalk() {
        val guide = JourneySteps.forBus(context, bus())

        assertEquals(listOf(
            "Walk 250 m to Via Roma",
            "Board bus 4 · arrives in ~4 min",
            "Get off at Piazza Castello",
            "Walk 120 m to your destination"
        ), texts(guide))
        assertEquals(listOf(StepKind.WALK, StepKind.BUS, StepKind.BUS, StepKind.WALK), guide.steps.map { it.kind })
    }

    @Test fun onlyTheWalkToTheBoardingStopOffersDirections() {
        val guide = JourneySteps.forBus(context, bus())

        val target = guide.steps.first().target
        assertNotNull(target)
        assertEquals(board.latitude, target!!.latitude, 1e-9)
        assertEquals(board.longitude, target.longitude, 1e-9)
        assertTrue(guide.steps.drop(1).all { it.target == null })
    }

    @Test fun aNegligibleWalkIsNotAStep() {
        val guide = JourneySteps.forBus(context, bus(walk = 5f, lastWalk = 0f))

        assertEquals(listOf("Board bus 4 · arrives in ~4 min", "Get off at Piazza Castello"), texts(guide))
    }

    @Test fun aTransferAddsTheChangeAndTheSecondRide() {
        val transfer = TransitStop("x", "Corso Vittorio", 45.075, 7.685)
        val secondExit = TransitStop("y", "Piazza Statuto", 45.09, 7.7)
        val second = TransitPattern("p2", "r9", "9", "Nord", "0", emptySet(), listOf(transfer, secondExit))
        val leg = JourneyLeg(second, vehicle, transfer, secondExit, 2)
        val guide = JourneySteps.forBus(context, bus(leg = leg, transfer = transfer, transferWalk = 80f))

        assertEquals(listOf(
            "Walk 250 m to Via Roma",
            "Board bus 4 · arrives in ~4 min",
            "Get off at Corso Vittorio",
            "Walk 80 m to Corso Vittorio",
            "Change to bus 9 at Corso Vittorio",
            "Get off at Piazza Statuto",
            "Walk 120 m to your destination"
        ), texts(guide))
    }

    @Test fun anEstimatedDirectionAndAnOldPositionBecomeNotes() {
        // Far enough from a minute boundary that the clock ticking mid-test cannot change the label.
        val stale = vehicle.copy(tripId = "other", timestampSeconds = System.currentTimeMillis() / 1000 - 250)
        val guide = JourneySteps.forBus(context, bus().copy(vehicle = stale))

        assertEquals(listOf("Estimated direction", "Position 5 min ago"), guide.notes)
    }

    @Test fun aTrainJourneyListsTimesAndCreditsTheTimetable() {
        val guide = JourneySteps.forTrain(context, train())

        assertEquals(listOf(
            "Walk 300 m to Torino Porta Susa",
            "Take REGIONALE 2121 towards Chieri · departs 09:30",
            "Get off at Chieri · arrives 09:42",
            "Walk 500 m to your destination"
        ), texts(guide))
        assertEquals(listOf("Train times: Regione Piemonte timetable."), guide.notes)
        assertEquals(StepKind.TRAIN, guide.steps[1].kind)
    }

    @Test fun aTrainThatRunsTomorrowSaysSo() {
        val guide = JourneySteps.forTrain(context, train(dayOffset = 1))

        assertTrue(texts(guide)[1].endsWith("departs 09:30 (+1)"))
    }

    @Test fun busThenTrainWalksBetweenTheTwoStops() {
        val guide = JourneySteps.forMixed(context,
            MixedJourney(bus(lastWalk = 150f), train(walk = 0f), busFirst = true, estimatedTotalSeconds = 3_000f))

        assertEquals(listOf(
            "Walk 250 m to Via Roma",
            "Board bus 4 · arrives in ~4 min",
            "Get off at Piazza Castello",
            "Walk 150 m to Torino Porta Susa",
            "Take REGIONALE 2121 towards Chieri · departs 09:30",
            "Get off at Chieri · arrives 09:42",
            "Walk 500 m to your destination"
        ), texts(guide))
        assertEquals(StepKind.WALK, guide.steps[3].kind)
        assertNull(guide.steps[3].target)
        assertTrue(guide.notes.contains("Train times: Regione Piemonte timetable."))
    }

    @Test fun trainThenBusDoesNotPromiseABusArrivalTime() {
        val guide = JourneySteps.forMixed(context,
            MixedJourney(bus(walk = 200f), train(lastWalk = 0f), busFirst = false, estimatedTotalSeconds = 3_000f))

        assertEquals(listOf(
            "Walk 300 m to Torino Porta Susa",
            "Take REGIONALE 2121 towards Chieri · departs 09:30",
            "Get off at Chieri · arrives 09:42",
            "Walk 200 m to Via Roma",
            "Board bus 4",
            "Get off at Piazza Castello",
            "Walk 120 m to your destination"
        ), texts(guide))
        // Directions lead to where the rider starts, the train station.
        assertEquals(trainStart.latitude, guide.steps.first().target!!.latitude, 1e-9)
    }

    @Test fun walkingIsOneStepWithDirectionsAndAnEstimate() {
        val destination = GeoPoint(45.07, 7.68)
        val guide = JourneySteps.forWalking(context, 900, 13, destination)

        assertEquals(listOf("Walk 900 m to your destination"), texts(guide))
        assertEquals(destination, guide.steps.single().target)
        assertTrue(guide.notes.single().contains("900 m"))
    }

    @Test @Config(qualifiers = "it") fun stepsAreInItalian() {
        val guide = JourneySteps.forBus(context, bus())

        assertEquals("Cammina per 250 m fino a Via Roma", guide.steps.first().text)
        assertEquals("Sali sul bus 4 · arriva tra circa 4 min", guide.steps[1].text)
        assertEquals("Scendi a Piazza Castello", guide.steps[2].text)
    }
}
