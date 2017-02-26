/*
 * Copyright (C) 2016 Muhammad Tayyab Akram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mta.tehreer.graphics;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Log;

import com.mta.tehreer.opentype.WritingDirection;
import com.mta.tehreer.util.FloatList;
import com.mta.tehreer.util.IntList;
import com.mta.tehreer.util.PointList;

/**
 * The <code>Renderer</code> class represents a generic glyph renderer. It can be used to generate
 * glyph paths, measure their bounding boxes and draw them on a <code>Canvas</code> object.
 */
public class Renderer {

    private static final String TAG = Renderer.class.getSimpleName();

    /**
     * Specifies the treatment for the beginning and ending of stroked lines and paths.
     */
    public enum Cap {
        /**
         * The stroke ends with the path, and does not project beyond it.
         */
        BUTT(GlyphRasterizer.LINECAP_BUTT),
        /**
         * The stroke projects out as a semicircle, with the center at the end of the path.
         */
        ROUND(GlyphRasterizer.LINECAP_ROUND),
        /**
         * The stroke projects out as a square, with the center at the end of the path.
         */
        SQUARE(GlyphRasterizer.LINECAP_SQUARE);

        private final int value;

        Cap(int value) {
            this.value = value;
        }
    }

    /**
     * Specifies the treatment where lines and curve segments join on a stroked path.
     */
    public enum Join {
        /**
         * The outer edges of a join meet with a straight line.
         */
        BEVEL(GlyphRasterizer.LINEJOIN_BEVEL),
        /**
         * The outer edges of a join meet at a sharp angle.
         */
        MITER(GlyphRasterizer.LINEJOIN_MITER),
        /**
         * The outer edges of a join meet in a circular arc.
         */
        ROUND(GlyphRasterizer.LINEJOIN_ROUND);

        private final int value;

        Join(int value) {
            this.value = value;
        }
    }

    /**
     * Specifies if the primitive being drawn is filled, stroked, or both.
     */
    public enum Style {
        /**
         * Glyphs drawn with this style will be filled, ignoring all stroke-related settings in the
         * renderer.
         */
        FILL,
        /**
         * Glyphs drawn with this style will be both filled and stroked at the same time, respecting
         * the stroke-related settings in the renderer.
         */
        FILL_STROKE,
        /**
         * Glyphs drawn with this style will be stroked, respecting the stroke-related settings in
         * the renderer.
         */
        STROKE,
    }

    private GlyphStrike mGlyphStrike;
    private int mGlyphLineRadius;
    private int mGlyphLineCap;
    private int mGlyphLineJoin;
    private int mGlyphMiterLimit;

    private Paint mPaint;
    private boolean mShouldRender;
    private boolean mShadowLayerSynced;

    private int mFillColor;
    private Style mStyle;
    private WritingDirection mWritingDirection;
    private Typeface mTypeface;
    private float mTypeSize;
    private float mSlantAngle;
    private float mScaleX;
    private float mScaleY;
    private int mStrokeColor;
    private float mStrokeWidth;
    private Cap mStrokeCap;
    private Join mStrokeJoin;
    private float mStrokeMiter;
    private float mShadowRadius;
    private float mShadowDx;
    private float mShadowDy;
    private int mShadowColor;

    /**
     * Constructs a renderer object.
     */
    public Renderer() {
        mGlyphStrike = new GlyphStrike();
        mPaint = new Paint();
        mShadowRadius = 0.0f;
        mShadowDx = 0.0f;
        mShadowDy = 0.0f;
        mShadowColor = Color.TRANSPARENT;

        setFillColor(Color.BLACK);
        setStyle(Style.FILL);
        setWritingDirection(WritingDirection.LEFT_TO_RIGHT);
        setTypeSize(16.0f);
        setSlantAngle(0.0f);
        setScaleX(1.0f);
        setScaleY(1.0f);
        setStrokeColor(Color.BLACK);
        setStrokeWidth(1.0f);
        setStrokeCap(Cap.BUTT);
        setStrokeJoin(Join.ROUND);
        setStrokeMiter(1.0f);
    }

    private void updatePixelSizes() {
        int pixelWidth = (int) ((mTypeSize * mScaleX * 64.0f) + 0.5f);
        int pixelHeight = (int) ((mTypeSize * mScaleY * 64.0f) + 0.5f);

        // Minimum size supported by Freetype is 64x64.
        mShouldRender = (pixelWidth >= 64 && pixelHeight >= 64);
        mGlyphStrike.pixelWidth = pixelWidth;
        mGlyphStrike.pixelHeight = pixelHeight;
    }

