package inga.bpmetrics.export

import com.google.gson.Gson
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.EventGroupEntity
import inga.bpmetrics.library.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a backup still carries everything the library holds.
 *
 * This exists because it didn't, twice. Columns were added to events, collections and people over
 * three sprints of schema work and nobody thought about the exporter, so a backup taken at format 3
 * restored every recording, every data point, every record tag — and lost the tags on events and
 * collections, the nesting between collections, every cover image, every person's photograph and
 * their heart rate figures.
 *
 * That is a worse failure than a crash. A restore that fails is obvious and recoverable; a restore
 * that returns all the *data* and none of the *organisation* looks like it worked, and is only
 * discovered later by the person who has to redo a year of filing by hand.
 *
 * So the check is not "does a round trip preserve the fields the DTO has" — that would have passed
 * happily the whole time it was broken. It is **every persisted property of the entity is accounted
 * for**, either carried or deliberately excluded with a stated reason. Adding a column and not
 * touching the exporter now fails here, at the moment it is introduced, rather than silently on
 * somebody's restore.
 */
class BackupCoverageTest {

    /**
     * Properties a backup deliberately does not carry, and why.
     *
     * Everything here is an id or a path — a number the destination reassigns, or a file name
     * meaningful only inside the install that wrote it. Adding to this list is a decision that a
     * field does not need to survive a restore, so each entry says why.
     */
    private val excused: Map<Class<*>, Map<String, String>> = mapOf(
        EventEntity::class.java to mapOf(
            "eventId" to "Reassigned on insert; the backup keys events by name.",
            "groupId" to "Carried as groupName; ids are reassigned.",
            "parentId" to "Carried as parentName.",
            "coverPath" to "A name inside this install's storage. The image travels as bytes.",
            "coverCropLeft" to "Carried inside cover.",
            "coverCropTop" to "Carried inside cover.",
            "coverCropRight" to "Carried inside cover.",
            "coverCropBottom" to "Carried inside cover.",
            "coverBlur" to "Carried inside cover."
        ),
        EventGroupEntity::class.java to mapOf(
            "groupId" to "Reassigned on insert; the backup keys collections by name.",
            "parentGroupId" to "Carried as parentName.",
            "coverPath" to "A name inside this install's storage. The image travels as bytes.",
            "coverCropLeft" to "Carried inside cover.",
            "coverCropTop" to "Carried inside cover.",
            "coverCropRight" to "Carried inside cover.",
            "coverCropBottom" to "Carried inside cover.",
            "coverBlur" to "Carried inside cover."
        ),
        PersonEntity::class.java to mapOf(
            "personId" to "Reassigned on insert; the backup keys people by name.",
            "photoPath" to "A name inside this install's storage. The image travels as bytes.",
            "photoCropLeft" to "Carried inside photoCrop.",
            "photoCropTop" to "Carried inside photoCrop.",
            "photoCropRight" to "Carried inside photoCrop.",
            "photoCropBottom" to "Carried inside photoCrop."
        )
    )

    /**
     * The persisted fields of a data class.
     *
     * Java reflection rather than `kotlin-reflect`, which is not on the unit test classpath and is
     * not worth adding for one test. Synthetic and static members are dropped: the compiler adds
     * `$stable` and `Companion`, and neither is anything a backup could carry.
     */
    private fun fieldsOf(type: Class<*>): List<String> =
        type.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }

    private fun assertCovered(entity: Class<*>, dto: Class<*>) {
        val carried = fieldsOf(dto).toSet()
        val excuses = excused[entity].orEmpty()

        val missing = fieldsOf(entity).filter { it !in carried && it !in excuses }

        assertTrue(
            "${entity.simpleName}.$missing is persisted but no backup carries it.\n" +
                "Either add it to ${dto.simpleName} and to the restore, or add it to `excused` " +
                "with the reason it does not need to survive a restore.",
            missing.isEmpty()
        )
    }

    @Test
    fun `an event's every column reaches the backup`() {
        assertCovered(EventEntity::class.java, EventDto::class.java)
    }

    @Test
    fun `a collection's every column reaches the backup`() {
        assertCovered(EventGroupEntity::class.java, EventGroupDto::class.java)
    }

    @Test
    fun `a person's every column reaches the backup`() {
        assertCovered(PersonEntity::class.java, PersonDto::class.java)
    }

    @Test
    fun `an excuse names a property that exists`() {
        // Otherwise a column renamed out from under an excuse leaves the excuse covering nothing,
        // and the new name is missing with nothing to catch it.
        excused.forEach { (entity, excuses) ->
            val real = fieldsOf(entity).toSet()
            val stale = excuses.keys.filter { it !in real }
            assertTrue("${entity.simpleName} has no property $stale, but it is excused.", stale.isEmpty())
        }
    }

    // --- The file itself ---

    @Test
    fun `everything survives being written and read back`() {
        val gson = Gson()
        val backup = LibraryBackup(
            exportedAt = 1_700_000_000_000L,
            people = listOf(
                PersonDto(
                    name = "Kyle",
                    colorArgb = -0x10000,
                    restingBpm = 52,
                    maxBpm = 191,
                    photoBase64 = "aGVsbG8=",
                    photoCrop = CoverDto(cropLeft = 0.1f, cropRight = 0.9f)
                )
            ),
            eventGroups = listOf(
                EventGroupDto(
                    name = "Day 1",
                    parentName = "Griztronics",
                    tags = listOf("Venue:Red Rocks"),
                    cover = CoverDto(imageBase64 = "aGVsbG8=", blur = 0.4f)
                )
            ),
            events = listOf(
                EventDto(
                    name = "Subtronics",
                    parentName = "Day 1",
                    type = "Set",
                    windowStart = 1_700_000_000_000L,
                    windowEnd = 1_700_003_600_000L,
                    excludedFromParentAnalysis = true,
                    tags = listOf("Character:Hulk"),
                    cover = CoverDto(imageBase64 = "aGVsbG8=", cropTop = 0.25f)
                )
            )
        )

        val read = gson.fromJson(gson.toJson(backup), LibraryBackup::class.java)

        assertEquals(backup, read)
        assertEquals(4, read.formatVersion)
    }

    @Test
    fun `a format 3 file still reads, with the new fields empty`() {
        // Old backups must not become unreadable. Everything added in 4 is optional for this reason.
        val old = """
            {"formatVersion":3,"events":[{"name":"Subtronics","groupName":"Day 1"}],
             "eventGroups":[{"name":"Day 1"}],
             "people":[{"name":"Kyle","colorArgb":-65536}]}
        """.trimIndent()

        val read = Gson().fromJson(old, LibraryBackup::class.java)

        assertEquals("Subtronics", read.events.single().name)
        assertEquals("Day 1", read.events.single().groupName)
        assertTrue(read.events.single().tags.isEmpty())
        assertEquals(null, read.events.single().cover)
        assertEquals(null, read.people.single().restingBpm)
    }

    @Test
    fun `a tag is written with the axis it belongs to`() {
        // "Hulk" alone is meaningless on restore — it has to know it is a Character. Writing the
        // category *id* here, which the importer read as a name, is how tags stopped surviving once
        // already.
        val tag = inga.bpmetrics.library.TagEntity(tagId = 1, name = "Hulk", parentCategoryId = 7)

        assertEquals("Character:Hulk", tag.qualified(mapOf(7L to "Character")))
    }

    @Test
    fun `a tag whose category has gone still names something`() {
        val tag = inga.bpmetrics.library.TagEntity(tagId = 1, name = "Hulk", parentCategoryId = 7)

        assertEquals("Uncategorized:Hulk", tag.qualified(emptyMap()))
    }
}
