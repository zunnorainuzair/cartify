package com.cartify.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The launch animation: the glass mark, then the word built out of particles.
 *
 * The word is not drawn as text at all. "Cartify" is rasterised once into an offscreen
 * mask, and every lit pixel of that mask becomes a particle target. The particles drift in
 * from around the screen, settle into the letterforms, shimmer while they hold, and then
 * blow outward just before the activity is revealed.
 *
 * Doing it this way means the word is always made of the same material as the motion: the
 * burst at the end is simply the same particles leaving their targets, so nothing has to be
 * cross-faded or swapped in at the last moment.
 */
public class SplashView extends View {

    /** Fired when the word starts being drawn, and when the whole intro is done. */
    public interface Listener {
        void onBrandShown();

        void onFinished();
    }

    private static final long DURATION = 3600L;
    private static final String WORD = "Cartify";

    /** One dot of the word. Targets are in view coordinates. */
    private static final class Particle {
        float tx, ty;      // where it belongs in the word
        float sx, sy;      // where it drifts in from
        float ex, ey;      // unit direction it leaves on
        float dist;        // how far it travels when the word bursts
        float radius;
        float delay;       // per-particle stagger, so the word assembles as a wave
        float seed;
        int color;
    }

    private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path clip = new Path();
    private final RectF clipRect = new RectF();
    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random(7);

    private Bitmap logo;
    private float t;
    private float density;
    private ValueAnimator anim;
    private Listener listener;
    private boolean brandFired;
    private float wordCenterY;

    public SplashView(Context c) {
        this(c, null);
    }

