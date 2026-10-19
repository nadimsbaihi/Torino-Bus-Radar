package it.mato.livebus

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import it.mato.livebus.databinding.ActivityMainBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class BusMapVisibilityTest {
    @Test fun searchPresetsRestoreAndSaveTrainSelection() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_MatoLiveBus)
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        set(activity, "binding", binding)
        val preferences = activity.getSharedPreferences("active_journey", 0)
        preferences.edit().putBoolean("include_trains", false).commit()
        MainActivity::class.java.getDeclaredMethod("setupSearchPresets").apply {
            isAccessible = true
            invoke(activity)
        }

        assertFalse(binding.includeTrainsSwitch.isChecked)
        binding.includeTrainsSwitch.isChecked = true
        assertTrue(preferences.getBoolean("include_trains", false))
        binding.map.onDetach()
    }

    @Test fun routeSelectionFiltersBusesAndClearingItRestoresTheFullOverview() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_MatoLiveBus)
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        set(activity, "binding", binding)
        val now = System.currentTimeMillis() / 1000
        set(activity, "liveVehicles", (1..40).map {
            val route = if (it == 40) "gtt:60U" else "56U"
            LiveVehicle("bus-$it", route, null, 45.07, 7.65 + it * 0.001, null, now - 200)
        })
        val render = MainActivity::class.java.getDeclaredMethod("renderVehicles").apply {
            isAccessible = true
        }
        render.invoke(activity)
        val buses = binding.map.overlays.filterIsInstance<Marker>()
        assertEquals(40, buses.size)
        assertTrue(buses.all { it.alpha < 1f && it.snippet.contains("4 min") })
        MainActivity::class.java.getDeclaredMethod("updateLiveStatus").apply {
            isAccessible = true
            invoke(activity)
        }
        // The markers say how old each position is; the label only counts the buses.
        assertTrue(binding.liveStatus.text.toString().contains("40 live buses"))
        assertFalse(binding.liveStatus.text.toString().contains("min"))

        set(activity, "selectedRoute", "60")
        render.invoke(activity)
        assertEquals(listOf(activity.getString(R.string.vehicle_title, "60")),
            binding.map.overlays.filterIsInstance<Marker>().map { it.title })

        set(activity, "selectedRoute", "99")
        render.invoke(activity)
        assertTrue(binding.map.overlays.filterIsInstance<Marker>().isEmpty())

        set(activity, "selectedRoute", null)
        render.invoke(activity)
        assertEquals(40, binding.map.overlays.filterIsInstance<Marker>().size)
        binding.map.onDetach()
    }

    @Test fun linesThatDifferOnlyBySuffixStayApartOnTheMap() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_MatoLiveBus)
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        set(activity, "binding", binding)
        val stops = listOf(TransitStop("a", "A", 45.06, 7.66), TransitStop("b", "B", 45.07, 7.66))
        fun line(id: String, name: String) = TransitPattern("$id:0", id, name, "End", "0", emptySet(), stops)
        val index = TransitIndex::class.java.getDeclaredConstructor(List::class.java)
            .apply { isAccessible = true }.newInstance(listOf(
                line("13U", "13"), line("13BU", "13+"), line("59U", "59"), line("59BU", "59/"),
                line("10U", "10"), line("10NU", "10N")))
        set(activity, "transitIndex\$delegate", lazyOf(index))
        val now = System.currentTimeMillis() / 1000
        set(activity, "liveVehicles", listOf("13U", "13BU", "59U", "59BU", "10U", "gtt:10NU").mapIndexed { i, route ->
            LiveVehicle("bus-$i", route, null, 45.07, 7.65 + i * 0.001, null, now)
        })
        val render = MainActivity::class.java.getDeclaredMethod("renderVehicles").apply { isAccessible = true }
        fun titles() = binding.map.overlays.filterIsInstance<Marker>().map { it.title }
        render.invoke(activity)
        assertEquals(listOf("13", "13+", "59", "59/", "10", "10N").map { activity.getString(R.string.vehicle_title, it) },
            titles())

        set(activity, "selectedRoute", "13")
        render.invoke(activity)
        assertEquals(listOf(activity.getString(R.string.vehicle_title, "13")), titles())
        set(activity, "selectedRoute", "10N")
        render.invoke(activity)
        assertEquals(listOf(activity.getString(R.string.vehicle_title, "10N")), titles())
        binding.map.onDetach()
    }

    @Test fun transferRouteDrawsTheSecondLegAndKeepsDestinationAboveBuses() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_MatoLiveBus)
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        set(activity, "binding", binding)
        val board = TransitStop("a", "Board", 45.10, 7.67)
        val transfer = TransitStop("b", "Transfer", 45.08, 7.67)
        val bend = TransitStop("c", "Bend", 45.08, 7.65)
        val exit = TransitStop("d", "Exit", 45.07, 7.65)
        val first = TransitPattern("first", "60U", "60", "Transfer", "0", emptySet(), listOf(board, transfer))
        val second = TransitPattern("second", "68U", "68", "Exit", "0", emptySet(), listOf(transfer, bend, exit))
        val bus = LiveVehicle("bus", "60U", null, 45.10, 7.67, null, System.currentTimeMillis() / 1000)
        val secondBus = bus.copy(id = "bus2", routeId = "68U", latitude = 45.08)
        set(activity, "liveVehicles", listOf(bus, secondBus,
            bus.copy(id = "opposite", latitude = 45.12, bearing = 180f),
            bus.copy(id = "unrelated", routeId = "56U")))
        val journey = JourneyChoice(first, bus, board, 0, exit,
            100f, 10f, 1, 20f, 45.07, 7.65,
            secondLeg = JourneyLeg(second, secondBus, transfer, exit, 2),
            transferAt = transfer)
        set(activity, "journeyChoice", journey)
        set(activity, "selectedRoute", "60")
        val render = MainActivity::class.java.getDeclaredMethod("renderVehicles").apply {
            isAccessible = true
        }
        render.invoke(activity)
        assertTrue(binding.map.overlays.filterIsInstance<Polyline>().any { line ->
            line.actualPoints.any { it.latitude == bend.latitude && it.longitude == bend.longitude }
        })
        val markers = binding.map.overlays.filterIsInstance<Marker>()
        assertTrue(markers.indexOfFirst { it.title == activity.getString(R.string.final_destination) } >
            markers.indexOfFirst { it.title.contains("60") })
        assertEquals(listOf(45.10, 45.08), markers.filter {
            it.title == activity.getString(R.string.vehicle_title, "60") ||
                it.title == activity.getString(R.string.vehicle_title, "68")
        }.map { it.position.latitude })
        assertFalse(markers.any { it.title == activity.getString(R.string.vehicle_title, "56") })

        set(activity, "walkingMode", true)
        render.invoke(activity)
        assertTrue(binding.map.overlays.filterIsInstance<Marker>().none {
            it.title == activity.getString(R.string.vehicle_title, "60") ||
                it.title == activity.getString(R.string.vehicle_title, "68")
        })

        set(activity, "walkingMode", false)
        set(activity, "journeyChoice", journey.copy(secondLeg = null, transferAt = null))
        render.invoke(activity)
        assertEquals(listOf(45.10), binding.map.overlays.filterIsInstance<Marker>().filter {
            it.title == activity.getString(R.string.vehicle_title, "60") ||
                it.title == activity.getString(R.string.vehicle_title, "68")
        }.map { it.position.latitude })

        MainActivity::class.java.getDeclaredMethod("consumeSharedDirections", Intent::class.java).apply {
            isAccessible = true
            invoke(activity, Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Bus 56")
            })
        }
        assertEquals(listOf(activity.getString(R.string.vehicle_title, "56")),
            binding.map.overlays.filterIsInstance<Marker>().map { it.title })
        assertTrue(binding.map.overlays.filterIsInstance<Polyline>().isEmpty())
        binding.map.onDetach()
    }

    @Test fun tappingABusHighlightsItsCourseUntilTheMapIsTapped() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_MatoLiveBus)
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        set(activity, "binding", binding)
        val stops = listOf(TransitStop("a", "A", 45.06, 7.66), TransitStop("b", "B", 45.07, 7.66),
            TransitStop("c", "C", 45.08, 7.66), TransitStop("d", "D", 45.09, 7.66))
        val pattern = TransitPattern("p", "60U", "60", "D", "0", setOf("trip"), stops)
        val index = TransitIndex::class.java.getDeclaredConstructor(List::class.java)
            .apply { isAccessible = true }.newInstance(listOf(pattern))
        set(activity, "transitIndex\$delegate", lazyOf(index))
        val bus = LiveVehicle("bus", "60U", "trip", 45.075, 7.66, 0f, System.currentTimeMillis() / 1000)
        val other = LiveVehicle("other", "56U", null, 45.08, 7.67, 0f, System.currentTimeMillis() / 1000)
        set(activity, "liveVehicles", listOf(bus, other))
        val render = MainActivity::class.java.getDeclaredMethod("renderVehicles").apply { isAccessible = true }
        render.invoke(activity)
        assertTrue(binding.map.overlays.filterIsInstance<Polyline>().isEmpty())
        fun busTitles() = binding.map.overlays.filterIsInstance<Marker>()
            .filter { it.relatedObject is LiveVehicle }.map { it.title }
        assertEquals(2, busTitles().size)

        val marker = binding.map.overlays.filterIsInstance<Marker>()
            .single { it.title == activity.getString(R.string.vehicle_title, "60") }
        val listener = Marker::class.java.getDeclaredField("mOnMarkerClickListener")
            .apply { isAccessible = true }.get(marker) as Marker.OnMarkerClickListener
        assertTrue(listener.onMarkerClick(marker, binding.map))
        assertEquals("bus", get(activity, "highlightedVehicleId"))
        // The other buses leave the map while the course is highlighted.
        assertEquals(listOf(activity.getString(R.string.vehicle_title, "60")), busTitles())
        fun courseLines() = binding.map.overlays.filterIsInstance<Polyline>()
        assertEquals(3, courseLines().size)
        // Part ahead covers the bus and the stops it has yet to reach; the part behind is separate.
        val aheadPoints = courseLines().last().actualPoints
        assertEquals(listOf(45.075, 45.08, 45.09), aheadPoints.map { it.latitude })
        assertEquals(listOf(45.06, 45.07, 45.075), courseLines().first().actualPoints.map { it.latitude })
        // A tapped line opens no empty bubble of its own; the tap goes on to the map.
        val lineListener = Polyline::class.java.getDeclaredField("mOnClickListener")
            .apply { isAccessible = true }
        courseLines().forEach { line ->
            val onClick = lineListener.get(line) as Polyline.OnClickListener
            assertFalse(onClick.onClick(line, binding.map, line.actualPoints.first()))
            assertFalse(line.isInfoWindowOpen)
        }
        // The course sits below every marker, so the bus stays tappable.
        assertTrue(binding.map.overlays.indexOfLast { it is Polyline } <
            binding.map.overlays.indexOfFirst { it is Marker })
        // Every stop gets a small dot, and the bus stays on top of them.
        val dots = binding.map.overlays.filterIsInstance<Marker>().filter { it.relatedObject is TransitStop }
        assertEquals(listOf("A", "B", "C", "D"), dots.map { it.title })
        assertTrue(dots.all { it.icon.intrinsicWidth <= (12 * activity.resources.displayMetrics.density).toInt() })
        assertTrue(binding.map.overlays.indexOf(dots.last()) < binding.map.overlays.indexOf(marker))
        // A tap that nearly hits a dot is meant for that stop; one well away from every stop is not.
        binding.map.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
        binding.map.layout(0, 0, 1080, 2000)
        binding.map.controller.setZoom(17.0)
        binding.map.controller.setCenter(dots.first().position)
        val near = MainActivity::class.java.getDeclaredMethod("courseStopNear", GeoPoint::class.java)
            .apply { isAccessible = true }
        val stopA = dots.first().position
        val reach = binding.map.projection.toPixels(stopA, null).let { at ->
            { dx: Int -> binding.map.projection.fromPixels(at.x + dx, at.y) as GeoPoint }
        }
        val density = activity.resources.displayMetrics.density
        assertEquals("A", (near.invoke(activity, reach((15 * density).toInt())) as TransitStop?)?.let {
            TransitIndex.cleanStopName(it.name) })
        assertNull(near.invoke(activity, reach((40 * density).toInt())))

        // It survives the 15-second refresh without doubling up.
        render.invoke(activity)
        assertEquals(3, courseLines().size)
        assertEquals(listOf(activity.getString(R.string.vehicle_title, "60")), busTitles())

        MainActivity::class.java.getDeclaredMethod("clearHighlightedCourse").apply {
            isAccessible = true
            invoke(activity)
        }
        assertTrue(courseLines().isEmpty())
        assertNull(get(activity, "highlightedVehicleId"))
        // Clearing the highlight brings every bus back.
        assertEquals(2, busTitles().size)

        // Tapping the highlighted bus again leaves its course, even after a refresh rebuilt the marker.
        listener.onMarkerClick(marker, binding.map)
        assertEquals("bus", get(activity, "highlightedVehicleId"))
        render.invoke(activity)
        val rebuilt = binding.map.overlays.filterIsInstance<Marker>()
            .single { it.relatedObject is LiveVehicle }
        val again = Marker::class.java.getDeclaredField("mOnMarkerClickListener")
            .apply { isAccessible = true }.get(rebuilt) as Marker.OnMarkerClickListener
        assertTrue(again.onMarkerClick(rebuilt, binding.map))
        assertNull(get(activity, "highlightedVehicleId"))
        assertTrue(courseLines().isEmpty())
        assertEquals(2, busTitles().size)
        // Tapping it once more highlights it afresh.
        val fresh = binding.map.overlays.filterIsInstance<Marker>()
            .single { it.title == activity.getString(R.string.vehicle_title, "60") }
        (Marker::class.java.getDeclaredField("mOnMarkerClickListener")
            .apply { isAccessible = true }.get(fresh) as Marker.OnMarkerClickListener)
            .onMarkerClick(fresh, binding.map)
        assertEquals("bus", get(activity, "highlightedVehicleId"))

        // A highlighted bus that leaves the feed drops its course and the others return.
        set(activity, "liveVehicles", listOf(other))
        render.invoke(activity)
        assertTrue(courseLines().isEmpty())
        assertNull(get(activity, "highlightedVehicleId"))
        assertEquals(listOf(activity.getString(R.string.vehicle_title, "56")), busTitles())
        binding.map.onDetach()
    }

    @Test fun highlightedCourseFollowsTheRoadAndMarksEveryStop() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_MatoLiveBus)
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        set(activity, "binding", binding)
        // The road bends east between the second and third stops, away from the straight line.
        val stops = listOf(TransitStop("a", "A", 45.062, 7.660), TransitStop("b", "B", 45.068, 7.660),
            TransitStop("c", "C", 45.072, 7.670), TransitStop("d", "D", 45.078, 7.670))
        val road = RouteShape(doubleArrayOf(45.060, 45.070, 45.070, 45.080),
            doubleArrayOf(7.660, 7.660, 7.670, 7.670))
        val pattern = TransitPattern("p", "60U", "60", "D", "0", setOf("trip"), stops, road)
        val index = TransitIndex::class.java.getDeclaredConstructor(List::class.java)
            .apply { isAccessible = true }.newInstance(listOf(pattern))
        set(activity, "transitIndex\$delegate", lazyOf(index))
        val bus = LiveVehicle("bus", "60U", "trip", 45.070, 7.665, 90f, System.currentTimeMillis() / 1000)
        set(activity, "liveVehicles", listOf(bus))
        val render = MainActivity::class.java.getDeclaredMethod("renderVehicles").apply { isAccessible = true }
        render.invoke(activity)
        val marker = binding.map.overlays.filterIsInstance<Marker>().single()
        val listener = Marker::class.java.getDeclaredField("mOnMarkerClickListener")
            .apply { isAccessible = true }.get(marker) as Marker.OnMarkerClickListener
        listener.onMarkerClick(marker, binding.map)

        val lines = binding.map.overlays.filterIsInstance<Polyline>()
        val passed = lines.first().actualPoints
        val ahead = lines.last().actualPoints
        // Both halves meet at the bus and turn at the bend instead of cutting between stops.
        assertEquals(45.070, passed.last().latitude, 1e-6)
        assertEquals(7.665, passed.last().longitude, 1e-6)
        assertEquals(listOf(45.060, 45.070), passed.map { it.latitude }.take(2))
        assertEquals(listOf(45.070, 45.070, 45.080), ahead.map { it.latitude })
        assertEquals(listOf(7.665, 7.670, 7.670), ahead.map { it.longitude })
        assertEquals(listOf("A", "B", "C", "D"), binding.map.overlays.filterIsInstance<Marker>()
            .filter { it.relatedObject is TransitStop }.map { it.title })
        binding.map.onDetach()
    }

    @Test fun fasterTransitStillOffersWalkingAndResetsChoicesForAnError() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_MatoLiveBus)
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        set(activity, "binding", binding)
        val board = TransitStop("a", "Board", 45.0703, 7.6869)
        val exit = TransitStop("b", "Exit", 45.09, 7.6869)
        val pattern = TransitPattern("route", "60U", "60", "Exit", "0", emptySet(), listOf(board, exit))
        val vehicle = LiveVehicle("bus", "60U", null, board.latitude, board.longitude,
            null, System.currentTimeMillis() / 1000)
        val journey = JourneyChoice(pattern, vehicle, board, 0, exit,
            20f, 10f, 1, 20f, exit.latitude, exit.longitude, estimatedTotalSeconds = 300f)
        MainActivity::class.java.getDeclaredMethod("displayJourney",
            String::class.java, Pair::class.java, JourneyChoice::class.java,
            ScheduledTrainJourney::class.java, Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
            invoke(activity, "Destination", exit.latitude to exit.longitude, journey, null, false)
        }
        assertTrue(binding.journeyTitle.text.startsWith("Transit"))
        // The ride is quicker than walking there, so walking is not offered.
        assertEquals(android.view.View.GONE, binding.modeSwitchButton.visibility)
        MainActivity::class.java.getDeclaredMethod("openWalkingDirections",
            org.osmdroid.util.GeoPoint::class.java, org.osmdroid.util.GeoPoint::class.java).apply {
            isAccessible = true
            invoke(activity, org.osmdroid.util.GeoPoint(board.latitude, board.longitude), null)
        }
        val mapsIntent = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
        assertEquals("com.google.android.apps.maps", mapsIntent.`package`)
        assertEquals("google.navigation:q=${board.latitude},${board.longitude}&mode=w",
            mapsIntent.data.toString())
        assertEquals(android.view.View.VISIBLE, binding.alertButton.visibility)
        MainActivity::class.java.getDeclaredMethod("showJourneyMessage", String::class.java).apply {
            isAccessible = true
            invoke(activity, activity.getString(R.string.journey_failed))
        }
        assertEquals(android.view.View.GONE, binding.modeSwitchButton.visibility)
        assertEquals(android.view.View.GONE, binding.alertButton.visibility)
        assertEquals(activity.getString(R.string.your_journey), binding.journeyTitle.text.toString())
        binding.map.onDetach()
    }

    @Test fun aRideFarSlowerThanWalkingLeavesOnlyTheWalk() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_MatoLiveBus)
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        set(activity, "binding", binding)
        val worthTaking = MainActivity::class.java.getDeclaredMethod("worthTaking",
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply { isAccessible = true }
        // A 38-minute walk: tomorrow's first train is no option, a ride a little slower still is.
        assertFalse(worthTaking.invoke(activity, 963 * 60f, 38 * 60f) as Boolean)
        assertTrue(worthTaking.invoke(activity, 50 * 60f, 38 * 60f) as Boolean)
        // A short walk still leaves a quarter of an hour for a ride.
        assertTrue(worthTaking.invoke(activity, 20 * 60f, 6 * 60f) as Boolean)
        assertFalse(worthTaking.invoke(activity, 22 * 60f, 6 * 60f) as Boolean)

        MainActivity::class.java.getDeclaredMethod("showWalkingOnly", String::class.java, Pair::class.java).apply {
            isAccessible = true
            invoke(activity, "Destination", 45.09 to 7.6869)
        }
        assertTrue(binding.journeyTitle.text.startsWith("Walk"))
        assertEquals(android.view.View.GONE, binding.modeSwitchButton.visibility)
        assertEquals(android.view.View.GONE, binding.alertButton.visibility)
        // The one walk carries its own directions button.
        assertEquals(android.view.View.VISIBLE,
            binding.journeySteps.getChildAt(0).findViewById<android.view.View>(R.id.stepDirections).visibility)
        assertTrue(binding.sharedDirections.text.contains(activity.getString(R.string.no_ride_beats_walking)))
        assertEquals(1, binding.journeySteps.childCount)
        binding.map.onDetach()
    }

    @Test fun mixedJourneyShowsBothLegsAndOnlyItsLiveBus() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_MatoLiveBus)
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        set(activity, "binding", binding)
        val board = TransitStop("bus-board", "Bus board", 45.0703, 7.6869)
        val station = TransitStop("station", "Station", 45.061113, 7.677577)
        val trainEnd = TrainStop("train-end", "Train end", 45.026779, 7.657283, 34620, 34740)
        val trainStart = TrainStop("station", "Station", station.latitude, station.longitude, 34200, 34200)
        val pattern = TransitPattern("route", "60U", "60", "Station", "0", emptySet(), listOf(board, station))
        val vehicle = LiveVehicle("selected", "60U", null, board.latitude, board.longitude,
            null, System.currentTimeMillis() / 1000)
        val bus = JourneyChoice(pattern, vehicle, board, 0, station, 0f, 0f, 1, 0f,
            station.latitude, station.longitude, estimatedTotalSeconds = 300f)
        val train = ScheduledTrainJourney("2121", "REGIONALE", "Lingotto", trainStart, trainEnd,
            listOf(trainStart, trainEnd), 0, 1, 0f, 0f, 600f, 34200, 34620)
        val mixed = MixedJourney(bus, train, true, 900f)
        set(activity, "liveVehicles", listOf(vehicle, vehicle.copy(id = "unrelated", routeId = "56U")))
        MainActivity::class.java.getDeclaredMethod("displayJourney",
            String::class.java, Pair::class.java, JourneyChoice::class.java,
            ScheduledTrainJourney::class.java, MixedJourney::class.java,
            Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
            invoke(activity, "Destination", trainEnd.latitude to trainEnd.longitude,
                bus.copy(estimatedTotalSeconds = 1_500f),
                train.copy(estimatedTotalSeconds = 1_800f), mixed, false)
        }

        val steps = stepTexts(binding)
        assertTrue(steps.toString(), "Bus 60" in steps)
        assertTrue(steps.toString(), steps.any { it.startsWith("R 2121 →") })
        assertEquals(android.view.View.GONE, binding.alertButton.visibility)
        assertEquals(1, binding.map.overlays.filterIsInstance<Marker>()
            .count { it.title == activity.getString(R.string.vehicle_title, "60") })
        assertFalse(binding.map.overlays.filterIsInstance<Marker>()
            .any { it.title == activity.getString(R.string.vehicle_title, "56") })
        assertTrue(binding.map.overlays.filterIsInstance<Polyline>().any { line ->
            line.actualPoints.any { it.latitude == trainEnd.latitude && it.longitude == trainEnd.longitude }
        })
        binding.map.onDetach()
    }

    private fun stepTexts(binding: ActivityMainBinding) = (0 until binding.journeySteps.childCount).map {
        binding.journeySteps.getChildAt(it).findViewById<android.widget.TextView>(R.id.stepText).text.toString()
    }

    @Test fun theDisplayedJourneyWeighsWalkingAgainstTime() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_MatoLiveBus)
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        set(activity, "binding", binding)
        val board = TransitStop("board", "Board", 45.0703, 7.6869)
        val busExit = TransitStop("bus-exit", "Bus exit", 45.085, 7.6869)
        val finalStop = TrainStop("final", "Final", 45.09, 7.6869, 36_000, 36_000)
        val trainBoard = TrainStop("train-board", "Train board", board.latitude, board.longitude,
            35_000, 35_000)
        val pattern = TransitPattern("bus", "60U", "60", "Final", "0", emptySet(), listOf(board, busExit))
        val vehicle = LiveVehicle("bus", "60U", null, board.latitude, board.longitude,
            null, System.currentTimeMillis() / 1000)
        val bus = JourneyChoice(pattern, vehicle, board, 0, busExit, 0f, 0f, 1,
            550f, finalStop.latitude, finalStop.longitude, estimatedTotalSeconds = 600f)
        val train = ScheduledTrainJourney("2121", "REGIONALE", "Final", trainBoard, finalStop,
            listOf(trainBoard, finalStop), 0, 1, 0f, 0f, 800f, 35_000, 36_000)
        val display = MainActivity::class.java.getDeclaredMethod("displayJourney",
            String::class.java, Pair::class.java, JourneyChoice::class.java,
            ScheduledTrainJourney::class.java, Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
        }

        // The bus is quicker, but its 550 m walk at the end costs more than the train's extra 200 s.
        display.invoke(activity, "Final", finalStop.latitude to finalStop.longitude, bus, train, false)
        assertEquals(train, get(activity, "trainJourneyChoice"))

        val busToTheDoor = bus.copy(destinationWalkMetres = 0f)
        display.invoke(activity, "Final", finalStop.latitude to finalStop.longitude, busToTheDoor, train, false)
        assertEquals(busToTheDoor, get(activity, "journeyChoice"))
        binding.map.onDetach()
    }

    private fun get(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    @Test fun aTappedBusSaysWhereItHeadsThenHowFarAndHowFresh() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_MatoLiveBus)
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        set(activity, "binding", binding)
        val now = System.currentTimeMillis() / 1000
        set(activity, "liveVehicles", listOf(
            LiveVehicle("1150U", "60U", null, 45.07, 7.65, null, now, label = "14687"),
            LiveVehicle("9643U", "56U", null, 45.08, 7.66, null, now)))
        MainActivity::class.java.getDeclaredMethod("renderVehicles").apply { isAccessible = true }.invoke(activity)

        fun tap(route: String): Marker {
            val marker = binding.map.overlays.filterIsInstance<Marker>()
                .single { it.title == activity.getString(R.string.vehicle_title, route) }
            val listener = Marker::class.java.getDeclaredField("mOnMarkerClickListener")
                .apply { isAccessible = true }.get(marker) as Marker.OnMarkerClickListener
            listener.onMarkerClick(marker, binding.map)
            return marker
        }

        // Where it heads comes second, then distance and age; the fleet number means nothing to a rider.
        val tapped = tap("60")
        assertEquals(activity.getString(R.string.bus_direction_unavailable), tapped.subDescription)
        assertTrue(tapped.snippet.contains(" min"))
        assertFalse(tapped.snippet.contains("14687"))
        assertFalse(tapped.snippet.contains("1150"))
    }

    private fun set(activity: MainActivity, name: String, value: Any?) {
        MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(activity, value)
        }
    }
}
