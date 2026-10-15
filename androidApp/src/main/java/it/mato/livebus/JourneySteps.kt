package it.mato.livebus

import android.content.Context
import androidx.annotation.DrawableRes
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import org.osmdroid.util.GeoPoint
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class StepKind { WALK, BUS, TRAIN, METRO, TRANSFER }

/**
 * One line of a journey: what and where in [text], when in [detail]. A [target] marks a walk the
 * rider can open as directions in Maps; its [origin] is where that leg starts, or null when it
 * starts where the journey does. [getOff] marks where the rider leaves a vehicle.
 */
data class JourneyStep(
    val kind: StepKind,
    val text: String,
    val target: GeoPoint? = null,
    val origin: GeoPoint? = null,
    val detail: String? = null,
    val getOff: Boolean = false
)

/** A caveat under the steps, with the icon that says what it is about. */
data class JourneyNote(@DrawableRes val icon: Int, val text: String)

/** A journey broken into steps, plus the caveats that apply to all of them. */
data class JourneyGuide(val steps: List<JourneyStep>, val notes: List<JourneyNote> = emptyList())

internal fun ceilMinutes(seconds: Float) = ceil(seconds / 60f).toInt().coerceAtLeast(1)

/** Metres up to a kilometre, then kilometres to one decimal, as the rider's language writes them. */
internal fun formatDistance(context: Context, metres: Float): String =
    if (metres < 1_000f) "${metres.roundToInt()} m"
    else DecimalFormat("0.#", DecimalFormatSymbols(context.resources.configuration.locales[0]))
        .format(metres / 1_000f) + " km"

internal fun formatClock(seconds: Int, dayOffset: Int = 0): String = "%02d:%02d".format(
    (seconds / 3600) % 24,
    (seconds / 60) % 60
) + if (dayOffset > 0) " (+$dayOffset)" else ""

/** Turns a planned journey into the short steps shown in the journey sheet. */
object JourneySteps {
    // A shorter walk is just standing at the stop; a step for it would be noise.
    private const val MIN_WALK_METRES = 20f
    private const val STALE_POSITION_SECONDS = 120
    // The planner's walking pace.
    private const val WALKING_SPEED = 1.2f
    // Below this chance of making a change, the rider is told it is tight rather than how long to wait.
    private const val TIGHT_CHANGE_CHANCE = 0.8f

    fun forBus(context: Context, bus: JourneyChoice) = JourneyGuide(
        buildList {
            addAll(firstBusLeg(context, bus))
            walkToDestination(context, bus.destinationWalkMetres, bus.lastStop,
                GeoPoint(bus.finalLatitude, bus.finalLongitude))?.let(::add)
        },
        busNotes(context, bus)
    )

    fun forTrain(context: Context, train: ScheduledTrainJourney, destination: GeoPoint? = null) = JourneyGuide(
        buildList {
            walkTo(context, train.walkingMetres, train.boardAt)?.let(::add)
            addAll(trainLeg(context, train))
            walkToDestination(context, train.destinationWalkMetres, train.destination.asTransitStop(), destination)
                ?.let(::add)
        },
        listOf(timetableNote(context, train))
    )

    fun forMixed(context: Context, mixed: MixedJourney, destination: GeoPoint? = null): JourneyGuide {
        val bus = mixed.bus
        val train = mixed.train
        val steps = buildList {
            if (mixed.busFirst) {
                addAll(firstBusLeg(context, bus))
                walkTo(context, bus.destinationWalkMetres, train.boardAt, bus.lastStop)?.let(::add)
                addAll(trainLeg(context, train))
                walkToDestination(context, train.destinationWalkMetres, train.destination.asTransitStop(),
                    destination)?.let(::add)
            } else {
                walkTo(context, train.walkingMetres, train.boardAt)?.let(::add)
                addAll(trainLeg(context, train))
                walkTo(context, bus.walkingMetres, bus.boardAt, train.destination.asTransitStop())?.let(::add)
                // The bus is judged against the clock when the train arrives, not now.
                addAll(busLeg(context, bus, withEta = false))
                walkToDestination(context, bus.destinationWalkMetres, bus.lastStop,
                    destination ?: GeoPoint(bus.finalLatitude, bus.finalLongitude))?.let(::add)
            }
        }
        // A bus planned for after the train is judged by the clock then; "in N min" from now would mislead.
        return JourneyGuide(steps, busNotes(context, bus, withBackup = mixed.busFirst) +
            timetableNote(context, train))
    }

    fun forWalking(context: Context, metres: Int, minutes: Int, destination: GeoPoint) = JourneyGuide(
        listOf(JourneyStep(StepKind.WALK,
            context.getString(R.string.step_walk_to_destination, formatDistance(context, metres.toFloat())),
            destination)),
        listOf(JourneyNote(R.drawable.ic_info, context.getString(R.string.walking_selected, metres, minutes)))
    )

    /**
     * The walk to the boarding stop and the ride, with when to set off so the bus is not missed:
     * its arrival less the walk. It rides on the walk step, or on boarding when there is no walk.
     */
    private fun firstBusLeg(context: Context, bus: JourneyChoice): List<JourneyStep> {
        val steps = listOfNotNull(walkTo(context, bus.walkingMetres, bus.boardAt)) +
            busLeg(context, bus, withEta = true)
        val leave = leaveBy(context, bus)
        val first = steps.first()
        return listOf(first.copy(detail = listOfNotNull(first.detail, leave).joinToString(" · "))) +
            steps.drop(1)
    }

