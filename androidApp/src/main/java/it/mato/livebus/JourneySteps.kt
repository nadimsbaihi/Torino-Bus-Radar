package it.mato.livebus

import android.content.Context
import org.osmdroid.util.GeoPoint
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class StepKind { WALK, BUS, TRAIN, TRANSFER }

/** One line of a journey. A [target] marks a walk the rider can open as directions in Maps. */
data class JourneyStep(val kind: StepKind, val text: String, val target: GeoPoint? = null)

/** A journey broken into steps, plus the caveats that apply to all of them. */
data class JourneyGuide(val steps: List<JourneyStep>, val notes: List<String> = emptyList())

internal fun ceilMinutes(seconds: Float) = ceil(seconds / 60f).toInt().coerceAtLeast(1)

internal fun formatClock(seconds: Int, dayOffset: Int = 0): String = "%02d:%02d".format(
    (seconds / 3600) % 24,
    (seconds / 60) % 60
) + if (dayOffset > 0) " (+$dayOffset)" else ""

/** Turns a planned journey into the short steps shown in the journey sheet. */
object JourneySteps {
    // A shorter walk is just standing at the stop; a step for it would be noise.
    private const val MIN_WALK_METRES = 20f
    private const val STALE_POSITION_SECONDS = 120

    fun forBus(context: Context, bus: JourneyChoice) = JourneyGuide(
        buildList {
            walkTo(context, bus.walkingMetres, bus.boardAt, directions = true)?.let(::add)
            addAll(busLeg(context, bus, withEta = true))
            walkToDestination(context, bus.destinationWalkMetres)?.let(::add)
        },
        busNotes(context, bus)
    )

    fun forTrain(context: Context, train: ScheduledTrainJourney) = JourneyGuide(
        buildList {
            walkTo(context, train.walkingMetres, train.boardAt, directions = true)?.let(::add)
            addAll(trainLeg(context, train))
            walkToDestination(context, train.destinationWalkMetres)?.let(::add)
        },
        listOf(context.getString(R.string.train_timetable_note))
    )

    fun forMixed(context: Context, mixed: MixedJourney): JourneyGuide {
        val bus = mixed.bus
        val train = mixed.train
        val steps = buildList {
            if (mixed.busFirst) {
                walkTo(context, bus.walkingMetres, bus.boardAt, directions = true)?.let(::add)
                addAll(busLeg(context, bus, withEta = true))
                walkTo(context, bus.destinationWalkMetres, train.boardAt)?.let(::add)
                addAll(trainLeg(context, train))
                walkToDestination(context, train.destinationWalkMetres)?.let(::add)
            } else {
                walkTo(context, train.walkingMetres, train.boardAt, directions = true)?.let(::add)
                addAll(trainLeg(context, train))
                walkTo(context, bus.walkingMetres, bus.boardAt)?.let(::add)
                // The bus is judged against the clock when the train arrives, not now.
                addAll(busLeg(context, bus, withEta = false))
                walkToDestination(context, bus.destinationWalkMetres)?.let(::add)
            }
        }
        return JourneyGuide(steps, busNotes(context, bus) + context.getString(R.string.train_timetable_note))
    }

    fun forWalking(context: Context, metres: Int, minutes: Int, destination: GeoPoint) = JourneyGuide(
        listOf(JourneyStep(StepKind.WALK,
            context.getString(R.string.step_walk_to_destination, metres), destination)),
        listOf(context.getString(R.string.walking_selected, metres, minutes))
    )

    private fun busLeg(context: Context, bus: JourneyChoice, withEta: Boolean): List<JourneyStep> = buildList {
        add(JourneyStep(StepKind.BUS,
            if (withEta) context.getString(R.string.step_board_bus_eta,
                bus.pattern.route, ceilMinutes(bus.boardingEtaSeconds))
            else context.getString(R.string.step_board_bus, bus.pattern.route)))
        val exit = bus.transferAt ?: bus.destination
        add(JourneyStep(StepKind.BUS, context.getString(R.string.step_get_off, stopName(exit.name))))
        bus.secondLeg?.let { leg ->
            if (bus.transferWalkMetres >= MIN_WALK_METRES) {
                add(JourneyStep(StepKind.WALK, context.getString(R.string.step_walk_to,
                    bus.transferWalkMetres.roundToInt(), stopName(leg.boardAt.name))))
            }
            add(JourneyStep(StepKind.TRANSFER, context.getString(R.string.step_change_bus,
                leg.pattern.route, stopName(leg.boardAt.name))))
            add(JourneyStep(StepKind.BUS, context.getString(R.string.step_get_off, stopName(leg.exitAt.name))))
        }
    }

    private fun trainLeg(context: Context, train: ScheduledTrainJourney) = listOf(
        JourneyStep(StepKind.TRAIN, context.getString(R.string.step_take_train,
            train.category, train.number, train.headsign,
            formatClock(train.departureSeconds, train.dayOffset))),
        JourneyStep(StepKind.TRAIN, context.getString(R.string.step_train_get_off,
            stopName(train.destination.name), formatClock(train.arrivalSeconds, train.dayOffset)))
    )

    private fun busNotes(context: Context, bus: JourneyChoice): List<String> {
        val age = maxOf(bus.vehicle.ageSeconds, bus.secondLeg?.vehicle?.ageSeconds ?: 0)
        return listOfNotNull(
            bus.turnaround?.let { context.getString(R.string.bus_turnaround_note, bus.pattern.route,
                stopName(bus.pattern.stops.first().name), ceilMinutes(it.departsInSeconds)) },
            if (bus.directionEstimated) context.getString(R.string.direction_estimated_short) else null,
            if (age > STALE_POSITION_SECONDS) context.getString(R.string.bus_position_age, (age + 59) / 60) else null
        )
    }

    private fun walkTo(context: Context, metres: Float, stop: TransitStop, directions: Boolean = false) =
        walkTo(context, metres, stop.name, stop.latitude, stop.longitude, directions)

    private fun walkTo(context: Context, metres: Float, stop: TrainStop, directions: Boolean = false) =
        walkTo(context, metres, stop.name, stop.latitude, stop.longitude, directions)

    private fun walkTo(
        context: Context,
        metres: Float,
        name: String,
        latitude: Double,
        longitude: Double,
        directions: Boolean
    ): JourneyStep? {
        if (metres < MIN_WALK_METRES) return null
        return JourneyStep(StepKind.WALK,
            context.getString(R.string.step_walk_to, metres.roundToInt(), stopName(name)),
            if (directions) GeoPoint(latitude, longitude) else null)
    }

    private fun walkToDestination(context: Context, metres: Float): JourneyStep? =
        if (metres < MIN_WALK_METRES) null
        else JourneyStep(StepKind.WALK, context.getString(R.string.step_walk_to_destination, metres.roundToInt()))

    private fun stopName(name: String) = TransitIndex.cleanStopName(name)
}
