package it.mato.livebus

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class SavedPlacesTest {
    private lateinit var places: SavedPlaces

    @Before fun startEmpty() {
        RuntimeEnvironment.getApplication().getSharedPreferences("saved_places", 0).edit().clear().commit()
        places = SavedPlaces(RuntimeEnvironment.getApplication())
    }

    private fun place(name: String, address: String = "", lat: Double = 45.07, lon: Double = 7.68,
                      id: String = name) = SavedPlace(id, name, address, lat, lon)

    @Test fun savedPlacesSurviveRestartAndComeBackByName() {
        assertNull(places.save(place("Mum’s", "Via Roma 14 · Cambiano", 45.0123, 7.7654)))

        val reopened = SavedPlaces(RuntimeEnvironment.getApplication())
        val mum = reopened.byName("mum’s")!!
        assertEquals("Via Roma 14 · Cambiano", mum.address)
        assertEquals(45.0123, mum.latitude, 1e-9)
        assertEquals(7.7654, mum.longitude, 1e-9)
    }

    @Test fun namesAreUniqueWhateverTheCaseAccentsOrSpacing() {
        assertNull(places.save(place("Caffè Mulassano")))

        assertEquals(SavedPlaces.NameProblem.TAKEN, places.save(place("  caffe   MULASSANO ", id = "other")))
        assertEquals(SavedPlaces.NameProblem.BLANK, places.save(place("   ", id = "blank")))
        assertEquals(1, places.all().size)
    }

    @Test fun renamingKeepsThePlaceAndFreesTheOldName() {
        places.save(place("Work", lat = 45.1, id = "work"))

        assertNull(places.save(place("Office", lat = 45.1, id = "work")))
        assertNull(places.save(place("Work", lat = 45.2, id = "new-work")))
        assertEquals(listOf("Office", "Work"), places.all().map { it.name })
        // Saving a place under its own name again is not a clash with itself.
        assertNull(places.save(place("Office", lat = 45.1, id = "work")))
    }

    @Test fun removingForgetsThePlace() {
        val home = place("Home")
        places.save(home)
        places.remove(home)

        assertTrue(places.isEmpty())
        assertNull(places.byName("Home"))
    }

    @Test fun savedNamesAreCleanedAndNeverTooLong() {
        places.save(place("  Dalla   nonna  ", id = "a"))
        places.save(place("x".repeat(200), id = "b"))

        assertNotNull(places.byName("Dalla nonna"))
        assertTrue(places.all().all { it.name.length <= SavedPlaces.MAX_NAME_LENGTH })
    }

    @Test fun searchFindsAPlaceByItsNewNameOrByTheAddressItWasFoundAs() {
        places.save(place("Mum’s", "Via Roma 14 · Cambiano", id = "mum"))
        places.save(place("Gym", "Via Roma 140 · Torino", id = "gym"))

        assertEquals(listOf("Mum’s"), places.search("mum").map { it.name })
        assertEquals(listOf("Mum’s"), places.search("via roma 14").map { it.name })
        // House 14 is not house 140.
        assertEquals(listOf("Gym"), places.search("roma 140").map { it.name })
        assertTrue(places.search("via roma 1").isEmpty())
        assertTrue(places.search("pizza").isEmpty())
        assertEquals(2, places.search("  ").size)
    }

    @Test fun searchPutsTheExactNameBeforeLongerOnes() {
        places.save(place("Home office", id = "a"))
        places.save(place("Home", id = "b"))
        places.save(place("Ex home", id = "c"))

        assertEquals(listOf("Home", "Home office", "Ex home"), places.search("home").map { it.name })
    }

    @Test fun placeAtASpotIsFoundWithinAFewMetresOnly() {
        places.save(place("Door", lat = 45.0700, lon = 7.6800))

        assertEquals("Door", places.at(45.07001, 7.68001)?.name)
        assertNull(places.at(45.0702, 7.6800))
    }

    @Test fun labelDoesNotRepeatTheNameItWasFoundAs() {
        assertEquals("Via Roma 14 · Cambiano",
            place("Via Roma 14", "Via Roma 14 · Cambiano").label)
        assertEquals("Mum’s · Via Roma 14 · Cambiano",
            place("Mum’s", "Via Roma 14 · Cambiano").label)
        assertEquals("Spot", place("Spot").label)
    }

    @Test fun suggestionsListSavedPlacesFirstWithoutDuplicatingTheirSpot() {
        val mum = place("Mum’s", "Via Roma 14 · Cambiano", 45.0123, 7.7654)
        val found = listOf(
            OfflinePlace("Via Roma 14", "Via Roma 14 · Cambiano", 45.01231, 7.76541, "address", true),
            OfflinePlace("Via Roma 14", "Via Roma 14 · Orbassano", 44.99, 7.54, "address", false))

        val merged = SavedPlaces.merge(listOf(mum), found, "via roma 14", limit = 12)

        assertEquals(listOf("★ Mum’s · Via Roma 14 · Cambiano", "Via Roma 14 · Orbassano"),
            merged.map { it.label })
        assertEquals("Mum’s", merged.first().name)
        assertEquals(SavedPlace.KIND, merged.first().kind)
        assertEquals(1, SavedPlaces.merge(listOf(mum), found, "x", limit = 1).size)
    }

    @Test fun savedSuggestionIsExactOnlyWhenTheNameIsWhatWasTyped() {
        val home = place("Casa")
        assertTrue(SavedPlaces.merge(listOf(home), emptyList(), "casa", 12).single().exactMatch)
        assertFalse(SavedPlaces.merge(listOf(home), emptyList(), "cas", 12).single().exactMatch)
    }

    @Test fun mapPinCoordinatesRoundTripAndRejectNonsense() {
        val text = MapPin.format(45.070312, 7.686899)
        assertEquals("45.07031, 7.68690", text)
        assertEquals(45.07031 to 7.6869, MapPin.parse(text))
        assertEquals(45.07 to -7.5, MapPin.parse(" 45.07 ; -7.5 "))
        assertNull(MapPin.parse("Via Roma 14"))
        assertNull(MapPin.parse("45, 7"))
        assertNull(MapPin.parse("95.0, 7.0"))
        assertNull(MapPin.parse("45.0, 7.0 Torino"))
    }
}
