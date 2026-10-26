package my.pinged.data

/**
 * What a read that met code 26 does to the shared instance, and nothing else:
 * stops serving [poisoned] and, if it was still the one served, announces the
 * replacement. Not [DatabaseAccess.damageScope], which would also record the
 * damage for the settings screen and so change what the test then reads.
 *
 * Shared by `:feature:capture`, `:feature:ledger` and `:feature:charts`'
 * androidTest source sets, whose build files add this directory; Gradle opts
 * those compilations into the raw access it takes.
 */
fun retireAndAnnounce(poisoned: PingedDatabase): Boolean {
    val retired = Databases.retire(poisoned)
    if (retired) Databases.announceReplacement()
    return retired
}
