package it.mato.livebus

import android.content.Intent
import android.view.LayoutInflater
import it.mato.livebus.databinding.ActivityMainBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPopupMenu

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class BusMapVisibilityTest {
    @Test fun searchPresetsRestoreAndSaveTrainSelection() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_MatoLiveBus)
        val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
        set(activity, "binding", binding)
        val preferences = activity.getSharedPreferences("active_journey", 0)
        preferences.edit().putBoolean("include_trains", false)
            .putString("walking_preference", WalkingPreference.LESS.name).commit()
        MainActivity::class.java.getDeclaredMethod("setupSearchPresets").apply {
            isAccessible = true
            invoke(activity)
        }

        assertFalse(binding.includeTrainsSwitch.isChecked)
        assertEquals(activity.getString(R.string.walking_preset_less),
            binding.walkingPresetButton.text.toString())
        binding.includeTrainsSwitch.isChecked = true
        assertTrue(preferences.getBoolean("include_trains", false))
        binding.walkingPresetButton.performClick()
        val popup = ShadowPopupMenu.getLatestPopupMenu()
        val moreWalking = popup.menu.findItem(WalkingPreference.MORE.ordinal + 1)
        assertTrue(moreWalking.isCheckable)
        org.robolectric.Shadows.shadowOf(popup).onMenuItemClickListener.onMenuItemClick(moreWalking)
        assertEquals(WalkingPreference.MORE.name,
            preferences.getString("walking_preference", null))
        assertEquals(activity.getString(R.string.walking_preset_more),
            binding.walkingPresetButton.text.toString())
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
            LiveVehicle("bus-$it", route, null, 45.07, 7.65 + it * 0.001, null, now - 240)
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
        assertTrue(binding.liveStatus.text.toString().contains("40 buses"))
        assertTrue(binding.liveStatus.text.toString().contains("4 min"))

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
        assertEquals(android.view.View.VISIBLE, binding.travelModeChoices.visibility)
        assertTrue(binding.walkButton.text.contains("min"))
        assertTrue(binding.busAnywayButton.text.contains("min"))
        assertEquals(android.view.View.VISIBLE, binding.boardStopDirectionsButton.visibility)
        MainActivity::class.java.getDeclaredMethod("openWalkingDirections", org.osmdroid.util.GeoPoint::class.java).apply {
            isAccessible = true
            invoke(activity, org.osmdroid.util.GeoPoint(board.latitude, board.longitude))
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
        assertEquals(android.view.View.GONE, binding.travelModeChoices.visibility)
        assertEquals(android.view.View.GONE, binding.boardStopDirectionsButton.visibility)
        assertEquals(android.view.View.GONE, binding.alertButton.visibility)
        assertEquals(activity.getString(R.string.your_journey), binding.journeyTitle.text.toString())
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

        assertTrue(binding.sharedDirections.text.contains("Live bus 60"))
        assertTrue(binding.sharedDirections.text.contains("scheduled REGIONALE 2121"))
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

    @Test fun walkingPresetChangesTheDisplayedJourney() {
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

        set(activity, "walkingPreference", WalkingPreference.LESS)
        display.invoke(activity, "Final", finalStop.latitude to finalStop.longitude, bus, train, false)
        assertEquals(train, get(activity, "trainJourneyChoice"))

        set(activity, "walkingPreference", WalkingPreference.MORE)
        display.invoke(activity, "Final", finalStop.latitude to finalStop.longitude, bus, train, false)
        assertEquals(bus, get(activity, "journeyChoice"))
        binding.map.onDetach()
    }

    private fun get(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun set(activity: MainActivity, name: String, value: Any?) {
        MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(activity, value)
        }
    }
}
