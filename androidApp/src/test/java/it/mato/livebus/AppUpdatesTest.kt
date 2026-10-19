package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
class AppUpdatesTest {
    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, assets: String = """
        [{"name": "notes.txt", "browser_download_url": "https://example.org/notes.txt"},
         {"name": "Torino-Bus-Radar-$tag.apk", "digest": "sha256:abc123",
          "browser_download_url": "https://example.org/Torino-Bus-Radar-$tag.apk"}]""") =
        """{"tag_name": "$tag", "draft": $draft, "prerelease": $prerelease, "assets": $assets}"""

    @Test fun theLatestReleaseGivesItsVersionApkAndChecksum() {
        assertEquals(AppRelease("0.4.0", "https://example.org/Torino-Bus-Radar-v0.4.0.apk", "abc123"),
            AppUpdates.parseLatest(release("v0.4.0")))
    }

    @Test fun aReleaseNotReadyOrWithoutAnApkIsNotOffered() {
        assertNull(AppUpdates.parseLatest(release("v0.4.0", draft = true)))
        assertNull(AppUpdates.parseLatest(release("v0.4.0", prerelease = true)))
        assertNull(AppUpdates.parseLatest(release("v0.4.0", assets = "[]")))
    }

    @Test fun versionsCompareNumberByNumber() {
        assertTrue(AppUpdates.isNewer("0.4.0", "0.3.1"))
        assertTrue(AppUpdates.isNewer("0.10.0", "0.9.3"))
        assertTrue(AppUpdates.isNewer("1.0", "0.99.99"))
        assertTrue(AppUpdates.isNewer("0.3.1.1", "0.3.1"))
        assertFalse(AppUpdates.isNewer("0.3.1", "0.3.1"))
        assertFalse(AppUpdates.isNewer("0.3.0", "0.3.1"))
        assertFalse(AppUpdates.isNewer("0.3", "0.3.0"))
    }

    @Test fun itLooksAgainOnlyAfterSomeHours() {
        val updates = AppUpdates(RuntimeEnvironment.getApplication())
        assertTrue(updates.dueForCheck())
        RuntimeEnvironment.getApplication().getSharedPreferences("app_updates", 0).edit()
            .putLong("last_check", 1_000_000L).apply()
        assertFalse(updates.dueForCheck(1_000_000L + 60 * 60 * 1000L))
        assertTrue(updates.dueForCheck(1_000_000L + 13 * 60 * 60 * 1000L))
    }
}
