package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * fade_rows: the rows fade out as they scroll up under the clock, instead of being cut off at the grid's top
 * edge. It erases the top of the grid's own picture, so whatever is behind Home (a wallpaper, the sky) shows
 * through; the grid draws into a hardware layer for that ({@link #attach}), or the erasing would punch through
 * the whole window. Android's own requiresFadingEdge draws its fade in the wrong place with this grid's
 * clipChildren=false, so it is not used. The fade grows in over the first pixels of scrolling, so the dock at
 * rest is untouched.
 */
final class TopFade extends RecyclerView.ItemDecoration {

    private final float length;
    private final Paint paint = new Paint();
    private float shaderHeight = -1f;
    /** Moving mode only (see {@link #attachMoving}): where the fade starts, in the grid's coordinates; above it all is erased. */
    private float cut;
    private final boolean moving;
    private final Matrix place = new Matrix();

    private TopFade(float length, boolean moving) {
        this.length = length;
        this.moving = moving;
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    static void attach(RecyclerView grid, float length) {
        grid.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        grid.addItemDecoration(new TopFade(length, false));
    }

    /**
     * For a grid that is moved up under the clock (a translation) instead of scrolled: the caller says where the clock
     * ends in the grid's own coordinates ({@link #setCut}), and everything above fades out over {@code length}.
     */
    static TopFade attachMoving(RecyclerView grid, float length) {
        grid.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        TopFade fade = new TopFade(length, true);
        grid.addItemDecoration(fade);
        return fade;
    }

    /** Moving mode: the fade starts at this y of the grid (above it nothing shows). Redraws the grid when it changed. */
    void setCut(RecyclerView grid, int cut) {
        if (cut == this.cut) return;
        this.cut = cut;
        grid.invalidate();
    }

    /** How tall the fade is after scrolling {@code scrolled} pixels: nothing at rest, then up to {@code length}. */
    static float height(int scrolled, float length) {
        return Math.max(0f, Math.min(length, scrolled));
    }

    @Override
    public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (moving) {
            if (cut + length <= 0f) return;
            if (shaderHeight < 0f) {
                // A smoothstep ramp, not a straight one: the fade starts and ends gently, so it shows no line.
                paint.setShader(new LinearGradient(0f, 0f, 0f, length,
                        new int[]{0xFF000000, 0xD7000000, 0x80000000, 0x28000000, 0x00000000},
                        new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}, Shader.TileMode.CLAMP));
                shaderHeight = length;
            }
            place.setTranslate(0f, cut);
            paint.getShader().setLocalMatrix(place);
            c.drawRect(0f, 0f, parent.getWidth(), cut + length, paint);
            return;
        }
        float fade = height(parent.computeVerticalScrollOffset(), length);
        if (fade <= 0f) return;
        if (fade != shaderHeight) {
            paint.setShader(new LinearGradient(0f, 0f, 0f, fade, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
            shaderHeight = fade;
        }
        c.drawRect(0f, 0f, parent.getWidth(), fade, paint);
    }
}
