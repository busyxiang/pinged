package my.pinged.ledger.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.ButtonLabel
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.ReceiptSheet
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.TornEdge
import my.pinged.ui.theme.dottedRule

/** The artboard's one button, which hands the user to Android's own screen. */
const val ALLOW_ACCESS: String = "Allow notification access"

/** The artboard's link, which opens [StoredSheet]. */
const val WHAT_IS_STORED: String = "What exactly gets stored?"

/** The artboard's headline, the line a test finds the screen by. */
const val ONBOARDING_HEADLINE: String = "Your bank already tells you every time you spend."

/**
 * First run, `design/Onboarding.dc.html` (#156): what Pinged is, the three
 * claims it makes, and the one thing it needs -- notification access.
 *
 * **Two steps, not the artboard's three.** The board's step 2 is spec 10.4's
 * battery screen, which is its own milestone and not built, so this says
 * `STEP 1 OF 2` and the next step is capture sources. A counter that skips a
 * number reads as a screen the app forgot to show.
 *
 * Small print is [Muted], not the board's `#8a8175` and `#4a423a`: the first
 * is `Faint`, which fails 4.5:1 as text (see `Color.kt`), and the second is
 * not in the palette -- `WipeSheet` made the same substitution.
 *
 * The claims and headline scroll above a fixed action band, so the button is
 * on screen at CI's 640dp as it is at the board's 844; the claims' Card fills
 * whatever height is left, as the board's `flex: 1` does.
 */
@Composable
fun OnboardingScreen(onAllowAccess: () -> Unit, modifier: Modifier = Modifier) {
    // Saved, so the sheet a user is reading survives a rotation.
    var storedOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(Card)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().background(Paper).padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 30.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text("Pinged", fontFamily = Display, fontSize = 25.sp, color = Ink, modifier = Modifier.weight(1f))
                    Text("STEP 1 OF 2", style = MonoLabel, color = Muted)
                }
                Text(
                    ONBOARDING_HEADLINE,
                    fontFamily = Display,
                    fontSize = 33.sp,
                    lineHeight = 39.sp,
                    color = Ink,
                    modifier = Modifier.padding(top = 44.dp),
                )
                Text(
                    "Pinged just writes it down. Nothing to type, nothing to remember at the end of the month.",
                    fontFamily = Body,
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    color = Muted,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            TornEdge()
            Column(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                CLAIMS.forEachIndexed { i, (label, body) ->
                    Claim(label, body, bodyColour = Muted, ruled = i < CLAIMS.lastIndex)
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(Paper)
                .dottedRule(atTop = true)
                .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Stamp)
                    .clickable(role = Role.Button, onClick = onAllowAccess),
                contentAlignment = Alignment.Center,
            ) {
                Text(ALLOW_ACCESS, style = ButtonLabel, color = Paper)
            }
            Text(
                "ANDROID ASKS NEXT" + Separator + "PINGED NEVER SEES A PASSWORD",
                style = MonoLabel,
                color = Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp),
            )
            // 44dp tall as a target, though the board draws a line of text.
            Text(
                WHAT_IS_STORED,
                fontFamily = Body,
                fontSize = 13.5.sp,
                color = Stamp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable(role = Role.Button) { storedOpen = true }
                    .heightIn(min = 44.dp)
                    .wrapContentHeight(),
            )
        }
    }
    if (storedOpen) StoredSheet(onDismiss = { storedOpen = false })
}

/**
 * The board's claims, label then sentence. Every one is a promise the code
 * keeps: the manifest has no `INTERNET` permission (spec 11), and the
 * allow-list discards before any write (spec 9.6).
 *
 * The third is true at first run and not in general. The bind's catch-up
 * (spec 10.1) reads what is still in the shade, but only from enabled apps,
 * and none is enabled until the screen after this one.
 */
private val CLAIMS = listOf(
    "STAYS ON YOUR PHONE" to
        "Pinged has no internet permission at all. It cannot send your spending anywhere, by design rather than by promise.",
    "ONLY THE APPS YOU PICK" to
        "Every other notification is thrown away before anything is written down. You choose the apps on the next screen.",
    "STARTS FROM TODAY" to
        "Android will not show us notifications from before now, so your history begins the moment you switch this on. " +
        "Nothing earlier can be recovered.",
)

/** A Stamp label over its sentence: the board's claims, and [StoredSheet]'s lines. */
@Composable
private fun Claim(label: String, body: String, bodyColour: Color, modifier: Modifier = Modifier, ruled: Boolean = false) {
    Column(modifier.fillMaxWidth().then(if (ruled) Modifier.dottedRule().padding(bottom = 20.dp) else Modifier)) {
        Text(label, style = MonoLabel, color = Stamp)
        Text(
            body,
            fontFamily = Body,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = bodyColour,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/**
 * What the onboarding's link opens. Undrawn on any artboard, so it borrows
 * the claims' own shape -- a Stamp label over a sentence -- inside the app's
 * one sheet chrome.
 *
 * **Each line is spec 4 and 9.6 as built.** Content comes from enabled apps
 * only, `raw_capture`'s text columns and the slot's metadata; other apps
 * leave a count and a first-seen time (spec 9.6); and across all apps, spec
 * 10.2's heartbeat and spec 4's `capture_day`, neither of which names an app.
 * A line here that claimed less than the code stores would break the promise
 * the onboarding's claims rest on.
 *
 * No "never when it last posted" for other apps, though spec 9.6 keeps no
 * last-seen time for a package never enabled: one enabled and then switched
 * off keeps `capture_source.last_notification_at`, so the sentence would be
 * false for exactly the apps a user has changed their mind about.
 */
@Composable
fun StoredSheet(onDismiss: () -> Unit) {
    ReceiptSheet(onDismissRequest = onDismiss, title = "What gets stored") {
        STORED.forEachIndexed { i, (label, body) ->
            Claim(label, body, bodyColour = Ink, modifier = Modifier.padding(top = if (i == 0) 0.dp else 18.dp))
        }
    }
}

private val STORED = listOf(
    "FROM THE APPS YOU PICK" to
        "Every word the notification shows, which app posted it and when, and the details Android files it " +
        "under, such as its channel. Pinged reads the payment out of it, and keeps the words so a better rule " +
        "can read them again later. All of it is encrypted on this phone.",
    "FROM EVERY OTHER APP" to
        "Not one word. Only the app's name, when it first posted, and how many notifications it has posted, " +
        "so Pinged can point out a bank you have not switched on.",
    "FROM ALL APPS TOGETHER" to
        "The time the latest notification of any kind arrived, and for each day whether any arrived at all, " +
        "without which app sent them. That is how Pinged can tell you if Android has stopped it listening, " +
        "and tell a day you spent nothing from a day it was not listening.",
)
