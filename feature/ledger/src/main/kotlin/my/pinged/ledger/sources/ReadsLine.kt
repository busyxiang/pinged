package my.pinged.ledger.sources

import my.pinged.data.AppNames
import my.pinged.data.NamedSource
import my.pinged.parse.ParsePack

/**
 * Spec 9.6's sentence under ON YOUR PHONE: which apps Pinged reads, and what
 * to do about the rest.
 *
 * Built from the loaded pack's packages and never a second list, so a package
 * added to the pack is named here with no screen edit. Each app is named by the
 * pack's own `label`, not by `PackageManager`: the sentence describes the pack,
 * not this phone, and an app that is not installed has no label to resolve.
 * Nor does this add to `<queries>`, which costs a release per package.
 * Listed in [AppNames]' order and joiner, as the sources line lists them.
 */
fun readsLine(pack: ParsePack): String {
    val apps = AppNames.ordered(pack.packages.map { NamedSource(it.pkg, it.label) })
    val listed = AppNames.listed(apps, " and ", name = { it.name }, words = { it }).joinToString("")
    return "Pinged reads $listed. Add payments from other apps by hand."
}
