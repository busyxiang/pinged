package my.pinged.ledger

import androidx.compose.ui.test.assert
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import my.pinged.ledger.sources.SourcesScreenContent
import my.pinged.ledger.sources.SourcesState
import my.pinged.ledger.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ledger.theme.PingedTheme
import my.pinged.ledger.theme.Stamp
import my.pinged.ledger.sources.SourceRow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import my.pinged.ledger.theme.Separator

/**
 * Source files here are ASCII, so the interpunct between a row's count and its
 * claim is read from [Separator] -- the character the artboard sets with
 * `&middot;` -- rather than typed into an expectation.
 */
class SourcesScreenTest {
    @get:Rule val compose = createComposeRule()

    /**
     * What a screen reader is handed for one row.
     *
     * The row is the switch -- `toggleable` on the whole `Row`, so the target is
     * the full 44dp and not the 42x24 pill -- and `toggleable` merges its
     * descendants in, so the accessible name is the row's own text. There is no
     * `contentDescription` anywhere on this screen and there should not be: every
     * element that means something has text, and a description bolted onto a
     * labelled node is a second name that can disagree with the first.
     *
     * Asserted rather than assumed: a name, a role and a state all have to hold
     * together, and each is separately easy to lose -- a `Box` around the content
     * breaks the merge and leaves the switch unnamed, and dropping `role` leaves a
     * control that announces as a button and never says on or off.
     */
    @Test fun eachRowIsANamedSwitchThatSaysWhetherItIsOn() {
        compose.setContent {
            PingedTheme {
                SourcesScreenContent(
                    state = SourcesState(
                        suggested = listOf(
                            SourceRow("my.com.tngdigital.ewallet", "Touch 'n Go eWallet", 412, true),
                            SourceRow("com.maybank2u.life", "Maybank MAE", 208, false),
                        ),
                        loaded = true,
                    ),
                    onToggle = { _, _ -> },
                    onBack = {},
                )
            }
        }

        val isASwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

        // Found *by its name*, which is the assertion: the node a reader
        // reaches is the one carrying the app's label.
        compose.onNodeWithText("Touch 'n Go eWallet").assert(isASwitch).assertIsOn()
        compose.onNodeWithText("Maybank MAE").assert(isASwitch).assertIsOff()
        // The count is part of the same node, not a separate unlabelled one.
        compose.onNodeWithText("412 SEEN").assert(isASwitch)
    }

    /**
     * The state where the app cannot open its own database does not get the
     * typography of an empty list.
     *
     * [CANNOT_READ_YOUR_DATA] and "NOTHING ELSE HAS POSTED YET" were the same size,
     * colour and position. Drawn identically, a user scanning an apparently empty
     * allow-list concludes nothing has ever notified them.
     *
     * The alert is what the accent and the frame are for, so this reads the pixels:
     * asserting the words proves nothing -- they were already right, and the first
     * version of this test passed with the fix reverted.
     */
    @Test fun theUnreadableStateIsDrawnAsAnAlertAndNotAsAnEmptyList() {
        compose.setContent {
            PingedTheme {
                SourcesScreenContent(
                    state = SourcesState(loaded = true, storageUnavailable = true),
                    onToggle = { _, _ -> },
                    onBack = {},
                )
            }
        }

        compose.onAllNodesWithText(CANNOT_READ_YOUR_DATA).assertCountEquals(2)
        // The two empty-section notes must not appear beside it: this state is
        // not "nothing was found", it is "nothing could be read".
        compose.onAllNodesWithText("NOTHING ELSE HAS POSTED YET").assertCountEquals(0)
        compose.onAllNodesWithText("NO KNOWN SOURCE IS INSTALLED ON THIS DEVICE").assertCountEquals(0)

        val drawn = compose.onAllNodesWithText(CANNOT_READ_YOUR_DATA)[0]
            .captureToImage()
            .toPixelMap()
        var accent = 0
        for (y in 0 until drawn.height) {
            for (x in 0 until drawn.width) {
                if (drawn[x, y] == Stamp) accent++
            }
        }
        assertTrue(
            "Nothing in this notice is drawn in the accent, so it is typeset " +
                "exactly like an empty section and reads as one",
            accent > 0,
        )
    }

