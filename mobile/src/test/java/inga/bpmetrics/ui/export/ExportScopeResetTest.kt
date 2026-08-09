package inga.bpmetrics.ui.export

import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.ExportPresetEntity
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.ui.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * What must not survive being pointed at something else.
 *
 * The export ViewModel is hoisted above the nav host so an entry point can prime it before
 * navigating, which means it outlives any one export. Anything content-specific left behind lands
 * on the *next* one — and it does so silently, which is how a title typed for one event came to
 * caption every image after it.
 */
class ExportScopeResetTest {

    private val repository = mockk<LibraryRepository>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)

    private val recordsFlow = MutableStateFlow<List<BpmRecord>>(emptyList())
    private val eventsFlow = MutableStateFlow<List<EventEntity>>(emptyList())
    private val groupsFlow = MutableStateFlow<List<EventEntity>>(emptyList())
    private val peopleFlow = MutableStateFlow<List<PersonEntity>>(emptyList())
    private val presetsFlow = MutableStateFlow<List<ExportPresetEntity>>(emptyList())
    private val tagsFlow = MutableStateFlow<Map<Long, List<EffectiveTag>>>(emptyMap())

    @Before
    fun setup() {
        // The whole set, not just the ones this test reads. A relaxed mock returns a Flow that
        // never emits, and `combine` waits on all of its sources — so a single missing stub hangs
        // the test with a timeout that names nothing.
        every { repository.records } returns recordsFlow
        every { repository.getAllEvents() } returns eventsFlow
        every { repository.getAllEventGroups() } returns groupsFlow
        every { repository.getAllPeople() } returns peopleFlow
        every { repository.getExportPresets() } returns presetsFlow
        every { repository.effectiveTags } returns tagsFlow
    }

    private fun viewModel() = ExportUtilityViewModel(repository, settings)

    @Test
    fun `a typed title does not follow the user to the next export`() = runTest {
        val vm = viewModel()
        vm.setSource(ExportSource.Event(7))
        vm.setImageTitle("Subtronics, but louder")

        vm.setSource(ExportSource.Event(8))

        assertNull("the next export must be named after itself", vm.imageTitle.value)
    }

    @Test
    fun `a narrowed clock window does not follow either`() = runTest {
        // Quieter than the title and worse: the next export silently loses whatever falls outside
        // a range that was typed about something else entirely.
        val vm = viewModel()
        vm.setSource(ExportSource.Event(7))
        vm.setImageCrop(ImageCrop(startWallClockMs = 1_000L, endWallClockMs = 2_000L))

        vm.setSource(ExportSource.Group(3))

        assertEquals(ImageCrop(), vm.imageCrop.value)
    }

    @Test
    fun `a chosen backdrop video does not reattach itself to something unrelated`() = runTest {
        val vm = viewModel()
        vm.setSource(ExportSource.Event(7))
        vm.setManualOverlay(mockk(relaxed = true))

        vm.setSource(ExportSource.Recordings(setOf(42)))

        assertNull(vm.manualOverlay.value)
    }

    @Test
    fun `re-entering the same scope keeps what was typed about it`() = runTest {
        // Opening the same event twice is not starting over. Clearing on every call would throw
        // away work whenever an entry point re-primed the ViewModel with what it already had.
        val vm = viewModel()
        vm.setSource(ExportSource.Event(7))
        vm.setImageTitle("Subtronics")

        vm.setSource(ExportSource.Event(7))

        assertEquals("Subtronics", vm.imageTitle.value)
    }

    @Test
    fun `the look survives a change of subject`() = runTest {
        // The opposite failure, and the reason this is a list rather than a blanket reset: a preset
        // is what someone spent ten minutes arranging, and it is meant to outlive the export.
        val vm = viewModel()
        vm.setSource(ExportSource.Event(7))
        vm.setPreset(vm.preset.value.copy(width = 1080, height = 1920, backgroundOpacity = 0))

        vm.setSource(ExportSource.Event(8))

        assertEquals(1080, vm.preset.value.width)
        assertEquals(1920, vm.preset.value.height)
        assertEquals(0, vm.preset.value.backgroundOpacity)
    }
}
