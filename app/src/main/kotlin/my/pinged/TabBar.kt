package my.pinged

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import my.pinged.ledger.theme.Ink
import my.pinged.ledger.theme.Muted
import my.pinged.ledger.theme.MonoLabel
import my.pinged.ledger.theme.Paper
import my.pinged.ledger.theme.Stamp
import my.pinged.ledger.theme.dottedRule

/**
 * `design/Main.dc.html`'s bottom bar, drawn for [Tab]'s two entries.
 *
 * The label is the accessible name, as on every other text-labelled control
 * here, and the icon is decoration. The unselected tab is [Muted], not the
 * artboard's [my.pinged.ledger.theme.Faint]: that is 3.12:1 on [Paper], which
 * clears WCAG 1.4.11 for a component and fails 1.4.3 for the label.
 *
 * Each tab is 62dp high as drawn, which is over the 48dp target and so needs no
 * padding to be tappable.
 */
@Composable
internal fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Paper)
            .dottedRule(atTop = true),
    ) {
        Tab.entries.forEach { tab ->
            val on = tab == selected
            val colour = if (on) Ink else Muted
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 62.dp)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(tab) }),
                contentAlignment = Alignment.Center,
            ) {
                // The artboard's 2px accent rule over the selected tab. The
                // label is darker too, but an unselected tab has no rule at
                // all, so the state does not rest on colour alone.
                if (on) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).background(Stamp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Image(
                        imageVector = tab.icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(colour),
                    )
                    Text(tab.label, style = MonoLabel, color = colour)
                }
            }
        }
    }
}

/** The label the artboard draws, which is also the tab's accessible name. */
private val Tab.label: String
    get() = when (this) {
        Tab.Spending -> "SPENDING"
        Tab.Settings -> "SETTINGS"
    }

/** Stroked outlines on the artboard's 24-unit grid, `stroke-width="1.6"`. */
private val Tab.icon: ImageVector
    get() = when (this) {
        Tab.Spending -> icon("M4 6h16M4 12h16M4 18h10")
        // The artboard's three sliders: a rule each, and a knob on each.
        Tab.Settings -> icon(
            "M4 7h6M14 7h6M4 12h10M18 12h2M4 17h3M11 17h9" +
                "M10 7a2 2 0 1 0 4 0a2 2 0 1 0 -4 0" +
                "M14 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0" +
                "M7 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
        )
    }

private fun icon(path: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
        )
        .build()
