package it.mato.livebus

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class DataUpdatesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val base = "https://data.example/"
    private val served = HashMap<String, ByteArray>()

    @Before fun clean() {
        DataFiles.folder(context).deleteRecursively()
        context.getSharedPreferences("data_updates", Context.MODE_PRIVATE).edit().clear().commit()
        served.clear()
    }

    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        val body = served[request.url.toString().removePrefix(base)]
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(if (body == null) 404 else 200).message("canned")
            .body((body ?: ByteArray(0)).toResponseBody("application/octet-stream".toMediaType())).build()
    }).build()

    private fun publish(name: String, json: String, schema: Int, checksum: String = sha(json.toByteArray())) {
        served["$name.gz"] = gzip(json.toByteArray())
        val manifest = JSONObject(served["manifest.json"]?.toString(Charsets.UTF_8) ?: """{"files":{}}""")
        manifest.getJSONObject("files").put(name, JSONObject()
            .put("asset", "$name.gz").put("sha256", checksum).put("schema", schema))
        served["manifest.json"] = manifest.toString().toByteArray()
    }

    private fun updates(validate: (Context, String) -> Unit = { _, json -> JSONObject(json) }) =
        DataUpdates(context, client, base, listOf(DataFile("transit_index.json", 6..6, validate)))

    @Test fun aNewerPublishedFileIsDownloadedAndKept() = runBlocking {
        publish("transit_index.json", """{"version":6,"patterns":["new"]}""", 6)
        assertEquals(listOf("transit_index.json"), updates().update())
        assertTrue(File(DataFiles.folder(context), "transit_index.json").readText().contains("new"))
        // The same file again is not downloaded twice.
        assertEquals(emptyList<String>(), updates().update())
    }

    @Test fun aFileThatDoesNotMatchItsChecksumIsRejected() = runBlocking {
        publish("transit_index.json", """{"version":6,"patterns":["new"]}""", 6, checksum = "0".repeat(64))
        assertEquals(emptyList<String>(), updates().update())
        assertFalse(File(DataFiles.folder(context), "transit_index.json").exists())
    }

    @Test fun aFileThatCannotBeParsedIsRejected() = runBlocking {
        publish("transit_index.json", """{"version":6,"patterns":["new"]}""", 6)
        val failing = updates { _, _ -> throw IllegalArgumentException("unusable") }
        assertEquals(emptyList<String>(), failing.update())
        assertFalse(File(DataFiles.folder(context), "transit_index.json").exists())
    }

    @Test fun aLayoutThisAppDoesNotReadIsNotDownloaded() = runBlocking {
        publish("transit_index.json", """{"version":7,"patterns":["new"]}""", 7)
        assertEquals(emptyList<String>(), updates().update())
    }

    @Test fun aBundledFileEqualToThePublishedOneIsNotDownloaded() = runBlocking {
        val bundled = context.assets.open("gtt_metro_index.json").use { it.readBytes() }
        served["gtt_metro_index.json.gz"] = gzip(bundled)
        served["manifest.json"] = """{"files":{"gtt_metro_index.json":{"asset":"gtt_metro_index.json.gz",
            "sha256":"${sha(bundled)}","schema":2}}}""".toByteArray()
        val updates = DataUpdates(context, client, base, listOf(DataFile("gtt_metro_index.json", 1..2) { _, _ -> }))
        assertEquals(emptyList<String>(), updates.update())
    }

    @Test fun aDamagedDownloadFallsBackToTheBundledTimetable() {
        val folder = DataFiles.folder(context).also { it.mkdirs() }
        File(folder, "gtt_metro_index.json").writeText("not json")
        val trips = DataFiles.load(context, "gtt_metro_index.json") { TrainIndex.parse(it) }
        assertTrue(trips.isNotEmpty())
        assertFalse("the damaged copy is dropped", File(folder, "gtt_metro_index.json").exists())
    }

    @Test fun aGoodDownloadIsReadInPlaceOfTheBundledOne() {
        val folder = DataFiles.folder(context).also { it.mkdirs() }
        File(folder, "x.json").writeText("downloaded")
        assertEquals("downloaded", DataFiles.load(context, "x.json") { it })
    }

    @Test fun anAppUpdateDropsTimetablesDownloadedBeforeIt() {
        DataUpdates.dropIfAppChanged(context, 1)
        val folder = DataFiles.folder(context).also { it.mkdirs() }
        File(folder, "transit_index.json").writeText("old")
        DataUpdates.dropIfAppChanged(context, 1)
        assertTrue(File(folder, "transit_index.json").exists())
        DataUpdates.dropIfAppChanged(context, 2)
        assertFalse(File(folder, "transit_index.json").exists())
    }
}
