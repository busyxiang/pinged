package my.pinged.ledger.sources

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
 */
fun readsLine(pack: ParsePack): String {
    val names = pack.packages.map { it.label }
    val listed = if (names.size < 2) names.joinToString() else names.dropLast(1).joinToString() + " and " + names.last()
    return "Pinged reads $listed. Add payments from other apps by hand."
}