    private void updateTransform() {
        mGlyphStrike.skewX = (int) ((mSlantAngle * 0x10000) + 0.5f);
    }

    private void syncShadowLayer() {
        if (!mShadowLayerSynced) {
            mShadowLayerSynced = true;
            mPaint.setShadowLayer(mShadowRadius, mShadowDx, mShadowDy, mShadowColor);
        }
    }

    /**
     * Returns this renderer's text color, used for filling glyphs. The default value is
     * <code>Color.BLACK</code>.
     *
     * @return The text color of this renderer expressed as ARGB integer.
     */
    public int getFillColor() {
        return mFillColor;
    }

    /**
     * Sets this renderer's text color, used for filling glyphs. The default value is
     * <code>Color.BLACK</code>.
     *
     * @param textColor The 32-bit value of color expressed as ARGB.
     */
    public void setFillColor(int textColor) {
        mFillColor = textColor;
    }

    /**
     * Returns this renderer's style, used for controlling how glyphs should appear while drawing.
     * The default value is {@link Style#FILL}.
     *
     * @return The style setting of this renderer.
     */
    public Style getStyle() {
        return mStyle;
    }

    /**
     * Sets this renderer's style, used for controlling how glyphs should appear while drawing. The
     * default value is {@link Style#FILL}.
     *
     * @param renderingStyle The new style to set in the renderer.
     *
     * @throws NullPointerException if <code>renderingStyle</code> is null.
     */
    public void setStyle(Style renderingStyle) {
        if (renderingStyle == null) {
            throw new NullPointerException("Rendering style is null");
        }

        mStyle = renderingStyle;
    }

    /**
     * Returns the direction in which the pen will advance after drawing a glyph. The default value
     * is {@link WritingDirection#LEFT_TO_RIGHT}.
     *
     * @return The current writing direction.
     */
    public WritingDirection getWritingDirection() {
        return mWritingDirection;
    }

    /**
     * Sets the direction in which the pen will advance after drawing a glyph. The default value is
     * {@link WritingDirection#LEFT_TO_RIGHT}.
     *
     * @param writingDirection The new writing direction.
     */
    public void setWritingDirection(WritingDirection writingDirection) {
        if (writingDirection == null) {
            throw new NullPointerException("Writing direction is null");
        }

        mWritingDirection = writingDirection;
    }

    /**
     * Returns this renderer's typeface, used for drawing glyphs.
     *
     * @return The typeface of this renderer.
     */
    public Typeface getTypeface() {
        return mTypeface;
    }

    /**
     * Sets this renderer's typeface, used for drawing glyphs.
     *
     * @param typeface The typeface to use for drawing glyphs.
     */
    public void setTypeface(Typeface typeface) {
        mTypeface = typeface;
        mGlyphStrike.typeface = typeface;
    }

    /**
     * Returns this renderer's text size, applied on glyphs while drawing.
     *
     * @return The text size of this renderer in pixels.
     */
    public float getTypeSize() {
        return mTypeSize;
    }

    /**
     * Sets this renderer's text size, applied on glyphs while drawing.
     *
     * @param textSize The new text size in pixels.
     *
     * @throws IllegalArgumentException if <code>textSize</code> is negative.
     */
    public void setTypeSize(float textSize) {
        if (textSize < 0.0) {
            throw new IllegalArgumentException("The value of text size is negative");
        }

        mTypeSize = textSize;
        updatePixelSizes();
    }

    /**
     * Returns this renderer's horizontal skew factor for glyphs. The default value is 0.
     *
     * @return The horizontal skew factor of this renderer for drawing glyphs.
     */
    public float getSlantAngle() {
        return mSlantAngle;
    }

    /**
     * Sets this renderer's horizontal skew factor for glyphs. The default value is 0.
     *
     * @param textSkewX The horizontal skew factor for drawing glyphs.
     */
    public void setSlantAngle(float textSkewX) {
        mSlantAngle = textSkewX;
        updateTransform();
    }

    /**
     * Returns this renderer's horizontal scale factor for glyphs. The default value is 1.0.
     *
     * @return The horizontal scale factor of this renderer for drawing/measuring glyphs.
     */
    public float getScaleX() {
        return mScaleX;
    }

