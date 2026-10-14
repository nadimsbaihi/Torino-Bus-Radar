package it.mato.livebus

import android.os.Looper
import android.view.View
import it.mato.livebus.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Duration

/** The journey sheet's refresh button: it replans only when there is a destination to plan to. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = MatoApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class JourneyRefreshTest {
    private fun screen(): Pair<MainActivity, ActivityMainBinding> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val binding = MainActivity::class.java.getDeclaredField("binding")
            .apply { isAccessible = true }.get(activity) as ActivityMainBinding
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        return activity to binding
    }

    private fun planningJob(activity: MainActivity) = MainActivity::class.java
        .getDeclaredField("planningJob").apply { isAccessible = true }.get(activity) as Job?

    // One screen for all of it: every activity loads the whole transit index, and the test heap is small.
    @Test fun refreshPlansAgainOnlyWhenThereIsADestination() {
        val (activity, binding) = screen()
        assertEquals(View.VISIBLE, binding.refreshJourneyButton.visibility)
        assertNotNull(binding.refreshJourneyButton.contentDescription)
        binding.refreshJourneyButton.performClick()
        assertNull(planningJob(activity))
        binding.destinationInput.setText("Porta Nuova", false)
        binding.refreshJourneyButton.performClick()
        assertNotNull(planningJob(activity))
    }
}
