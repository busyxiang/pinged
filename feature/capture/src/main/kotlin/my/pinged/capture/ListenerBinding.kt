package my.pinged.capture

/**
 * Whether the system has bound [PingedNotificationListener] in this process.
 *
 * `CaptureReport` keeps no "bound" field because no public API answers that
 * from outside the service. Inside the process that hosts it, the service's
 * own `onListenerConnected` and `onListenerDisconnected` do, because the
 * system calls them. The flag also dies with the process, which unbinds the
 * service.
 *
 * Cleared in `onDestroy` as well as on disconnect. If a rebind's new instance
 * connects before the old one is destroyed, the flag reads false while bound.
 * That only skips a `capture_day` write, the same as having no flag at all.
 * A missed clear would claim a bound day that was not.
 */
internal object ListenerBinding {
    @Volatile var connected: Boolean = false
}