    /**
     * Sets this renderer's horizontal scale factor for glyphs. The default value is 1.0. Values
     * greater than 1.0 will stretch the glyphs wider. Values less than 1.0 will stretch the glyphs
     * narrower.
     *
     * @param textScaleX The horizontal scale factor for drawing/measuring glyphs.
     */
    public void setScaleX(float textScaleX) {
        if (textScaleX < 0.0f) {
            throw new IllegalArgumentException("Scale value is negative");
        }

        mScaleX = textScaleX;
        updatePixelSizes();
    }

    /**
     * Returns this renderer's vertical scale factor for glyphs. The default value is 1.0.
     *
     * @return The vertical scale factor of this renderer for drawing/measuring glyphs.
     */
    public float getScaleY() {
        return mScaleY;
    }

    /**
     * Sets this renderer's vertical scale factor for glyphs. The default value is 1.0. Values
     * greater than 1.0 will stretch the glyphs wider. Values less than 1.0 will stretch the glyphs
     * narrower.
     *
     * @param textScaleY The vertical scale factor for drawing/measuring glyphs.
     */
    public void setScaleY(float textScaleY) {
        if (textScaleY < 0.0f) {
            throw new IllegalArgumentException("Scale value is negative");
        }

        mScaleY = textScaleY;
        updatePixelSizes();
    }

    /**
     * Returns this renderer's stroke color for glyphs. The default value is
     * <code>Color.BLACK</code>.
     *
     * @return The stroke color of this renderer expressed as ARGB integer.
     */
    public int getStrokeColor() {
        return mStrokeColor;
    }

    /**
     * Sets this renderer's stroke color for glyphs. The default value is <code>Color.BLACK</code>.
     *
     * @param strokeColor The 32-bit value of color expressed as ARGB.
     */
    public void setStrokeColor(int strokeColor) {
        mStrokeColor = strokeColor;
    }

    /**
     * Returns this renderer's width for stroking glyphs.
     *
     * @return The stroke width of this renderer in pixels.
     */
    public float getStrokeWidth() {
        return mStrokeWidth;
    }

    /**
     * Sets the width for stroking glyphs.
     *
     * @param strokeWidth The stroke width in pixels.
     */
    public void setStrokeWidth(float strokeWidth) {
        if (strokeWidth < 0.0f) {
            throw new IllegalArgumentException("Stroke width is negative");
        }

        mStrokeWidth = strokeWidth;
        mGlyphLineRadius = (int) ((strokeWidth * 64.0f / 2.0f) + 0.5f);
    }

    /**
     * Returns this renderer's cap, controlling how the start and end of stroked lines and paths are
     * treated. The default value is {@link Cap#BUTT}.
     *
     * @return The stroke cap style of this renderer.
     */
    public Cap getStrokeCap() {
        return mStrokeCap;
    }

    /**
     * Sets this renderer's cap, controlling how the start and end of stroked lines and paths are
     * treated. The default value is {@link Cap#BUTT}.
     *
     * @param strokeCap The new stroke cap style.
     *
     * @throws NullPointerException if <code>strokeCap</code> is null.
     */
    public void setStrokeCap(Cap strokeCap) {
        if (strokeCap == null) {
            throw new NullPointerException("Stroke cap is null");
        }

        mStrokeCap = strokeCap;
        mGlyphLineCap = strokeCap.value;
    }

    /**
     * Returns this renderer's stroke join type. The default value is {@link Join#ROUND}.
     *
     * @return The stroke join type of this renderer.
     */
    public Join getStrokeJoin() {
        return mStrokeJoin;
    }

    /**
     * Sets this renderer's stroke join type. The default value is {@link Join#ROUND}.
     *
     * @param strokeJoin The new stroke join type.
     *
     * @throws NullPointerException if <code>strokeJoin</code> is null.
     */
    public void setStrokeJoin(Join strokeJoin) {
        if (strokeJoin == null) {
            throw new NullPointerException("Stroke join is null");
        }

        mStrokeJoin = strokeJoin;
        mGlyphLineJoin = strokeJoin.value;
    }

    /**
     * Returns this renderer's stroke miter value. Used to control the behavior of miter joins when
     * the joins angle is sharp.
     *
     * @return The miter limit of this renderer in pixels.
     */
    public float getStrokeMiter() {
        return mStrokeMiter;
    }

