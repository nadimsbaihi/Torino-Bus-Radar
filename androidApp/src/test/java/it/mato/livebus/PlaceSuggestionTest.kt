package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class PlaceSuggestionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun place(name: String, label: String, kind: String) = OfflinePlace(name, label, 45.07, 7.68, kind, false)

    @Test fun anAddressNamesItsTown() {
        val row = PlaceSuggestion.of(context, place("Via Roma 1", "Via Roma 1 · Torino", "address"))
        assertEquals(PlaceSuggestion(R.drawable.ic_place, "Via Roma 1", "Torino"), row)
    }

    @Test fun aStopAndAStationSayWhatTheyAre() {
        assertEquals(PlaceSuggestion(R.drawable.ic_bus, "Fermata 747 - SOFIA CAP", "GTT stop"),
            PlaceSuggestion.of(context, place("Fermata 747 - SOFIA CAP", "Fermata 747 - SOFIA CAP · GTT stop", "bus_stop")))
        assertEquals(PlaceSuggestion(R.drawable.ic_train, "Torino Porta Susa", "Train station"),
            PlaceSuggestion.of(context, place("Torino Porta Susa", "Torino Porta Susa · Regional train station", "train_station")))
    }

    @Test fun savedAndRecentPlacesAreMarkedAsSuch() {
        val saved = SavedPlace("1", "Work", "Via Nizza 10 · Torino", 45.05, 7.67).asOfflinePlace(exactMatch = true)
        assertEquals(PlaceSuggestion(R.drawable.ic_bookmark_filled, "Work", "Via Nizza 10 · Torino"),
            PlaceSuggestion.of(context, saved))
        assertEquals(PlaceSuggestion(R.drawable.ic_history, "Via Nizza", "Recent"),
            PlaceSuggestion.of(context, place("Via Nizza", "Via Nizza", RecentDestinations.KIND)))
    }

    @Test fun aSpotOnTheMapIsNotTitledWithItsCoordinates() {
        val row = PlaceSuggestion.of(context, place("45.13660, 7.61250", "45.13660, 7.61250", RecentDestinations.KIND))
        assertEquals(PlaceSuggestion(R.drawable.ic_history, "Spot on the map", "45.13660, 7.61250"), row)
    }

    @Test fun oneStationMappedManyTimesIsOfferedOnce() {
        val found = listOf(
            OfflinePlace("Porta Nuova", "Porta Nuova", 45.0628, 7.6790, "place", true),
            OfflinePlace("Porta Nuova", "Porta Nuova · Via Paolo Sacchi 4 · Torino", 45.0625, 7.6770, "place", true),
            OfflinePlace("Porta Nuova", "Porta Nuova · GTT stop", 45.0627, 7.6775, "bus_stop", true),
            OfflinePlace("Porta Nuova FS", "Porta Nuova FS", 45.0632, 7.6788, "place", false),
            // Same name, other side of town: a different place.
            OfflinePlace("Porta Nuova", "Porta Nuova", 45.1100, 7.6790, "place", true))

        val shown = PlaceSuggestion.collapseNearby(found)

        assertEquals(listOf(found[0], found[2], found[3], found[4]), shown)
    }
}
