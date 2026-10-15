package my.pinged.data

/**
 * A test that records a figure and asserts nothing, so a local run skips it:
 * `build.gradle.kts` passes `notAnnotation` with this name unless `CI` is set.
 * CI runs it, which keeps the figure current on every push to main.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Measurement