    public SplashView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.5f * density);
        ringPaint.setColor(0xFF4CE87F);
        sweepPaint.setStyle(Paint.Style.FILL);
        dotPaint.setStyle(Paint.Style.FILL);
        try {
            logo = BitmapFactory.decodeResource(getResources(), R.drawable.cartify_logo);
        } catch (Throwable ignored) {
            logo = null;
        }
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Rewinds to frame zero so the intro always plays from the start. */
    public void reset() {
        stop();
        t = 0f;
        brandFired = false;
        invalidate();
    }

    public void start() {
        if (anim != null) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(DURATION);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            if (!brandFired && t >= 0.22f) {
                brandFired = true;
                if (listener != null) listener.onBrandShown();
            }
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                // Cleared so the view can play again. Without this the field stays assigned
                // to a finished animator, start()'s guard sees non-null and returns, and a
                // replayed splash hangs motionless for its whole duration.
                anim = null;
                if (listener != null) listener.onFinished();
            }
        });
        anim.start();
    }

    public void stop() {
        if (anim != null) {
            // Listeners first: cancel() delivers onAnimationEnd, and onFinished() hides the
            // splash. Without this, stopping an intro to replay it reported that it had
            // completed and the new run was faded out before it drew a frame.
            anim.removeAllListeners();
            anim.cancel();
            anim = null;
        }
    }

    /** Normalised progress between two timeline points, eased out. */
    private float seg(float from, float to) {
        if (t <= from) return 0f;
        if (t >= to) return 1f;
        float p = (t - from) / (to - from);
        return 1f - (1f - p) * (1f - p);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        buildParticles(w, h);
    }

    /**
     * Rasterises the word once and turns its lit pixels into particle targets.
     *
     * Sampling with a step keeps the count in the low thousands on a phone screen, which
     * is enough for the letterforms to read while staying cheap to redraw every frame.
     */
    private void buildParticles(int w, int h) {
        particles.clear();
        if (w <= 0 || h <= 0) return;

        float wordSize = Math.min(w, h) * 0.125f;
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setTextSize(wordSize);
        textPaint.setColor(0xFFFFFFFF);

        float measured = textPaint.measureText(WORD);
        float maxWidth = w * 0.66f;
        if (measured > maxWidth) {
            wordSize *= maxWidth / measured;
            textPaint.setTextSize(wordSize);
            measured = textPaint.measureText(WORD);
        }

        int bw = (int) Math.ceil(measured) + 6;
        int bh = (int) Math.ceil(wordSize * 1.5f) + 6;
        Bitmap mask;
        try {
            mask = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            return;
        }
        Canvas maskCanvas = new Canvas(mask);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = (bh - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        maskCanvas.drawText(WORD, 3, baseline, textPaint);

        // Place the word just under the mark.
        float logoCenterY = h * 0.40f;
        float logoSide = Math.min(w, h) * 0.56f;
        float wordTop = logoCenterY + logoSide * 0.5f + 26f * density;
        float wordLeft = (w - bw) / 2f;
        wordCenterY = wordTop + bh / 2f;

        int[] pixels = new int[bw * bh];
        mask.getPixels(pixels, 0, bw, 0, 0, bw, bh);

        int step = 3;
        float maxDist = Math.max(w, h) * 0.55f;
        int green = 0xFF7DFFA8;
        int pale = 0xFFE9FFF2;

        for (int y = 0; y < bh; y += step) {
            for (int x = 0; x < bw; x += step) {
                int alpha = (pixels[y * bw + x] >>> 24);
                if (alpha < 90) continue;
                Particle p = new Particle();
                p.tx = wordLeft + x;
                p.ty = wordTop + y;

                // Drift in from a wide ring around the screen centre.
                double angle = random.nextDouble() * Math.PI * 2;
                float reach = maxDist * (0.55f + random.nextFloat() * 0.9f);
                p.sx = p.tx + (float) Math.cos(angle) * reach;
                p.sy = p.ty + (float) Math.sin(angle) * reach * 0.7f;

                // Burst outward, biased away from the word's centre.
                float dx = p.tx - (w / 2f);
                float dy = p.ty - wordCenterY;
                float len = (float) Math.hypot(dx, dy);
                if (len < 1f) {
                    double a2 = random.nextDouble() * Math.PI * 2;
                    dx = (float) Math.cos(a2);
                    dy = (float) Math.sin(a2);
                    len = 1f;
                }
                p.ex = dx / len + (random.nextFloat() - 0.5f) * 0.55f;
                p.ey = dy / len + (random.nextFloat() - 0.5f) * 0.55f;
                p.dist = maxDist * (0.35f + random.nextFloat() * 0.85f);

                p.radius = (1.05f + random.nextFloat() * 0.85f) * density;
                p.delay = random.nextFloat() * 0.42f;
                p.seed = random.nextFloat() * 6.28f;
                p.color = random.nextFloat() < 0.78f ? green : pale;
                particles.add(p);
            }
        }
        mask.recycle();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float cy = h * 0.40f;
        float side = Math.min(w, h) * 0.56f;

        float appear = seg(0f, 0.30f);
        float leave = seg(0.90f, 1f);
        float visible = 1f - leave;
        float lift = (1f - appear) * 26f * density;
        cy += lift;
        float scale = 0.90f + 0.10f * appear;

        drawGlow(canvas, cx, cy, side * scale, appear * visible);
        drawRings(canvas, cx, cy, side * scale, visible);

        if (logo != null) {
            float half = side * scale / 2f;
            bounds.set(cx - half, cy - half, cx + half, cy + half);

            float radius = bounds.width() * 0.235f;
            clipRect.set(bounds);
            clip.reset();
            clip.addRoundRect(clipRect, radius, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(clip);

            logoPaint.setAlpha((int) (255 * 0.34f * appear * visible));
            canvas.drawBitmap(logo, null, bounds, logoPaint);

            if (t >= 0.16f) {
                float sweep = seg(0.16f, 0.62f);
                float edge = bounds.left + bounds.width() * sweep;
                int sweepSave = canvas.save();
                canvas.clipRect(bounds.left, bounds.top - 4 * density, edge, bounds.bottom + 4 * density);
                logoPaint.setAlpha((int) (255 * visible));
                canvas.drawBitmap(logo, null, bounds, logoPaint);
                if (sweep < 1f) {
                    float bandLeft = edge - bounds.width() * 0.22f;
                    sweepPaint.setShader(new android.graphics.LinearGradient(
                            bandLeft, bounds.top, edge, bounds.bottom,
                            new int[]{0x004CE87F, 0x6638FF8C, 0xBB8CFFAE, 0x004CE87F},
                            new float[]{0f, 0.55f, 0.85f, 1f}, Shader.TileMode.CLAMP));
                    canvas.drawRect(bandLeft, bounds.top, edge, bounds.bottom, sweepPaint);
                    sweepPaint.setShader(null);
                }
                canvas.restoreToCount(sweepSave);
            }

            ringPaint.setAlpha((int) (70 * appear * visible));
            ringPaint.setStrokeWidth(1.2f * density);
            canvas.drawRoundRect(bounds, radius, radius, ringPaint);
            ringPaint.setAlpha(255);
            ringPaint.setStrokeWidth(1.5f * density);
            canvas.restoreToCount(save);
        }

        drawParticles(canvas);

        float done = seg(0.62f, 0.84f);
        if (done > 0f && done < 1f) {
            glowPaint.setShader(new RadialGradient(cx, wordCenterY, side * 0.62f,
                    new int[]{0x334CE87F, 0x00040A06},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            glowPaint.setAlpha((int) (255 * done * (1f - done) * 2.2f));
            canvas.drawCircle(cx, wordCenterY, side * 0.62f, glowPaint);
            glowPaint.setShader(null);
        }
    }

    /**
     * The word itself: dots fly in, settle into the letterforms, shimmer, then burst.
     *
     * The burst runs on the same clock as the settle, so the transition from a readable
     * word to a spray of light is continuous rather than a swap between two states.
     */
    private void drawParticles(Canvas canvas) {
        if (particles.isEmpty()) return;

        float assemble = seg(0.18f, 0.60f);
        float burst = seg(0.86f, 1f);
        float burstEase = burst * burst;

        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);

            float local = (assemble - p.delay) / Math.max(0.001f, 1f - p.delay);
            if (local <= 0f) continue;
            if (local > 1f) local = 1f;
            float ease = 1f - (1f - local) * (1f - local);

            float x = p.sx + (p.tx - p.sx) * ease;
            float y = p.sy + (p.ty - p.sy) * ease;

            if (local >= 1f) {
                // Settled: a small idle shimmer so the word breathes instead of freezing.
                float phase = t * 7.5f + p.seed;
                x += (float) Math.sin(phase) * 0.8f * density;
                y += (float) Math.cos(phase * 1.27f) * 0.8f * density;
            }

            if (burstEase > 0f) {
                x += p.ex * p.dist * burstEase;
                y += p.ey * p.dist * burstEase;
            }

            int alpha = (int) (255 * Math.min(1f, local * 1.5f) * (1f - burst * 0.92f));
            if (alpha <= 3) continue;
            dotPaint.setColor((alpha << 24) | (p.color & 0x00FFFFFF));
            float r = p.radius * (1f - burst * 0.5f);
            canvas.drawCircle(x, y, r, dotPaint);
        }
    }

    /** A soft mint halo behind the mark, breathing while it settles. */
    private void drawGlow(Canvas canvas, float cx, float cy, float side, float amount) {
        if (amount <= 0f) return;
        float pulse = 1f + 0.06f * (float) Math.sin(t * Math.PI * 4f);
        float radius = side * 0.78f * pulse;
        glowPaint.setShader(new RadialGradient(cx, cy, radius,
                new int[]{0x554CE87F, 0x1A4CE87F, 0x00040A06},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        glowPaint.setAlpha((int) (255 * amount));
        canvas.drawCircle(cx, cy, radius, glowPaint);
        glowPaint.setShader(null);
    }

    /** Sound rings pulsing out of the mark, staggered and fading as they expand. */
    private void drawRings(Canvas canvas, float cx, float cy, float side, float amount) {
        if (t < 0.12f || t > 0.99f || amount <= 0f) return;
        for (int i = 0; i < 3; i++) {
            float phase = t * 1.7f + i / 3f;
            phase -= (float) Math.floor(phase);
            float radius = side * (0.58f + phase * 0.42f);
            int alpha = (int) (120 * (1f - phase) * amount);
            if (alpha <= 0) continue;
            ringPaint.setAlpha(alpha);
            canvas.drawCircle(cx, cy, radius, ringPaint);
        }
    }
}