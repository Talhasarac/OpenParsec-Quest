package com.example.parsecdemo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * Compact, non-modal network and device warning badges shown over a stream.
 */
final class PerformanceWarningOverlay extends LinearLayout {
    private final WarningBadge networkBadge;
    private final WarningBadge deviceBadge;

    PerformanceWarningOverlay(Context context) {
        super(context);
        setOrientation(HORIZONTAL);

        networkBadge = new WarningBadge(context, WarningBadge.Kind.NETWORK);
        networkBadge.setContentDescription("Network performance warning");
        networkBadge.setOnClickListener(v -> Toast.makeText(context,
                "Network is struggling. Lower bandwidth or use 5 GHz Wi-Fi.",
                Toast.LENGTH_LONG).show());

        deviceBadge = new WarningBadge(context, WarningBadge.Kind.DEVICE);
        deviceBadge.setContentDescription("Device decode performance warning");
        deviceBadge.setOnClickListener(v -> Toast.makeText(context,
                "The stream is too demanding. Lower resolution or frame rate.",
                Toast.LENGTH_LONG).show());

        int badgeSize = dp(40);
        LayoutParams networkParams = new LayoutParams(badgeSize, badgeSize);
        networkParams.rightMargin = dp(6);
        addView(networkBadge, networkParams);
        addView(deviceBadge, new LayoutParams(badgeSize, badgeSize));
        setWarnings(false, false);
    }

    void setWarnings(boolean network, boolean device) {
        networkBadge.setVisibility(network ? VISIBLE : GONE);
        deviceBadge.setVisibility(device ? VISIBLE : GONE);
        setVisibility(network || device ? VISIBLE : GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class WarningBadge extends View {
        enum Kind { NETWORK, DEVICE }

        private final Kind kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;

        WarningBadge(Context context, Kind kind) {
            super(context);
            this.kind = kind;
            this.density = getResources().getDisplayMetrics().density;
            setClickable(true);
            setFocusable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float stroke = 2f * density;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xE6212328);
            rect.set(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f);
            canvas.drawRoundRect(rect, 9f * density, 9f * density, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(0xFFFFB300);
            canvas.drawRoundRect(rect, 9f * density, 9f * density, paint);

            if (kind == Kind.NETWORK) {
                drawNetwork(canvas, w, h);
            } else {
                drawDevice(canvas, w, h);
            }
            drawExclamation(canvas, w);
        }

        private void drawNetwork(Canvas canvas, float w, float h) {
            float cx = w * 0.48f;
            float cy = h * 0.70f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.4f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.WHITE);
            for (float radiusDp : new float[]{12f, 7.5f}) {
                float radius = radiusDp * density;
                rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
                canvas.drawArc(rect, 205f, 130f, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, 2.2f * density, paint);
        }

        private void drawDevice(Canvas canvas, float w, float h) {
            float cx = w * 0.48f;
            float cy = h * 0.52f;
            float half = 8f * density;
            float pin = 4f * density;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f * density);
            paint.setStrokeCap(Paint.Cap.SQUARE);
            paint.setColor(Color.WHITE);
            rect.set(cx - half, cy - half, cx + half, cy + half);
            canvas.drawRoundRect(rect, 2f * density, 2f * density, paint);
            rect.inset(4f * density, 4f * density);
            canvas.drawRect(rect, paint);

            for (float offsetDp : new float[]{-5f, 0f, 5f}) {
                float offset = offsetDp * density;
                canvas.drawLine(cx - half - pin, cy + offset,
                        cx - half, cy + offset, paint);
                canvas.drawLine(cx + half, cy + offset,
                        cx + half + pin, cy + offset, paint);
                canvas.drawLine(cx + offset, cy - half - pin,
                        cx + offset, cy - half, paint);
                canvas.drawLine(cx + offset, cy + half,
                        cx + offset, cy + half + pin, paint);
            }
        }

        private void drawExclamation(Canvas canvas, float w) {
            float cx = w - 8.5f * density;
            float cy = 8.5f * density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFB300);
            canvas.drawCircle(cx, cy, 6f * density, paint);
            paint.setColor(0xFF16181C);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 9,
                    getResources().getDisplayMetrics()));
            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = cy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText("!", cx, baseline, paint);
        }
    }
}
