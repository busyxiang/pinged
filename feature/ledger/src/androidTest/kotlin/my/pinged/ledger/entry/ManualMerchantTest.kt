package my.pinged.ledger.entry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.capture.Graph
import my.pinged.capture.MerchantColumns
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.dao.MerchantTotal
import my.pinged.data.entity.Txn
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.home.MerchantOpening
import my.pinged.ledger.home.RowActions
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.transfer.ImportJson
import my.pinged.ledger.transfer.Restore
import my.pinged.ledger.transfer.SalvageJson
import my.pinged.ledger.transfer.exportBytes
import my.pinged.ledger.transfer.freshDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A manual entry's merchant and note (#101), against the real encrypted
 * database: the typed merchant becomes the same three columns a capture's
 * would, so it is the same merchant identity as a captured row of that key,
 * and both fields survive a backup.
 *
 * Driven through [ManualEntries], the holder the entry screen's view model
 * owns, and the merchant sheet through [RowActions], the holder every row's
 * long-press reaches.
 */
@RunWith(AndroidJUnit4::class)
class ManualMerchantTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun leaveNothingBehind() {
        scope.cancel()
        context.discardTheDatabase()
    }

    private fun entries() = ManualEntries(context = { context }, scope = scope, clock = { NOON })

    private suspend fun saved(merchant: String = "", note: String = ""): Txn {
        val id = entries().save(ManualDraft(amountSen = 1_250L, merchant = merchant, note = note)).await()
        return Databases.txnDao(context).byId(id!!)!!
    }

    private val Txn.merchant get() = Triple(merchantRaw, merchantDisplay, merchantKey)

    @Test fun aTypedMerchantFillsTheColumnsAsACaptureWould() = runBlocking {
        context.freshLedger()
        // What stage two writes for the same string: `ParsePass` derives the
        // columns through `MerchantColumns`, under `Graph.ruleMatcher()`'s
        // normalization.
        val normalization = Graph.ruleMatcher().merchantNormalization
        for (typed in listOf("Kopitiam", "TNG*KK SUPERMART SDN BHD", "foodpanda KLCC", "  Warung  Pak Ali ")) {
            val capture = MerchantColumns.of(typed, normalization)
            assertEquals(
                "'$typed' was not given the merchant columns a capture of it gets",
                Triple(capture.raw, capture.display, capture.key),
                saved(merchant = typed).merchant,
            )
        }
        // Spelled out once, so the comparison above cannot pass by both sides
        // skipping the pack: its prefix and suffix are gone from the display
        // and the key, its exception keeps `KK`, and the raw is as typed.
        assertEquals(
            Triple("TNG*KK SUPERMART SDN BHD", "KK Supermart", "KK SUPERMART"),
            saved(merchant = "TNG*KK SUPERMART SDN BHD").merchant,
        )
    }

    @Test fun aBlankOrNoiseOnlyMerchantLeavesAllThreeColumnsNull() = runBlocking {
        context.freshLedger()
        for (typed in listOf("", "   ", "TNG*", "DUITNOWQR-")) {
            assertEquals(
                "'$typed' names no merchant, yet was stored as one",
                Triple(null, null, null),
                saved(merchant = typed).merchant,
            )
        }
    }

    @Test fun theNoteIsStoredTrimmedAndABlankOneIsNull() = runBlocking {
        context.freshLedger()
        assertEquals("parking at the pasar", saved(note = "  parking at the pasar ").note)
        assertEquals("A blank note was stored", null, saved(note = "   ").note)
    }

    @Test fun aTypedAndACapturedMerchantOfOneKeyAreOneMerchantInTopMerchants() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(captured(raw = "KOPITIAM", display = "Kopitiam", key = "KOPITIAM"))

        saved(merchant = "Kopitiam")

        assertEquals(
            "A typed Kopitiam and a captured KOPITIAM did not rank as one shop paid twice",
            listOf("KOPITIAM" to 2),
            topMerchants().map { it.identityKey to it.txnCount },
        )
    }

    @Test fun theSheetMergesATypedMerchantIntoACapturedOneAndSeparatesThem() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(captured(raw = "TNG*99 SPEEDMART", display = "99 Speedmart", key = "99 SPEEDMART"))
        val typed = saved(merchant = "Speedmart 99").merchantKey
            ?: throw AssertionError("The typed merchant has no key for a sheet to open on")
        val rows = RowActions(context = { context }, scope = scope, unavailable = {})

        open(rows, typed)
        rows.mergeMerchant("99 SPEEDMART").join()
        assertEquals(
            "The merge did not join the typed merchant to the captured one",
            listOf("99 SPEEDMART" to 2),
            topMerchants().map { it.identityKey to it.txnCount },
        )

        open(rows, typed)
        rows.separateMerchant(typed).join()
        assertEquals(
            "Separating did not split the typed merchant out again",
            setOf("99 SPEEDMART" to 1, typed to 1),
            topMerchants().map { it.identityKey to it.txnCount }.toSet(),
        )
    }

    @Test fun anExportAndAnImportKeepTheMerchantAndTheNote() = runBlocking {
        context.freshLedger()
        val entry = saved(merchant = "Kopitiam", note = "teh tarik")
        assertCarriesBoth(entry)

        Databases.reset()
        val bytes = DatabaseFactory.build(context).let { db -> try { exportBytes(db) } finally { db.close() } }
        val restored = freshDatabase(context)
        try {
            ImportJson.read(restored, ByteArrayInputStream(bytes))
            assertEquals(
                "The manual entry did not survive the round trip whole",
                entry,
                restored.txnDao().byId(entry.id),
            )
        } finally {
            restored.close()
        }
    }

    @Test fun aSalvageKeepsTheMerchantAndTheNote() = runBlocking {
        context.freshLedger()
        val entry = saved(merchant = "Kopitiam", note = "teh tarik")
        assertCarriesBoth(entry)

        Databases.reset()
        val out = ByteArrayOutputStream()
        SalvageJson.write(context, out)
        Restore.replaceEverything(context, ByteArrayInputStream(out.toByteArray()))

        assertEquals(
            "The salvage did not keep the manual entry whole",
            entry,
            Databases.txnDao(context).byId(entry.id),
        )
    }

    /** So the comparison after a backup cannot pass on an entry with neither field. */
    private fun assertCarriesBoth(entry: Txn) =
        assertEquals("KOPITIAM" to "teh tarik", entry.merchantKey to entry.note)

    /** A captured row on [NOON]'s day an hour earlier, with the merchant a parse would have written. */
    private fun captured(raw: String, display: String, key: String) =
        ledgerTxn(amountSen = 900L, occurredAt = NOON - HOUR)
            .copy(merchantRaw = raw, merchantDisplay = display, merchantKey = key)

    private fun topMerchants(): List<MerchantTotal> {
        val month = LocalDates.monthRange(YearMonth.of(2026, 8))
        return Databases.txnDao(context).merchantTotals(month.start, month.endInclusive, limit = 10)
    }

    /** The sheet open on [key], as a long-press on its row leaves it. */
    private suspend fun open(rows: RowActions, key: String) {
        rows.openMerchant(MerchantOpening(key, key, LocalDates.of(NOON))).join()
        withTimeout(TIMEOUT) { rows.merchantSheet.first { it?.read != null } }
    }

    private companion object {
        val NOON: Long = java.time.LocalDate.of(2026, 8, 15).atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        const val HOUR = 3_600_000L
    }
}
