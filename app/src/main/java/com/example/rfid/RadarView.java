package com.example.rfid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * iPhone-style rotating compass with the RFID radar inside it.
 *
 * <p>The reader is always "up" on screen: the gold heading line at the top and the antenna beam
 * point where the reader points. The outer dial (ticks, degrees and N/E/S/W) is fixed to the
 * room, so it turns the opposite way to the reader: turn the reader clockwise and the dial turns
 * anticlockwise, turn it anticlockwise and the dial turns clockwise. The file's dot sits on the
 * dial at its real bearing, so it turns with the dial and always stays put in the room.
 *
 * <p>The dial glides to the new heading with a short easing (shortest way round, so it never
 * spins the long way across North), which gives the smooth iPhone feel even when the compass
 * sensor delivers jumpy readings.
 */
public class RadarView extends View {

    /**
     * Distance, in cm, shown at the outer radar ring. The dot's radius is its distance on a
     * square-root scale (so centimetres close to the reader are easy to see). Ring positions use
     * the same scale, so a ring labelled "50 cm" really is 50 cm.
     */
    public static final float RANGE_CM = 300f;
    private static final float[] RING_CM = {25f, 50f, 100f, 200f, RANGE_CM};
    private static final String[] RING_LABEL = buildRingLabels();

    /** Half-width of the antenna beam, degrees. Must match the in-beam test in RadarActivity. */
    private static final float BEAM_HALF_DEG = 28f;

    /** How far the dial moves toward the real heading on each frame (0..1). */
    private static final float HEADING_EASE = 0.22f;

    // ---- palette ----
    private static final int C_DISK_INNER = Color.parseColor("#14294F");
    private static final int C_DISK_OUTER = Color.parseColor("#0A1830");
    private static final int C_BEZEL = Color.parseColor("#2A4470");
    private static final int C_TICK_MINOR = Color.parseColor("#5B6F92");
    private static final int C_TICK_MID = Color.parseColor("#9AABC7");
    private static final int C_TICK_MAJOR = Color.parseColor("#F3F5FA");
    private static final int C_NORTH = Color.parseColor("#E5484D");
    private static final int C_GOLD = Color.parseColor("#C9A227");
    private static final int C_GOLD_LIGHT = Color.parseColor("#E6C766");
    private static final int C_RING = Color.parseColor("#2A4470");
    private static final int C_TARGET_FAR = Color.parseColor("#F5C542");
    private static final int C_TARGET_BEAM = Color.parseColor("#2FC77D");
    private static final int C_TARGET_PIN = Color.parseColor("#10B981");

    private final Paint diskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bezelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint degreePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardinalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lubberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint puckPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint puckRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path beamPath = new Path();
    private final Path arrowPath = new Path();
    private final Path northMarker = new Path();
    private final RectF arcRect = new RectF();
    private final Paint.FontMetrics fm = new Paint.FontMetrics();

    private Shader beamIdleShader;
    private Shader beamActiveShader;

    private final float dp;

    // geometry, set in onSizeChanged
    private float cx, cy;
    private float rOuter;      // edge of the dial
    private float rDegrees;    // centre line of the degree numbers
    private float rTickOuter;  // outer end of the ticks
    private float rCardinal;   // centre of the N/E/S/W letters
    private float rMax;        // outer radar ring
    private float rMin;        // radar centre (reader puck)

    // state from the activity
    private float strength = 0f;
    private float targetHeading = 0f;     // latest compass reading
    private float displayHeading = 0f;    // what the dial shows (eases toward targetHeading)
    private boolean headingInitialised = false;
    private float targetBearing = 0f;
    private boolean inBeam = false;
    private boolean isPinpoint = false;
    private boolean hasTarget = false;

    public RadarView(Context context) {
        this(context, null);
    }

