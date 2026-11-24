package com.example.rfid;

/**
 * Turns the reader's raw signal value (rssi) into a distance to the tag, in centimetres.
 *
 * Model (log-distance, which is how RFID signal fades):
 *
 *     rssi(d) = refRssi - slope * log10(d / refCm)      so      d = refCm * 10^((refRssi - rssi) / slope)
 *
 * refCm / refRssi / slope come from {@link #configure}. Without a calibration they are rough
 * defaults chosen by the caller; a two-point calibration ({@link #fit}) replaces them with real
 * values for this reader, tag and power setting.
 *
 * Only fresh reads are fed in (see RadarActivity), so nothing changes while the trigger is
 * released or the tag is out of the beam. The distance is taken from the STRONGEST of the last
 * WINDOW reads: aiming slightly off the tag only ever makes the signal weaker, so the strongest
 * recent read is the best sign of the true distance, and it still follows you as you move
 * closer or back away within a fraction of a second.
 *
 * Plain Java, no Android classes.
 */
final class DistanceEstimator {

    /** Number of most recent fresh reads considered. */
    static final int WINDOW = 12;

    /** How quickly the shown distance follows the measurement (0..1, per fresh read). */
    private static final double FOLLOW = 0.35;

    static final double MIN_CM = 1.0;
    static final double MAX_CM = 1000.0;

    /**
     * A read older than this is ignored. Without it, when reads come slowly (far away, or the
     * tag only just inside the beam) the last 12 reads can span many seconds, so one strong read
     * from when you were close kept the distance "stuck" close after you moved back.
     */
    static final long MAX_AGE_MS = 1500;

    private final int[] recent = new int[WINDOW];
    private final long[] recentAt = new long[WINDOW];
    private long lastReadMs = 0;
    private int count = 0;
    private int next = 0;

    private double refCm = 15.0;
    private double refRssi = -17.0;
    private double slope = 40.0;
    private boolean calibrated = false;

    private double smoothLog = Double.NaN; // natural log of the smoothed distance in cm

    void configure(double refCm, double refRssi, double slope, boolean calibrated) {
        this.refCm = refCm;
        this.refRssi = refRssi;
        this.slope = slope;
        this.calibrated = calibrated;
        // Re-express the current window with the new model, without waiting for new reads.
        if (count > 0) {
            smoothLog = Math.log(distanceForRssi(strongestRecent()));
        }
    }

    /** Forget the recent reads (used at the start of each calibration step). */
    void resetWindow() {
        count = 0;
        next = 0;
        smoothLog = Double.NaN;
    }

    boolean isCalibrated() {
        return calibrated;
    }

    /** Human-readable model numbers, for the diagnostic line and the readings log. */
    String modelText() {
        return String.format(java.util.Locale.US, "ref %.0f @ %.0f cm, slope %.0f dB/decade (%s)",
                refRssi, refCm, slope, calibrated ? "calibrated" : "default");
    }

    boolean hasEstimate() {
        return count > 0 && !Double.isNaN(smoothLog);
    }

    /** Smoothed distance in cm. Only meaningful if hasEstimate(). */
    double distanceCm() {
        return Math.exp(smoothLog);
    }

    /** Feed one FRESH read. */
    void addRead(int rssi) {
        addRead(rssi, System.nanoTime() / 1000000L);
    }

    /** Same, with an explicit clock (milliseconds), so the behaviour can be tested. */
    void addRead(int rssi, long nowMs) {
        recent[next] = rssi;
        recentAt[next] = nowMs;
        next = (next + 1) % WINDOW;
        if (count < WINDOW) count++;
        lastReadMs = nowMs;

        double measuredLog = Math.log(distanceForRssi(strongestRecent()));
        if (Double.isNaN(smoothLog)) {
            smoothLog = measuredLog;
        } else {
            smoothLog += FOLLOW * (measuredLog - smoothLog);
        }
    }

    /** Strongest of the last WINDOW reads that are not older than MAX_AGE_MS (the newest always counts). */
    int strongestRecent() {
        int best = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            if (lastReadMs - recentAt[i] <= MAX_AGE_MS && recent[i] > best) best = recent[i];
        }
        return best;
    }

    double distanceForRssi(double rssi) {
        double d = refCm * Math.pow(10.0, (refRssi - rssi) / slope);
        return Math.max(MIN_CM, Math.min(MAX_CM, d));
    }

    /**
     * Two-point calibration. nearRssi / farRssi are the strongest signals measured with the reader
     * aimed straight at the tag at nearCm and farCm. Returns {refCm, refRssi, slope}, or null if
     * the numbers do not make sense (the signal must be clearly weaker at the far point).
     */
    static double[] fit(double nearCm, double nearRssi, double farCm, double farRssi) {
        double drop = nearRssi - farRssi;
        if (drop < 3.0 || farCm <= nearCm) return null;
        double slope = drop / Math.log10(farCm / nearCm);
        slope = Math.max(15.0, Math.min(60.0, slope));
        return new double[]{nearCm, nearRssi, slope};
    }
}
