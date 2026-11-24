package com.example.rfid;

/**
 * Works out WHERE a stationary tag is, in compass degrees, from the readings made while the
 * operator sweeps the reader around.
 *
 * A tag never moves, so its bearing must not follow the reader. The reader's antenna is
 * strongest when it points at the tag, so the best estimate of the bearing is the compass
 * heading at which the signal was strongest. To be stable against single noisy reads this
 * class takes a signal-weighted circular average of the headings of the strongest reads
 * (those within WINDOW_DB of the best signal seen), instead of the latest heading.
 *
 * Only call addRead() for a FRESH read (the SDK read counter went up). Never call it for a
 * repeated poll of old data, and never call it when the trigger is released: the estimate
 * simply stays where it is.
 *
 * Plain Java, no Android classes.
 */
final class TagBearingEstimator {

    /** Reads weaker than (best - WINDOW_DB) are outside the antenna's main lobe and ignored. */
    static final float WINDOW_DB = 8f;

    /** Old reads lose influence slowly (per fresh read), so a new position can take over. */
    private static final double FORGET = 0.995;

    /** The remembered best signal sinks slowly (per fresh read) so moving away is noticed. */
    private static final float PEAK_SINK_DB = 0.01f;

    /** One poll may represent several reads; do not let a burst dominate. */
    private static final int MAX_BURST = 10;

    private double sumX = 0;   // sum of weight * sin(heading)
    private double sumY = 0;   // sum of weight * cos(heading)
    private float bestRssi = 0f;
    private boolean hasRead = false;
    private float bearing = 0f;

    boolean hasEstimate() {
        return hasRead;
    }

    /** Strongest raw signal value currently remembered. Only meaningful if hasEstimate(). */
    float bestRssi() {
        return bestRssi;
    }

    /** Estimated tag bearing, 0..360 degrees (compass). */
    float bearing() {
        return bearing;
    }

    void addRead(float headingDeg, int rssi, int newReads) {
        sumX *= FORGET;
        sumY *= FORGET;

        if (!hasRead) {
            hasRead = true;
            bestRssi = rssi;
        } else {
            bestRssi -= PEAK_SINK_DB;
        }

        // A clearly better lobe than anything remembered: older headings no longer count.
        if (rssi > bestRssi + WINDOW_DB) {
            sumX = 0;
            sumY = 0;
        }
        if (rssi > bestRssi) {
            bestRssi = rssi;
        }

        if (rssi >= bestRssi - WINDOW_DB) {
            double weight = Math.pow(10.0, (rssi - bestRssi) / 20.0)
                    * Math.max(1, Math.min(newReads, MAX_BURST));
            double h = Math.toRadians(headingDeg);
            sumX += weight * Math.sin(h);
            sumY += weight * Math.cos(h);
        }

        if (Math.hypot(sumX, sumY) > 1e-9) {
            double deg = Math.toDegrees(Math.atan2(sumX, sumY));
            if (deg < 0) deg += 360.0;
            bearing = (float) deg;
        }
    }
}
