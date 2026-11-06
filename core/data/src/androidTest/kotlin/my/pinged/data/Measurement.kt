package my.pinged.data

/**
 * A test that records a figure and asserts nothing, so a local run skips it:
 * `build.gradle.kts` passes `notAnnotation` with this name unless
 * `PINGED_MEASURE=true`, which CI sets on a push to main only. That keeps the
 * figure current without a pull request's run waiting on it.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Measurement
