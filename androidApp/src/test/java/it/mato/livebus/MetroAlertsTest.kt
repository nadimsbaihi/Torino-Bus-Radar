package it.mato.livebus

import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Test

class MetroAlertsTest {
    private val now = 1_760_454_000L + 86_400L * 400

    private fun alert(header: String, route: String, start: Long?, end: Long?) =
        GtfsRealtime.FeedEntity.newBuilder().setId(header).setAlert(GtfsRealtime.Alert.newBuilder()
            .addInformedEntity(GtfsRealtime.EntitySelector.newBuilder().setRouteId(route))
            .addActivePeriod(GtfsRealtime.TimeRange.newBuilder().apply {
                start?.let(::setStart)
                end?.let(::setEnd)
            })
            .setHeaderText(GtfsRealtime.TranslatedString.newBuilder().addTranslation(
                GtfsRealtime.TranslatedString.Translation.newBuilder().setText(header).setLanguage("it"))))
            .build()

    private fun feed(vararg entities: GtfsRealtime.FeedEntity) = GtfsRealtime.FeedMessage.newBuilder()
        .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
        .addAllEntity(entities.toList()).build()

    @Test fun onlyCurrentShortLivedNoticesAboutTheMetroCount() {
        val notices = MetroAlerts.from(feed(
            // GTT's standing bulletin, posted for ten years: not news.
            alert("Situazione aggiornata ascensori metro.", "METROU", 1_760_454_000L, 2_076_003_900L),
            alert("Metro interrotta tra Fermi e Paradiso", "gtt:METROU", now - 3_600, now + 7_200),
            // Over already, not yet begun, or about a bus.
            alert("Ieri", "METROU", now - 7_200, now - 3_600),
            alert("Domani", "METROU", now + 3_600, now + 7_200),
            alert("Linea 4 deviata", "4U", now - 3_600, now + 7_200)
        ), now)

        assertEquals(listOf("Metro interrotta tra Fermi e Paradiso"), notices)
    }

    @Test fun aNoticeWithNoEndIsTreatedAsStanding() {
        assertEquals(emptyList<String>(), MetroAlerts.from(feed(alert("Avviso", "METROU", now - 60, null)), now))
    }
}
