package com.cartify.app;

/**
 * Tracks whether the app as a whole is in the foreground.
 *
 * The launch animation belongs to re-entering the app, not to moving between screens. A
 * single activity's lifecycle cannot tell those apart - MainActivity stops both when the
 * user goes home and when the player opens on top of it. Counting started activities can:
 * Android starts the incoming activity before stopping the outgoing one, so an in-app
 * navigation keeps the count above zero, while leaving the app drops it to zero.
 *
 * Static state is correct here. It describes the process, and the process is exactly what
 * "is the app in the foreground" means.
 */
public final class AppLifecycle {

    private static int startedCount;

    private AppLifecycle() {
    }

    /**
     * Records an activity starting.
     *
     * @return true when this was the transition from nothing-started to something-started,
     *         i.e. the app just entered the foreground.
     */
    public static boolean onActivityStarted() {
        return ++startedCount == 1;
    }

    /**
     * Records an activity stopping.
     *
     * @return true when that was the last started activity, i.e. the app left the
     *         foreground.
     */
    public static boolean onActivityStopped() {
        if (startedCount > 0) startedCount--;
        return startedCount == 0;
    }
}
