package my.pinged.charts.screenshot

/**
 * The committed golden for [name], as a path from the module directory, which
 * is the JVM tests' working directory.
 *
 * Must agree with `roborazzi.outputDir` in this module's build file: the
 * plugin tracks that directory as the record and verify tasks' input, and a
 * golden written anywhere else is neither committed nor compared. A golden is
 * a picture of its input and the repository is public, so every screenshot
 * draws constructed data only (CLAUDE.md).
 */
fun golden(name: String): String = "src/test/screenshots/$name.png"
