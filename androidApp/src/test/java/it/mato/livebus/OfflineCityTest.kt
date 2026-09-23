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
}
