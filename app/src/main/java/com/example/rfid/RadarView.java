package com.example.rfid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 60 FPS Hardware-Accelerated Native Radar View.
 * Displays antenna beam following reader compass, cardinal points, and pinpoint tag location.
 */
public class RadarView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardinalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beamActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path beamPath = new Path();
    private final Path arrowPath = new Path();

    private float strength = 0f;
    private float deviceHeading = 0f;
    private float targetBearing = 0f;
    private boolean inBeam = false;
    private boolean isPinpoint = false;
    private boolean hasTarget = false;

    private float haloAnimRadius = 0f;
    private boolean haloGrowing = true;

    public RadarView(Context context) {
        super(context);
        init();
    }

    public RadarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RadarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(Color.parseColor("#2F7A4C"));
        ringPaint.setStrokeWidth(2f);
        ringPaint.setAlpha(120);

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setColor(Color.parseColor("#2F7A4C"));
        axisPaint.setStrokeWidth(1.5f);
        axisPaint.setAlpha(90);
        axisPaint.setPathEffect(new DashPathEffect(new float[]{8f, 8f}, 0));

        cardinalPaint.setColor(Color.parseColor("#4ADE80"));
        cardinalPaint.setTextSize(32f);
        cardinalPaint.setTextAlign(Paint.Align.CENTER);
        cardinalPaint.setFakeBoldText(true);

        beamPaint.setStyle(Paint.Style.FILL);
        beamPaint.setColor(Color.parseColor("#334ADE80"));

        beamActivePaint.setStyle(Paint.Style.FILL);
        beamActivePaint.setColor(Color.parseColor("#7722C55E"));

        targetPaint.setStyle(Paint.Style.FILL);
        targetPaint.setColor(Color.parseColor("#FACC15"));

        haloPaint.setStyle(Paint.Style.FILL);
        haloPaint.setColor(Color.parseColor("#FACC15"));

        centerPaint.setStyle(Paint.Style.FILL);
        centerPaint.setColor(Color.parseColor("#0A1A12"));

        arrowPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        arrowPaint.setColor(Color.parseColor("#4ADE80"));
        arrowPaint.setStrokeWidth(3f);
    }

    public void update(float strength, float deviceHeading, float targetBearing, boolean inBeam, boolean isPinpoint, boolean hasTarget) {
        this.strength = strength;
        this.deviceHeading = deviceHeading;
        this.targetBearing = targetBearing;
        this.inBeam = inBeam;
        this.isPinpoint = isPinpoint;
        this.hasTarget = hasTarget;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float rMax = Math.min(cx, cy) - 40f;
        float rMin = 35f;

        if (rMax <= rMin) return;

        // Background dark radial gradient
        RadialGradient bgGrad = new RadialGradient(cx, cy, rMax + 20f,
                Color.parseColor("#12311F"), Color.parseColor("#09160F"), Shader.TileMode.CLAMP);
        bgPaint.setShader(bgGrad);
        canvas.drawCircle(cx, cy, rMax + 20f, bgPaint);

        // Concentric distance rings
        canvas.drawCircle(cx, cy, rMax * 0.25f, ringPaint);
        canvas.drawCircle(cx, cy, rMax * 0.50f, ringPaint);
        canvas.drawCircle(cx, cy, rMax * 0.75f, ringPaint);
        canvas.drawCircle(cx, cy, rMax, ringPaint);

        // Crosshairs
        canvas.drawLine(cx - rMax, cy, cx + rMax, cy, axisPaint);
        canvas.drawLine(cx, cy - rMax, cx, cy + rMax, axisPaint);

        // Cardinal directions (North, East, South, West)
        canvas.drawText("N", cx, cy - rMax + 28f, cardinalPaint);
        canvas.drawText("E", cx + rMax - 20f, cy + 10f, cardinalPaint);
        canvas.drawText("S", cx, cy + rMax - 12f, cardinalPaint);
        canvas.drawText("W", cx - rMax + 20f, cy + 10f, cardinalPaint);

        // DYNAMIC ANTENNA BEAM CONE (Rotates with handheld reader compass)
        canvas.save();
        canvas.rotate(deviceHeading, cx, cy);

        beamPath.reset();
        beamPath.moveTo(cx, cy);
        float beamRadius = rMax + 10f;
        float halfAngle = 28f;
        float radHalf = (float) Math.toRadians(halfAngle);
        float x1 = cx - (float) Math.sin(radHalf) * beamRadius;
        float y1 = cy - (float) Math.cos(radHalf) * beamRadius;
        float x2 = cx + (float) Math.sin(radHalf) * beamRadius;
        float y2 = cy - (float) Math.cos(radHalf) * beamRadius;

        beamPath.lineTo(x1, y1);
        beamPath.quadTo(cx, cy - beamRadius, x2, y2);
        beamPath.close();

        canvas.drawPath(beamPath, (inBeam && hasTarget) ? beamActivePaint : beamPaint);
        canvas.restore();

        // TARGET TAG (Placed at physical room bearing and measured distance)
        if (hasTarget) {
            float dist = rMin + (1f - strength) * (rMax - rMin);
            float rad = (float) Math.toRadians(targetBearing);
            float tx = cx + (float) Math.sin(rad) * dist;
            float ty = cy - (float) Math.cos(rad) * dist;

            // Halo animation
            if (haloGrowing) {
                haloAnimRadius += 0.8f;
                if (haloAnimRadius >= 26f) haloGrowing = false;
            } else {
                haloAnimRadius -= 0.8f;
                if (haloAnimRadius <= 12f) haloGrowing = true;
            }

            if (isPinpoint) {
                haloPaint.setColor(Color.parseColor("#34D399"));
                haloPaint.setAlpha(140);
                targetPaint.setColor(Color.parseColor("#10B981"));
            } else if (inBeam) {
                haloPaint.setColor(Color.parseColor("#4ADE80"));
                haloPaint.setAlpha(110);
                targetPaint.setColor(Color.parseColor("#22C55E"));
            } else {
                haloPaint.setColor(Color.parseColor("#FACC15"));
                haloPaint.setAlpha(70);
                targetPaint.setColor(Color.parseColor("#FACC15"));
            }

            canvas.drawCircle(tx, ty, haloAnimRadius + strength * 14f, haloPaint);
            canvas.drawCircle(tx, ty, isPinpoint ? 18f : 14f, targetPaint);

            // Inner white dot
            Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            whitePaint.setColor(Color.WHITE);
            canvas.drawCircle(tx, ty, 6f, whitePaint);
        }

        // CENTER READER (Rotates with reader deviceHeading)
        canvas.save();
        canvas.rotate(deviceHeading, cx, cy);
        canvas.drawCircle(cx, cy, 22f, centerPaint);

        arrowPath.reset();
        arrowPath.moveTo(cx, cy - 16f);
        arrowPath.lineTo(cx + 8f, cy - 5f);
        arrowPath.lineTo(cx + 3f, cy - 5f);
        arrowPath.lineTo(cx + 3f, cy + 10f);
        arrowPath.lineTo(cx - 3f, cy + 10f);
        arrowPath.lineTo(cx - 3f, cy - 5f);
        arrowPath.lineTo(cx - 8f, cy - 5f);
        arrowPath.close();

        canvas.drawPath(arrowPath, arrowPaint);
        canvas.restore();
    }
}