    @Test fun showsSeenCountsAndTogglesASource() {
        var toggled: Pair<String, Boolean>? = null
        compose.setContent {
            PingedTheme {
                SourcesScreenContent(
                    state = SourcesState(
                        suggested = listOf(
                            SourceRow("my.com.tngdigital.ewallet", "Touch 'n Go eWallet", 412, true),
                            SourceRow("com.maybank2u.life", "Maybank MAE", 208, false),
                        ),
                        seenNotCaptured = listOf(
                            SourceRow("com.whatsapp", "WhatsApp", 3204, false),
                        ),
                        loaded = true,
                    ),
                    onToggle = { pkg, on -> toggled = pkg to on },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Touch 'n Go eWallet").assertIsDisplayed()
        compose.onNodeWithText("412 SEEN").assertIsDisplayed()
        // The row that makes the privacy model concrete (spec 9.6).
        compose.onNodeWithText("3,204 SEEN" + Separator + "NOT ONE WORD STORED").assertIsDisplayed()

        compose.onNodeWithText("Maybank MAE").performClick()
        assertTrue(toggled == "com.maybank2u.life" to true)
    }

    /**
     * Being in the lower section is not evidence that nothing was stored.
     *
     * Disabling a source stops the next capture and deletes nothing, so a
     * source the user enabled, let run, and switched off again lands back here
     * with its full notification text still in `raw_capture`. Drawn from the
     * row's `enabled` flag alone the claim is a statement about what happens
     * next, not about what is on disk.
     */
    @Test fun theNotStoredClaimIsNotPrintedForADisabledSourceWhoseTextWasKept() {
        compose.setContent {
            PingedTheme {
                SourcesScreenContent(
                    state = SourcesState(
                        seenNotCaptured = listOf(
                            SourceRow("com.example.was", "Former Bank", 41, false, textStored = true),
                            SourceRow("com.whatsapp", "WhatsApp", 3204, false),
                        ),
                        loaded = true,
                    ),
                    onToggle = { _, _ -> },
                    onBack = {},
                )
            }
        }

        compose.onAllNodesWithText("41 SEEN" + Separator + "NOT ONE WORD STORED").assertCountEquals(0)
        compose.onNodeWithText("41 SEEN" + Separator + "EARLIER TEXT STILL SAVED").assertIsDisplayed()
        // The source that really has nothing stored still says so.
        compose.onNodeWithText("3,204 SEEN" + Separator + "NOT ONE WORD STORED").assertIsDisplayed()
    }

    /**
     * The claim is per row, not per section.
     *
     * "NOT ONE WORD STORED" is printed under every row of the lower list. This
     * component is public, stateless, and cannot see the allow-list, so if the
     * claim were a property of the section then one caller putting an enabled
     * source in that list would make the app lie about a source whose text is
     * in `raw_capture`. The count still shows, because it is true either way.
     */
    @Test fun theNotStoredClaimIsNotPrintedForASourceWhoseTextIsStored() {
        compose.setContent {
            PingedTheme {
                SourcesScreenContent(
                    state = SourcesState(
                        seenNotCaptured = listOf(
                            SourceRow("com.example.enabled", "Enabled Bank", 12, true),
                            SourceRow("com.whatsapp", "WhatsApp", 3204, false),
                        ),
                        loaded = true,
                    ),
                    onToggle = { _, _ -> },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("12 SEEN").assertIsDisplayed()
        compose.onAllNodesWithText("12 SEEN" + Separator + "NOT ONE WORD STORED").assertCountEquals(0)
        compose.onNodeWithText("3,204 SEEN" + Separator + "NOT ONE WORD STORED").assertIsDisplayed()
    }

    /**
     * A disabled bank in the **upper** list carries the claim too.
     *
     * The upper section is the pack's installed packages plus everything enabled,
     * and the pack holds two -- both in `<queries>`, so those two are always up
     * here whether they are on or off. Rendered only in the lower section, a
     * disabled Maybank drew "12 SEEN" and nothing else under a heading reading TEXT
     * IS STORED ONLY FOR THESE.
     */
    @Test fun aDisabledSourceInTheUpperListStillSaysWhatIsStored() {
        compose.setContent {
            PingedTheme {
                SourcesScreenContent(
                    state = SourcesState(
                        suggested = listOf(
                            SourceRow("com.maybank2u.life", "Maybank MAE", 12, false, textStored = true),
                            SourceRow("my.com.tngdigital.ewallet", "Touch 'n Go eWallet", 7, false),
                            SourceRow("com.example.on", "A Bank", 3, true),
                        ),
                        loaded = true,
                    ),
                    onToggle = { _, _ -> },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("12 SEEN" + Separator + "EARLIER TEXT STILL SAVED").assertIsDisplayed()
        compose.onNodeWithText("7 SEEN" + Separator + "NOT ONE WORD STORED").assertIsDisplayed()
        // An enabled row says nothing: the heading above it already does.
        compose.onNodeWithText("3 SEEN").assertIsDisplayed()
    }

    /**
     * Spec 9.6 asks each row for the `is_authoritative` flag of §7.2, which is
     * what the artboard's chip is drawn from.
     */
    @Test fun theAuthoritativeChipIsDrawnOnlyForAnAuthoritativeSource() {
        compose.setContent {
            PingedTheme {
                SourcesScreenContent(
                    state = SourcesState(
                        suggested = listOf(
                            SourceRow(
                                "my.com.tngdigital.ewallet",
                                "Touch 'n Go eWallet",
                                412,
                                true,
                                authoritative = true,
                            ),
                            SourceRow("com.maybank2u.life", "Maybank MAE", 208, true),
                        ),
                        loaded = true,
                    ),
                    onToggle = { _, _ -> },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Touch 'n Go eWallet").assertIsDisplayed()
        compose.onAllNodesWithText("AUTHORITATIVE").assertCountEquals(1)
    }
}
