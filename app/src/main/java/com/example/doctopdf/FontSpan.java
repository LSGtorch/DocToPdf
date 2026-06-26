package com.example.doctopdf;

import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

/**
 * A span that applies a Typeface backed by bundled Liberation fonts during
 * StaticLayout drawing, without requiring the span itself to hold a Context.
 *
 * The Typeface is resolved lazily via {@link FontHelper#getTypeface}.
 */
public class FontSpan extends MetricAffectingSpan {

    private final String mFontFamily;
    private final boolean mBold;
    private final boolean mItalic;
    private Typeface mTypeface; // cached after first resolution

    public FontSpan(String fontFamily, boolean bold, boolean italic) {
        this.mFontFamily = fontFamily;
        this.mBold = bold;
        this.mItalic = italic;
    }

    private Typeface resolve() {
        if (mTypeface == null) {
            mTypeface = FontHelper.getTypeface(mFontFamily, mBold, mItalic);
        }
        return mTypeface;
    }

    @Override
    public void updateDrawState(TextPaint ds) {
        Typeface tf = resolve();
        if (tf != null) ds.setTypeface(tf);
    }

    @Override
    public void updateMeasureState(TextPaint paint) {
        Typeface tf = resolve();
        if (tf != null) paint.setTypeface(tf);
    }
}