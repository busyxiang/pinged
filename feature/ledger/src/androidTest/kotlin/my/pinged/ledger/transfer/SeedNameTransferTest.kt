package my.pinged.ledger.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import my.pinged.data.PingedDatabase
import my.pinged.data.Seed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `category.seed_name` through a backup (#155, format 3): a renamed seeded
 * category is still the seeded one after a restore, and a file from before
 * format 3 has its seeded rows named as the schema migration names them.
 */
@RunWith(AndroidJUnit4::class)
class SeedNameTransferTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var db: PingedDatabase? = null

    @After fun tearDown() {
        db?.close()
        db = null
    }

    private fun fresh(): PingedDatabase {
        db?.close()
        db = null
        return freshDatabase(context).also { db = it }
    }

    /** Without `seed_name` in the file, "Makan" restores as a category no dictionary entry names. */
    @Test fun aRenamedSeededCategoryIsStillTheSeededOneAfterARestore() {
        val original = fresh()
        val food = original.categoryDao().seededIds().getValue("Food & Drinks")
        original.categoryDao().edit(food, "Makan", "soup")
        val bytes = exportBytes(original)

        val restored = fresh()
        ImportJson.read(restored, ByteArrayInputStream(bytes))

        assertEquals(food, restored.categoryDao().seededIds()["Food & Drinks"])
        assertEquals("Makan", restored.categoryDao().all().single { it.id == food }.name)
    }

    /**
     * A format-2 file carries no `seed_name`; each row is given its name when
     * that is a seeded one, which no build that wrote format 2 could have
     * renamed. Made from a real export by taking the column and the later
     * section out and the format back, so it is shaped exactly as a format-2
     * export is.
     */
    @Test fun aFormatTwoBackupGivesTheSeededRowsTheirSeedNames() {
        val original = fresh()
        val text = String(exportBytes(original), Charsets.UTF_8)
        val formatTwo = text
            .replace(Regex(""","seed_name":(null|"[^"]*")"""), "")
            .replaceFirst("{\"${Backup.FIELD_FORMAT}\":${Backup.FORMAT_VERSION}", "{\"${Backup.FIELD_FORMAT}\":2")
            // Format 4's section, which no format-2 build wrote.
            .let { it.substring(0, it.indexOf(",\"${Backup.USER_REJECT_RULES}\":")) + "}" }
        check("seed_name" !in formatTwo) { "the column is still in the file" }

        val restored = fresh()
        val report = ImportJson.read(restored, ByteArrayInputStream(formatTwo.toByteArray()))

        assertEquals(2, report.formatVersion)
        val seeded = restored.categoryDao().all().associate { it.name to it.seedName }
        assertEquals(Seed.NAMES.associateWith { it }, seeded)
    }
}
