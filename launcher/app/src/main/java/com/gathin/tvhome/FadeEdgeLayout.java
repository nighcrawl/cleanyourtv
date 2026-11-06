package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.widget.FrameLayout;

/** A FrameLayout whose children are multiplied by the alpha of a drawable (see res/drawable/banner_mask.xml). */
final class FadeEdgeLayout extends FrameLayout {
    private final Drawable mask;
    private final Paint maskPaint = new Paint();

    FadeEdgeLayout(Context context, int maskRes) {
        super(context);
        mask = context.getDrawable(maskRes);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        int save = canvas.saveLayer(0, 0, w, h, null);
        super.dispatchDraw(canvas);
        mask.setBounds(0, 0, w, h);
        canvas.saveLayer(0, 0, w, h, maskPaint);
        mask.draw(canvas);
        canvas.restoreToCount(save);
    }
}