    internal fun leaveBy(context: Context, bus: JourneyChoice): String {
        val walkSeconds = if (bus.walkingMetres < MIN_WALK_METRES) 0f else bus.walkingMetres / WALKING_SPEED
        val minutes = ((bus.boardingEtaSeconds - walkSeconds) / 60f).toInt()
        return if (minutes < 1) context.getString(R.string.leave_now)
        else context.getString(R.string.leave_in, minutes)
    }

    private fun busLeg(context: Context, bus: JourneyChoice, withEta: Boolean): List<JourneyStep> = buildList {
        add(JourneyStep(StepKind.BUS, context.getString(R.string.step_board_bus, bus.pattern.route),
            detail = if (withEta) context.getString(R.string.estimated_minutes,
                ceilMinutes(bus.boardingEtaSeconds)) else null))
        val exit = bus.transferAt ?: bus.destination
        add(JourneyStep(StepKind.BUS, stopName(exit.name), getOff = true))
        bus.secondLeg?.let { leg ->
            walkTo(context, bus.transferWalkMetres, leg.boardAt, exit)?.let(::add)
            add(JourneyStep(StepKind.TRANSFER, context.getString(R.string.step_change_bus,
                leg.pattern.route, stopName(leg.boardAt.name)),
                detail = listOfNotNull(
                    if (withEta) context.getString(R.string.estimated_minutes,
                        ceilMinutes(leg.boardingEtaSeconds)) else null,
                    changeWait(context, leg, bus.transferChance)
                ).joinToString(" · ")))
            add(JourneyStep(StepKind.BUS, stopName(leg.exitAt.name), getOff = true))
        }
    }

    /** How long the rider waits for the second bus, or a warning when the change may be missed. */
    private fun changeWait(context: Context, leg: JourneyLeg, chance: Float): String =
        if (chance < TIGHT_CHANGE_CHANCE || leg.waitSeconds < 60f) context.getString(R.string.change_tight)
        else context.getString(R.string.change_wait, (leg.waitSeconds / 60f).roundToInt())

    private fun trainLeg(context: Context, train: ScheduledTrainJourney): List<JourneyStep> {
        val kind = if (train.isMetro) StepKind.METRO else StepKind.TRAIN
        // A metro train has no number to look for, only the line and where it is going.
        val board = if (train.isMetro) context.getString(R.string.step_take_metro, train.number, stopName(train.headsign))
            else context.getString(R.string.step_take_train,
                trainCategory(train.category), train.number, stopName(train.headsign))
        return listOf(
            JourneyStep(kind, board, detail = formatClock(train.departureSeconds, train.dayOffset)),
            JourneyStep(kind, stopName(train.destination.name),
                detail = formatClock(train.arrivalSeconds, train.dayOffset), getOff = true)
        )
    }

    private fun busNotes(context: Context, bus: JourneyChoice, withBackup: Boolean = true): List<JourneyNote> {
        val age = maxOf(bus.vehicle.ageSeconds, bus.secondLeg?.vehicle?.ageSeconds ?: 0)
        return listOfNotNull(
            bus.backup?.takeIf { withBackup }?.let {
                JourneyNote(R.drawable.ic_history,
                    context.getString(R.string.missed_it_next_bus, it.route, ceilMinutes(it.etaSeconds)))
            },
            bus.turnaround?.let {
                JourneyNote(R.drawable.ic_schedule, context.getString(R.string.bus_turnaround_note,
                    bus.pattern.route, ceilMinutes(it.departsInSeconds)))
            },
            if (age > STALE_POSITION_SECONDS) {
                JourneyNote(R.drawable.ic_schedule, context.getString(R.string.bus_position_age, (age + 59) / 60))
            } else null
        )
    }

    /** The short names on station boards: "REGIONALE VELOCE" is "RV". */
    internal fun trainCategory(category: String) = when (category.trim().uppercase()) {
        "REGIONALE" -> "R"
        "REGIONALE VELOCE" -> "RV"
        else -> TransitIndex.readableCase(category.trim())
    }

    private fun timetableNote(context: Context, train: ScheduledTrainJourney) =
        JourneyNote(if (train.isMetro) R.drawable.ic_metro else R.drawable.ic_train,
            context.getString(R.string.train_timetable_note))

    private fun walkTo(context: Context, metres: Float, stop: TransitStop, from: TransitStop? = null) =
        walkTo(context, metres, stop.name, GeoPoint(stop.latitude, stop.longitude), from)

    private fun walkTo(context: Context, metres: Float, stop: TrainStop, from: TransitStop? = null) =
        walkTo(context, metres, stop.name, GeoPoint(stop.latitude, stop.longitude), from)

    private fun walkTo(context: Context, metres: Float, name: String, to: GeoPoint, from: TransitStop?): JourneyStep? {
        if (metres < MIN_WALK_METRES) return null
        return JourneyStep(StepKind.WALK,
            context.getString(R.string.step_walk_to, formatDistance(context, metres), stopName(name)),
            to, from?.let { GeoPoint(it.latitude, it.longitude) })
    }

    private fun walkToDestination(context: Context, metres: Float, from: TransitStop, to: GeoPoint?): JourneyStep? =
        if (metres < MIN_WALK_METRES) null
        else JourneyStep(StepKind.WALK,
            context.getString(R.string.step_walk_to_destination, formatDistance(context, metres)),
            to, GeoPoint(from.latitude, from.longitude))

    private fun TrainStop.asTransitStop() = TransitStop(id, name, latitude, longitude)

    /** Where the rider gets off the last bus. */
    private val JourneyChoice.lastStop: TransitStop get() = secondLeg?.exitAt ?: destination

    private fun stopName(name: String) = TransitIndex.cleanStopName(name)
}
