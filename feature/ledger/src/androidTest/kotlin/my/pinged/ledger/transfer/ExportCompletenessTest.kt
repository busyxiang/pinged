package my.pinged.ledger.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import my.pinged.data.PingedDatabase
import my.pinged.data.entity.CaptureDay
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.Category
import my.pinged.data.entity.MerchantAlias
import my.pinged.data.entity.MerchantName
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guard that catches the class of bug, rather than an instance of it.
 *
 * Spec 12 calls the JSON export "full fidelity". The way that stops being true
 * is not a crash: it is a column added to an entity and not added to the
 * export, which produces a complete-looking file and a clean restore that has
 * quietly lost the reason a transaction needed review. Both ends report
 * success. A round-trip assertion does not find it either, because a round
 * trip only exercises the fields somebody remembered to write.
 *
 * So these tests do not check any particular column. They check that the
 * export's description of a table -- `Backup`'s column list -- agrees with
 * three independent sources of truth about what that table is:
 *
 * 1. the Kotlin entity, by reflection over its declared fields;
 * 2. the columns SQLite is actually holding, by `PRAGMA table_info`;
 * 3. the tables the database has at all, by `sqlite_master`.
 *
 * Adding a column to an entity fails (1) and (2) on the next run. Adding a
 * whole table fails (3). That is the same shape as `EnumVocabularyTest`
 * freezing the enum text and `MigrationTest` comparing the harness DDL, and it
 * is here for the same reason: this schema changed under its consumers four
 * times in a day.
 */
@RunWith(AndroidJUnit4::class)
class ExportCompletenessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PingedDatabase

    @Before fun setUp() {
        db = freshDatabase(context)
    }

    @After fun tearDown() = db.close()

    /**
     * The pairing of a section to the entity it carries.
     *
     * Written out by hand, and the test below makes that safe: a seventh
     * entity added to the database with no section fails
     * [everyTableInTheDatabaseHasASection] before it can fail to appear here.
     */
    private val sections: List<Pair<BackupSection<*>, Class<*>>> = listOf(
        CATEGORY_SECTION to Category::class.java,
        CAPTURE_SOURCE_SECTION to CaptureSource::class.java,
        CAPTURE_DAY_SECTION to CaptureDay::class.java,
        MERCHANT_RULE_SECTION to MerchantRule::class.java,
        RAW_CAPTURE_SECTION to RawCapture::class.java,
        TXN_SECTION to Txn::class.java,
        MERCHANT_ALIAS_SECTION to MerchantAlias::class.java,
        MERCHANT_NAME_SECTION to MerchantName::class.java,
    )

    /**
     * **The headline.** Every constructor parameter of every entity is carried
     * by the export.
     *
     * The comparison is between property names on both sides, never between a
     * property name and a guessed snake_case column name, because a guess is
     * the thing that would have to be right for the test to mean anything.
     * `BackupColumn.property` exists for this and nothing else.
     */
    @Test fun everyFieldOfEveryEntityIsInTheExport() {
        sections.forEach { (section, entity) ->
            val declared = entityFieldNames(entity)
            val exported = section.columns.map { it.property }.toSet()
            assertEquals(
                "${entity.simpleName} and the '${section.name}' section of the export " +
                    "disagree about which fields exist. Missing from the export: " +
                    "${(declared - exported).sorted()}. In the export and not on the " +
                    "entity: ${(exported - declared).sorted()}. A column that is on the " +
                    "entity and not in the export is lost by every backup and every " +
                    "restore, with both of them reporting success.",
                declared,
                exported,
            )
        }
    }

    /**
     * And every name the export writes is a real column of the current
     * schema -- read off SQLite, not off the entity, so a typo in a
     * `BackupColumn` name is caught even though the property beside it is
     * right.
     */
    @Test fun everyExportedNameIsAColumnOfItsTable() {
        sections.forEach { (section, _) ->
            val actual = db.columnsOf(section.table)
            val exported = section.columns.map { it.column }.toSet()
            assertEquals(
                "The '${section.name}' section names columns that are not in " +
                    "${section.table}, or misses columns that are. Only in the table: " +
                    "${(actual - exported).sorted()}. Only in the export: " +
                    "${(exported - actual).sorted()}.",
                actual,
                exported,
            )
            assertEquals(
                "The '${section.name}' section names a column twice.",
                section.columns.size,
                exported.size,
            )
        }
    }

    /**
     * A table added to the database with no export section would be lost
     * whole, which is worse than losing a column and is exactly as quiet.
     */
    @Test fun everyTableInTheDatabaseHasASection() {
        assertEquals(
            "A table exists that the export does not carry, or the export names a " +
                "table that does not exist.",
            db.userTables(),
            ALL_SECTIONS.map { it.table }.toSet(),
        )
        assertEquals(
            "ALL_SECTIONS and this test's own list have drifted apart.",
            ALL_SECTIONS.size,
            sections.size,
        )
        // The SECTION_ORDER/ALL_SECTIONS drift assertion that used to sit here
        // is gone: SECTION_ORDER is now `ALL_SECTIONS.map { it.name }`, so the
        // comparison could only ever pass. The two lists it policed are one
        // list now.

    }

    /**
     * And the document really carries them: the same comparison again against
     * a row that has been through `JsonWriter`, in case a column is in the
     * list and its writer does nothing.
     */
    @Test fun anExportedRowCarriesEveryColumnItsSectionNames() {
        seedOneOfEverything(db)
        val bytes = exportBytes(db)
        val document = JSONObject(String(bytes, Charsets.UTF_8))

        assertEquals(Backup.FORMAT_VERSION, document.getInt(Backup.FIELD_FORMAT))
        ALL_SECTIONS.forEach { section ->
            val rows = document.getJSONArray(section.name)
            assertTrue(
                "The fixture left '${section.name}' empty, so this assertion would " +
                    "prove nothing about it.",
                rows.length() > 0,
            )
            val row = rows.getJSONObject(0)
            val keys = mutableSetOf<String>()
            row.keys().forEach { keys += it }
            assertEquals(
                "A '${section.name}' row in the written document does not carry the " +
                    "columns its section names.",
                section.columns.map { it.column }.toSet(),
                keys,
            )
        }
    }

    /**
     * **Named for what it proves, which is less than it used to claim.**
     *
     * It was called `theFormatVersionIsNotTheSchemaVersion`, and its own doc
     * said the two being 1 today "is precisely why something has to say they
     * are two different numbers before one of them moves". It cannot say that.
     * Spec 6.4 moved both to 2 together, so the two assertions still compare
     * against one value, and an export that wrote `FORMAT_VERSION` into the
     * schema slot, or the reverse, still passes.
     *
     * What it does prove: both fields are present, `format` is first in the
     * document, and the schema field carries the version of the database the
     * export was actually taken from. The discriminating assertion arrives for
     * free the day the two numbers part, and this test starts failing then --
     * which is the right moment for it to.
     */
    @Test fun theExportStampsBothVersions() {
        val bytes = exportBytes(db)
        val document = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(Backup.FORMAT_VERSION, document.getInt(Backup.FIELD_FORMAT))
        assertEquals(
            "The export should record the schema version it was taken from, as " +
                "diagnostics and never as a gate.",
            db.openHelper.readableDatabase.version,
            document.getInt(Backup.FIELD_SCHEMA_VERSION),
        )
        assertTrue(
            "'${Backup.FIELD_FORMAT}' has to be the first name in the document: the " +
                "reader gates on it before it believes a single row.",
            String(bytes, Charsets.UTF_8).startsWith("{\"${Backup.FIELD_FORMAT}\":"),
        )
    }
}
