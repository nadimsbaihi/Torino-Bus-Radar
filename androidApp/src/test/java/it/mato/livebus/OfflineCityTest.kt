package it.mato.livebus

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OfflineCityTest {
    private val city get() = (RuntimeEnvironment.getApplication() as MatoApplication).offlineCity

    @Test fun exactPlaceAndPrefixResolveInsideTorino() {
        val comala = city.search("COMALA").first()
        assertTrue(comala.exactMatch)
        assertTrue(comala.label.contains("Corso Francesco Ferrucci 65/a"))
        assertEquals(45.0692, comala.latitude, 0.0002)
        assertEquals(7.6560, comala.longitude, 0.0002)
        assertEquals(comala, city.search("Coma").first().copy(exactMatch = true))
    }

    @Test fun gttStopsAreSearchableAsDestinations() {
        val stop = city.search("Fermata 74 Ospedale Giovanni Bosco")
            .first { it.kind == "bus_stop" }
        assertTrue(stop.label.contains("GTT stop"))
        assertEquals(45.09592, stop.latitude, 0.00001)
        assertEquals(7.70151, stop.longitude, 0.00001)
    }

    @Test fun regionalTrainStationsAreSearchableAsDestinations() {
        val station = city.search("Torino Lingotto F.S.")
            .first { it.kind == "train_station" }
        assertTrue(station.label.contains("Regional train station"))
        assertEquals(45.026779, station.latitude, 0.00001)
        assertEquals(7.657283, station.longitude, 0.00001)
        assertTrue(city.search("Fossata").any { it.kind == "train_station" })
    }

    @Test fun houseNumberIsNotMatchedAsPrefixOfAnotherHouseNumber() {
        // OSM doesn't contain number 1 here in this extract; don't substitute 10/11/etc.
        assertTrue(city.search("Via Chiesa della Salute 1").isEmpty())
        assertTrue(city.search("Via Chiesa della Salute").isNotEmpty())
    }

    @Test fun quotedInputCannotChangeSearchExpression() {
        assertTrue(city.search("\" OR nonexistentplace98452").isEmpty())
        assertTrue(city.search("   ").isEmpty())
        assertTrue(city.search("@@@").isEmpty())
    }

    @Test fun repeatedOpeningReusesVerifiedMapWithoutCopying() {
        val before = city.mapFile.lastModified()
        val reopened = OfflineCity.open(RuntimeEnvironment.getApplication())
        assertEquals(before, reopened.mapFile.lastModified())
        assertEquals(city.mapCacheKey, reopened.mapCacheKey)
        assertEquals(city.search("Comala"), reopened.search("Comala"))
    }

    @Test fun streetTypeAbbreviationsFindTheFullName() {
        val full = city.search("Corso Francia 186").first()
        assertEquals(full, city.search("c.so Francia 186").first())
        assertEquals(full, city.search("cso francia 186").first())
        assertTrue(city.search("p.za Castello").isNotEmpty())
    }

    @Test fun aSingleLetterIsNotASearch() {
        assertTrue(city.search("v").isEmpty())
        assertTrue(city.search("v rom").isNotEmpty())
    }

    @Test fun equallyGoodMatchesAreOrderedByDistanceFromTheUser() {
        val east = city.search("Via Roma", 45.0938, 7.8822).first { it.name.equals("via roma", true) }
        val west = city.search("Via Roma", 45.0448, 7.9204).first { it.name.equals("via roma", true) }
        assertEquals(7.8822, east.longitude, 0.001)
        assertEquals(7.9204, west.longitude, 0.001)
    }
}
