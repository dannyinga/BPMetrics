package inga.bpmetrics.ui.feedback

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.LocationEntity
import inga.bpmetrics.ui.locations.LocationsViewModel
import inga.bpmetrics.util.GUARDED_FAILURE
import inga.bpmetrics.util.launchGuarded
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The feedback channel, and the two promises S2 makes with it: a failure the user caused is
 * reported rather than swallowed, and a delete can be taken back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackTest {

    private val repository = mockk<LibraryRepository>(relaxed = true)

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // launchGuarded logs what it catches, and android.util.Log isn't available on the JVM.
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { repository.getAllLocations() } returns flowOf(emptyList())
        // Feedback is app-wide, so anything a previous test left in it has to go first.
        while (next() != null) Unit
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    private suspend fun next(): FeedbackMessage? =
        withTimeoutOrNull(50) { Feedback.messages.first() }

    @Test
    fun `each verb posts the right kind of message`() = runTest {
        Feedback.say("Saved.")
        Feedback.failed("Couldn't save.")
        Feedback.undoable("Deleted.") {}
        Feedback.created("Made one.") {}

        val said = next()!!
        assertEquals(FeedbackMessage.Kind.INFO, said.kind)
        assertNull(said.actionLabel)

        assertEquals(FeedbackMessage.Kind.FAILURE, next()!!.kind)
        assertEquals("Undo", next()!!.actionLabel)
        assertEquals("Open", next()!!.actionLabel)
        assertNull(next())
    }

    @Test
    fun `a failed rename says so`() = runTest {
        coEvery { repository.renameLocation(any(), any()) } throws IllegalStateException("disk full")

        LocationsViewModel(repository).rename(locationId = 4, name = "The Gorge")

        val message = next()
        assertNotNull("A failure that says nothing looks like success", message)
        assertEquals(FeedbackMessage.Kind.FAILURE, message!!.kind)
        assertEquals("Couldn't rename that location.", message.text)
    }

    @Test
    fun `launchGuarded reports with the default text, or not at all when asked`() = runTest {
        val viewModel = object : ViewModel() {}

        viewModel.launchGuarded { error("boom") }
        assertEquals(GUARDED_FAILURE, next()?.text)

        viewModel.launchGuarded(failure = null) { error("background refresh") }
        assertNull(next())
    }

    @Test
    fun `deleting a location offers an Undo that puts it back`() = runTest {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val deleted = LibraryRepository.DeletedLocation(
            location = LocationEntity(locationId = 7, name = "The Gorge"),
            photo = null,
            eventIds = listOf(1, 2),
            recordIds = listOf(10, 11, 12)
        )
        coEvery { repository.deleteLocation(any(), 7) } returns deleted

        LocationsViewModel(repository).delete(context, 7)

        val message = next()!!
        assertEquals("Undo", message.actionLabel)
        message.action!!.invoke()
        coVerify { repository.restoreLocation(context, deleted) }
    }
}
