package my.pinged.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The app's toggle: 42 by 24, an 18dp knob, [Stamp] when on. Drawn only --
 * the row it sits in owns the click and the semantics, `toggleable` with
 * `Role.Switch`, so a screen reader hears the label and the state together and
 * the whole row is the target. Material's `Switch` is 52 by 32 in its own
 * colours and is not used (#164).
 *
 * The Sources screen's capture toggle and the chooser's "Always call this".
 *
 * **The off state is drawn in [Faint], and this is the one place the artboard
 * is overruled.** It drew the outline in `Border` on the upper ground and
 * `Rule` on the lower -- 1.35:1 and 1.41:1 -- with the knob `Rule` on both, at
 * 1.60:1 and 1.41:1. WCAG 1.4.11 asks 3:1 of a component and its states, and
 * the Sources screen has exactly one question, so "not this one" being almost
 * invisible is not a stylistic matter. [Faint] clears 3:1 on both grounds.
 *
 * One colour rather than one per ground: a per-ground parameter exists to keep
 * the outline equally faint against two backgrounds, which is the property
 * that was the bug.
 *
 * The two states are **not** told apart by colour. On is a filled pill with the
 * knob right, off an outline with the knob left; the accent and [Faint] measure
 * 1.77:1 against each other, so a reader who sees no colour reads this entirely
 * from fill and position. That is WCAG 1.4.1, and why the fill is not
 * decoration.
 */
@Composable
fun Toggle(on: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 42.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (on) {
                    Modifier.background(Stamp)
                } else {
                    Modifier.border(1.dp, Faint, RoundedCornerShape(12.dp))
                },
            )
            .padding(horizontal = 3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (on) Paper else Faint),
        )
    }
}