    /**
     * Sets this renderer's stroke miter value. This is used to control the behavior of miter joins
     * when the joins angle is sharp.
     *
     * @param strokeMiter The value of miter limit in pixels.
     *
     * @throws IllegalArgumentException if <code>strokeMiter</code> is less than one.
     */
    public void setStrokeMiter(float strokeMiter) {
        if (strokeMiter < 1.0f) {
            throw new IllegalArgumentException("Stroke miter is less than one");
        }

        mStrokeMiter = strokeMiter;
        mGlyphMiterLimit = (int) ((strokeMiter * 0x10000) + 0.5f);
    }

    /**
     * Returns this renderer's shadow radius, used when drawing glyphs. The default value is zero.
     *
     * @return The shadow radius of this renderer in pixels.
     */
    public float getShadowRadius() {
        return mShadowRadius;
    }

    /**
     * Sets this renderer's shadow radius. The default value is zero. The shadow is disabled if the
     * radius is set to zero.
     *
     * @param shadowRadius The value of shadow radius in pixels.
     *
     * @throws IllegalArgumentException if <code>shadowRadius</code> is negative.
     */
    public void setShadowRadius(float shadowRadius) {
        if (shadowRadius < 0.0f) {
            throw new IllegalArgumentException("Shadow radius is negative");
        }

        mShadowRadius = shadowRadius;
        mShadowLayerSynced = false;
    }

    /**
     * Returns this renderer's horizontal shadow offset.
     *
     * @return The horizontal shadow offset of this renderer in pixels.
     */
    public float getShadowDx() {
        return mShadowDx;
    }

    /**
     * Sets this renderer's horizontal shadow offset.
     *
     * @param shadowDx The value of horizontal shadow offset in pixels.
     */
    public void setShadowDx(float shadowDx) {
        mShadowDx = shadowDx;
        mShadowLayerSynced = false;
    }

    /**
     * Returns this renderer's vertical shadow offset.
     *
     * @return The vertical shadow offset of this renderer in pixels.
     */
    public float getShadowDy() {
        return mShadowDy;
    }

    /**
     * Sets this renderer's vertical shadow offset.
     *
     * @param shadowDy The value of vertical shadow offset in pixels.
     */
    public void setShadowDy(float shadowDy) {
        mShadowDy = shadowDy;
        mShadowLayerSynced = false;
    }

    /**
     * Returns this renderer's shadow color.
     *
     * @return The shadow color of this renderer expressed as ARGB integer.
     */
    public int getShadowColor() {
        return mShadowColor;
    }

    /**
     * Sets this renderer's shadow color.
     *
     * @param shadowColor The 32-bit value of color expressed as ARGB.
     */
    public void setShadowColor(int shadowColor) {
        mShadowColor = shadowColor;
        mShadowLayerSynced = false;
    }

    private Path getGlyphPath(int glyphId) {
        return GlyphCache.getInstance().getGlyphPath(mGlyphStrike, glyphId);
    }

    /**
     * Generates the path of the specified glyph.
     *
     * @param glyphId The ID of glyph whose path is generated.
     * @return The path of the glyph specified by <code>glyphId</code>.
     */
    public Path generatePath(int glyphId) {
        Path glyphPath = new Path();
        glyphPath.addPath(getGlyphPath(glyphId));

        return glyphPath;
    }

    /**
     * Generates a cumulative path of the glyphs in specified range.
     *
     * @param glyphIds The list containing the glyph IDs.
     * @param offsets The list containing the glyph offsets.
     * @param advances The list containing the glyph advances.
     * @return The cumulative path of the glyphs in specified range.
     */
    public Path generatePath(IntList glyphIds, PointList offsets, FloatList advances) {
        Path cumulativePath = new Path();
        float penX = 0.0f;

        int size = glyphIds.size();

        for (int i = 0; i < size; i++) {
            int glyphId = glyphIds.get(i);
            float xOffset = offsets.getX(i) * mScaleX;
            float yOffset = offsets.getY(i) * mScaleY;
            float advance = advances.get(i) * mScaleX;

            Path glyphPath = getGlyphPath(glyphId);
            cumulativePath.addPath(glyphPath, penX + xOffset, yOffset);

            penX += advance;
        }

        return cumulativePath;
    }

