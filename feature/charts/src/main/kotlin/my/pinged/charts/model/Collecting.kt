package my.pinged.charts.model

import my.pinged.ui.theme.Separator
import my.pinged.data.Collecting
import my.pinged.data.GATE_DAYS

/**
 * The collecting block's mono line, `COLLECTING · N OF 14 DAYS`, 0 included.
 * While the gate (#81, Collecting; #66) is shut this block stands in for the
 * category bars and the merchant ranking; the grid, the hero and the "not in
 * the total" block draw regardless. The count and the fourteen days are
 * `CaptureRecord.collecting`'s; this and [line] are its words.
 */
val Collecting.label: String get() = "COLLECTING$Separator$days OF $GATE_DAYS DAYS"

/** The block's serif line. */
val Collecting.line: String get() = "Category bars and top merchants appear after two weeks of capture."