    public RadarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RadarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        dp = context.getResources().getDisplayMetrics().density;
        init();
    }

    private static String[] buildRingLabels() {
        String[] out = new String[RING_CM.length];
        for (int i = 0; i < RING_CM.length; i++) {
            float cm = RING_CM[i];
            out[i] = cm < 100f
                    ? String.format(java.util.Locale.US, "%d cm", Math.round(cm))
                    : String.format(java.util.Locale.US, "%s m", trimMeters(cm / 100f));
        }
        return out;
    }

    private static String trimMeters(float m) {
        return m == Math.round(m) ? String.valueOf(Math.round(m)) : String.format(java.util.Locale.US, "%.1f", m);
    }

    private void init() {
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        bezelPaint.setStyle(Paint.Style.STROKE);
        bezelPaint.setColor(C_BEZEL);
        bezelPaint.setStrokeWidth(1.5f * dp);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        degreePaint.setColor(C_TICK_MID);
        degreePaint.setTextAlign(Paint.Align.CENTER);
        degreePaint.setTypeface(medium);

        cardinalPaint.setColor(C_TICK_MAJOR);
        cardinalPaint.setTextAlign(Paint.Align.CENTER);
        cardinalPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(C_RING);
        ringPaint.setStrokeWidth(1f * dp);

        ringLabelPaint.setColor(C_TICK_MINOR);
        ringLabelPaint.setTextAlign(Paint.Align.LEFT);
        ringLabelPaint.setTypeface(medium);

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setColor(C_RING);
        axisPaint.setStrokeWidth(1f * dp);
        axisPaint.setPathEffect(new DashPathEffect(new float[]{4f * dp, 5f * dp}, 0));

        beamPaint.setStyle(Paint.Style.FILL);

        lubberPaint.setStyle(Paint.Style.STROKE);
        lubberPaint.setStrokeCap(Paint.Cap.ROUND);
        lubberPaint.setColor(C_GOLD_LIGHT);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        targetPaint.setStyle(Paint.Style.FILL);
        haloPaint.setStyle(Paint.Style.FILL);
        whitePaint.setColor(Color.WHITE);

        puckPaint.setStyle(Paint.Style.FILL);
        puckPaint.setColor(Color.parseColor("#0B1D3A"));
        puckRingPaint.setStyle(Paint.Style.STROKE);
        puckRingPaint.setColor(C_GOLD);

        arrowPaint.setStyle(Paint.Style.FILL);
        arrowPaint.setColor(C_GOLD_LIGHT);
    }

    /**
     * @param strength      0 = outer ring .. 1 = at the reader; places the dot
     * @param deviceHeading compass heading the reader points to, degrees
     * @param targetBearing estimated compass bearing of the file, degrees
     */
    public void update(float strength, float deviceHeading, float targetBearing, boolean inBeam, boolean isPinpoint, boolean hasTarget) {
        this.strength = strength;
        this.targetHeading = norm360(deviceHeading);
        if (!headingInitialised) {
            displayHeading = this.targetHeading;
            headingInitialised = true;
        }
        this.targetBearing = targetBearing;
        this.inBeam = inBeam;
        this.isPinpoint = isPinpoint;
        this.hasTarget = hasTarget;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h / 2f;
        rOuter = Math.min(cx, cy) - 2f * dp;

        // Scale everything to the dial so it looks right on small and large screens.
        float s = Math.max(0.7f, Math.min(1.25f, rOuter / (150f * dp)));
        degreePaint.setTextSize(11f * dp * s);
        cardinalPaint.setTextSize(19f * dp * s);
        ringLabelPaint.setTextSize(9f * dp * s);

        rDegrees = rOuter - 11f * dp * s;
        rTickOuter = rOuter - 22f * dp * s;
        rCardinal = rTickOuter - 30f * dp * s;
        rMax = rCardinal - 16f * dp * s;
        rMin = 18f * dp * s;

        lubberPaint.setStrokeWidth(3.5f * dp * s);
        arcPaint.setStrokeWidth(3f * dp * s);
        puckRingPaint.setStrokeWidth(2f * dp * s);

        if (rOuter > 0) {
            diskPaint.setShader(new RadialGradient(cx, cy, rOuter,
                    C_DISK_INNER, C_DISK_OUTER, Shader.TileMode.CLAMP));
        }
        if (rMax > 0) {
            beamIdleShader = new LinearGradient(cx, cy, cx, cy - rMax,
                    Color.argb(110, 201, 162, 39), Color.argb(0, 201, 162, 39), Shader.TileMode.CLAMP);
            beamActiveShader = new LinearGradient(cx, cy, cx, cy - rMax,
                    Color.argb(170, 47, 199, 125), Color.argb(10, 47, 199, 125), Shader.TileMode.CLAMP);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (rMax <= rMin) return;

        // ---- ease the dial toward the real heading, shortest way round ----
        float delta = shortestDelta(targetHeading, displayHeading);
        boolean settling = Math.abs(delta) > 0.05f;
        displayHeading = settling ? norm360(displayHeading + delta * HEADING_EASE) : targetHeading;
        float heading = displayHeading;

        // ---- dial face ----
        canvas.drawCircle(cx, cy, rOuter, diskPaint);
        canvas.drawCircle(cx, cy, rOuter, bezelPaint);

        // ---- radar rings (round, so they need no rotation) ----
        for (int i = 0; i < RING_CM.length; i++) {
            float ringR = ringRadius(RING_CM[i]);
            canvas.drawCircle(cx, cy, ringR, ringPaint);
        }

        // ---- everything fixed to the ROOM turns by -heading ----
        canvas.save();
        canvas.rotate(-heading, cx, cy);

        // north-south / east-west guide lines
        canvas.drawLine(cx, cy - rMax, cx, cy + rMax, axisPaint);
        canvas.drawLine(cx - rMax, cy, cx + rMax, cy, axisPaint);

        // ticks every 2 degrees
        float s = rOuter / (150f * dp);
        for (int deg = 0; deg < 360; deg += 2) {
            float len;
            if (deg % 30 == 0) {
                len = 13f * dp * s;
                tickPaint.setStrokeWidth(2.2f * dp);
                tickPaint.setColor(deg == 0 ? C_NORTH : C_TICK_MAJOR);
            } else if (deg % 10 == 0) {
                len = 10f * dp * s;
                tickPaint.setStrokeWidth(1.4f * dp);
                tickPaint.setColor(C_TICK_MID);
            } else {
                len = 6f * dp * s;
                tickPaint.setStrokeWidth(1f * dp);
                tickPaint.setColor(C_TICK_MINOR);
            }
            double a = Math.toRadians(deg);
            float sin = (float) Math.sin(a);
            float cos = (float) Math.cos(a);
            canvas.drawLine(cx + sin * rTickOuter, cy - cos * rTickOuter,
                    cx + sin * (rTickOuter - len), cy - cos * (rTickOuter - len), tickPaint);
        }

        // small red north marker on the outer edge
        float nTip = rOuter - 1.5f * dp;
        float nBase = rOuter - 8f * dp * s;
        float nHalf = 5f * dp * s;
        northMarker.reset();
        northMarker.moveTo(cx, cy - nBase);
        northMarker.lineTo(cx - nHalf, cy - nTip);
        northMarker.lineTo(cx + nHalf, cy - nTip);
        northMarker.close();
        tickPaint.setStyle(Paint.Style.FILL);
        tickPaint.setColor(C_NORTH);
        canvas.drawPath(northMarker, tickPaint);
        tickPaint.setStyle(Paint.Style.STROKE);

        canvas.restore();

        // ---- labels: placed on the turning dial but drawn upright so they stay readable ----
        for (int deg = 30; deg < 360; deg += 30) {
            if (deg % 90 == 0) continue; // cardinal letters mark these
            drawUpright(canvas, String.valueOf(deg), deg - heading, rDegrees, degreePaint);
        }
        drawUpright(canvas, "0", -heading, rDegrees, degreePaint);
        drawUpright(canvas, "90", 90f - heading, rDegrees, degreePaint);
        drawUpright(canvas, "180", 180f - heading, rDegrees, degreePaint);
        drawUpright(canvas, "270", 270f - heading, rDegrees, degreePaint);

        cardinalPaint.setColor(C_NORTH);
        drawUpright(canvas, "N", -heading, rCardinal, cardinalPaint);
        cardinalPaint.setColor(C_TICK_MAJOR);
        drawUpright(canvas, "E", 90f - heading, rCardinal, cardinalPaint);
        drawUpright(canvas, "S", 180f - heading, rCardinal, cardinalPaint);
        drawUpright(canvas, "W", 270f - heading, rCardinal, cardinalPaint);

        // ring distance labels, fixed to the screen at the lower right
        float labelAng = (float) Math.toRadians(135);
        for (int i = 1; i < RING_CM.length; i++) {
            float r = ringRadius(RING_CM[i]);
            canvas.drawText(RING_LABEL[i],
                    cx + (float) Math.sin(labelAng) * r + 3f * dp,
                    cy - (float) Math.cos(labelAng) * r + 3f * dp,
                    ringLabelPaint);
        }

        // ---- antenna beam: fixed, always straight ahead of the reader ----
        boolean beamLit = inBeam && hasTarget;
        beamPaint.setShader(beamLit ? beamActiveShader : beamIdleShader);
        beamPath.reset();
        beamPath.moveTo(cx, cy);
        float radHalf = (float) Math.toRadians(BEAM_HALF_DEG);
        float x1 = cx - (float) Math.sin(radHalf) * rMax;
        float y1 = cy - (float) Math.cos(radHalf) * rMax;
        float x2 = cx + (float) Math.sin(radHalf) * rMax;
        arcRect.set(cx - rMax, cy - rMax, cx + rMax, cy + rMax);
        beamPath.lineTo(x1, y1);
        beamPath.arcTo(arcRect, -90f - BEAM_HALF_DEG, 2f * BEAM_HALF_DEG, false);
        beamPath.lineTo(x2, y1);
        beamPath.close();
        canvas.drawPath(beamPath, beamPaint);

        // ---- the file ----
        float rel = shortestDelta(targetBearing, heading); // where the file is, relative to the reader
        if (hasTarget) {
            // arc on the tick ring from "straight ahead" to the file, like the iPhone bearing lock
            int arcColor = beamLit ? C_TARGET_BEAM : C_GOLD;
            arcPaint.setColor(arcColor);
            arcPaint.setAlpha(220);
            float arcR = rTickOuter + 4f * dp * s;
            arcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR);
            if (Math.abs(rel) > 1f) {
                canvas.drawArc(arcRect, -90f, rel, false, arcPaint);
            }

            float dist = rMin + (1f - strength) * (rMax - rMin);
            float rad = (float) Math.toRadians(rel);
            float tx = cx + (float) Math.sin(rad) * dist;
            float ty = cy - (float) Math.cos(rad) * dist;

            // gentle pulsing halo, time based so it is smooth at any frame rate
            float phase = (SystemClock.uptimeMillis() % 1400L) / 1400f;
            float pulse = 0.5f - 0.5f * (float) Math.cos(phase * 2.0 * Math.PI);
            float haloR = (12f + 12f * pulse) * dp * s * 0.9f + strength * 8f * dp;

            int color = isPinpoint ? C_TARGET_PIN : (inBeam ? C_TARGET_BEAM : C_TARGET_FAR);
            haloPaint.setColor(color);
            haloPaint.setAlpha(isPinpoint ? 120 : (inBeam ? 95 : 70));
            targetPaint.setColor(color);

            canvas.drawCircle(tx, ty, haloR, haloPaint);
            canvas.drawCircle(tx, ty, (isPinpoint ? 10f : 8f) * dp * s, targetPaint);
            canvas.drawCircle(tx, ty, 3.2f * dp * s, whitePaint);
        }

        // ---- heading line at the top: fixed, marks where the reader points ----
        canvas.drawLine(cx, cy - rOuter + 3f * dp, cx, cy - rTickOuter + 16f * dp * s, lubberPaint);

        // ---- reader puck in the centre (fixed, pointing up) ----
        canvas.drawCircle(cx, cy, rMin, puckPaint);
        canvas.drawCircle(cx, cy, rMin, puckRingPaint);
        float u = rMin / 18f;
        arrowPath.reset();
        arrowPath.moveTo(cx, cy - 11f * u);
        arrowPath.lineTo(cx + 7f * u, cy + 7f * u);
        arrowPath.lineTo(cx, cy + 3.5f * u);
        arrowPath.lineTo(cx - 7f * u, cy + 7f * u);
        arrowPath.close();
        canvas.drawPath(arrowPath, arrowPaint);

        // keep animating while the dial is still gliding or the halo is pulsing
        if (settling || hasTarget) {
            postInvalidateOnAnimation();
        }
    }

    private float ringRadius(float cm) {
        float f = (float) Math.sqrt(cm / RANGE_CM);
        return rMin + f * (rMax - rMin);
    }

    /** Draws text at a screen angle (0 = top, clockwise) and radius, upright and centred. */
    private void drawUpright(Canvas canvas, String text, float screenDeg, float radius, Paint paint) {
        double a = Math.toRadians(screenDeg);
        float x = cx + (float) Math.sin(a) * radius;
        float y = cy - (float) Math.cos(a) * radius;
        paint.getFontMetrics(fm);
        canvas.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, paint);
    }

    /** Signed smallest angle from {@code from} to {@code to}, in -180..180. */
    private static float shortestDelta(float to, float from) {
        float d = (to - from) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static float norm360(float deg) {
        float h = deg % 360f;
        if (h < 0) h += 360f;
        return h;
    }
}