    private void getBoundingBox(int glyphId, RectF boundingBox) {
        Glyph glyph = GlyphCache.getInstance().getMaskGlyph(mGlyphStrike, glyphId);
        boundingBox.set(glyph.leftSideBearing(), glyph.topSideBearing(),
                        glyph.rightSideBearing(), glyph.bottomSideBearing());
    }

    /**
     * Calculates the bounding box of specified glyph.
     *
     * @param glyphId The ID of glyph whose bounding box is calculated.
     * @return A rectangle that tightly encloses the path of the specified glyph.
     */
    public RectF computeBoundingBox(int glyphId) {
        RectF boundingBox = new RectF();
        getBoundingBox(glyphId, boundingBox);

        return boundingBox;
    }

    /**
     * Calculates the bounding box of the glyphs in specified range.
     *
     * @param glyphIds The list containing the glyph IDs.
     * @param offsets The list containing the glyph offsets.
     * @param advances The list containing the glyph advances.
     * @return A rectangle that tightly encloses the paths of glyphs in the specified range.
     */
    public RectF computeBoundingBox(IntList glyphIds, PointList offsets, FloatList advances) {
        RectF glyphBBox = new RectF();
        RectF cumulativeBBox = new RectF();
        float penX = 0.0f;

        int size = glyphIds.size();

        for (int i = 0; i < size; i++) {
            int glyphId = glyphIds.get(i);
            float xOffset = offsets.getX(i) * mScaleX;
            float yOffset = offsets.getY(i) * mScaleY;
            float advance = advances.get(i) * mScaleX;

            getBoundingBox(glyphId, glyphBBox);
            glyphBBox.offset(penX + xOffset, yOffset);
            cumulativeBBox.union(cumulativeBBox);

            penX += advance;
        }

        return cumulativeBBox;
    }

    private void drawGlyphs(Canvas canvas,
                            IntList glyphIds, PointList offsets, FloatList advances,
                            boolean strokeMode) {
        GlyphCache cache = GlyphCache.getInstance();
        boolean reverseMode = (mWritingDirection == WritingDirection.RIGHT_TO_LEFT);
        float penX = 0.0f;

        int size = glyphIds.size();

        for (int i = 0; i < size; i++) {
            int pos = (!reverseMode ? i : (size - i) - 1);

            int glyphId = glyphIds.get(pos);
            float xOffset = offsets.getX(pos) * mScaleX;
            float yOffset = offsets.getY(pos) * mScaleY;
            float advance = advances.get(pos) * mScaleX;

            Glyph maskGlyph = (!strokeMode
                               ? cache.getMaskGlyph(mGlyphStrike, glyphId)
                               : cache.getMaskGlyph(mGlyphStrike, glyphId, mGlyphLineRadius,
                                                    mGlyphLineCap, mGlyphLineJoin, mGlyphMiterLimit));
            Bitmap maskBitmap = maskGlyph.bitmap();
            if (maskBitmap != null) {
                int left = (int) (penX + xOffset + maskGlyph.leftSideBearing() + 0.5f);
                int top = (int) (-yOffset - maskGlyph.topSideBearing() + 0.5f);

                canvas.drawBitmap(maskBitmap, left, top, mPaint);
            }

            penX += advance;
        }
    }

    /**
     * Draws the glyphs in specified range onto the given canvas. The shadow will not be drawn if
     * the canvas is hardware accelerated.
     *
     * @param canvas The canvas onto which to draw the glyphs.
     * @param glyphIds The list containing the glyph IDs.
     * @param offsets The list containing the glyph offsets.
     * @param advances The list containing the glyph advances.
     */
    public void drawGlyphs(Canvas canvas,
                           IntList glyphIds, PointList offsets, FloatList advances) {
        if (mShouldRender) {
            syncShadowLayer();

            if (mShadowRadius > 0.0f && canvas.isHardwareAccelerated()) {
                Log.e(TAG, "Canvas is hardware accelerated, shadow will not be rendered");
            }

            if (mStyle == Style.FILL || mStyle == Style.FILL_STROKE) {
                mPaint.setColor(mFillColor);
                drawGlyphs(canvas, glyphIds, offsets, advances, false);
            }

            if (mStyle == Style.STROKE || mStyle == Style.FILL_STROKE) {
                mPaint.setColor(mStrokeColor);
                drawGlyphs(canvas, glyphIds, offsets, advances, true);
            }
        }
    }
}
