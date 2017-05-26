package org.bitbucket.nikcody92.scalableimageview;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.v7.widget.AppCompatImageView;
import android.util.AttributeSet;
import android.view.View;



/**
 * View for image capable of scaling its content to a defined ratio and focusing a defined area.
 */
public class ScalableImageView extends AppCompatImageView {

    private float scaleFactor;
    private float pivotX, pivotY;
    private ScaleType scaleType;
    private OnScaleImageListener scaleListener;

    private void initImage() {
        scaleFactor = 1;
        pivotX = 0.5f;
        pivotY = 0.5f;
        scaleType = ScaleType.CENTER;
        scaleListener = null;
    }

    public ScalableImageView(Context context) {
        super(context);
        initImage();
    }

    public ScalableImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScalableImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initImage();
        TypedArray attrArray = context.getTheme().obtainStyledAttributes(attrs, R.styleable.ScalableImageView, defStyleAttr, 0);
        try {
            this.scaleFactor = attrArray.getFloat(R.styleable.ScalableImageView_scale, 1);
            this.pivotX = attrArray.getFraction(R.styleable.ScalableImageView_scalePivotX, 1, 1, 0.5f);
            this.pivotY = attrArray.getFraction(R.styleable.ScalableImageView_scalePivotY, 1, 1, 0.5f);
            if (this.pivotX < 0 || this.pivotX > 1 || this.pivotY < 0 || this.pivotY > 1)
                throw new UnsupportedOperationException("Invalid value for pivot attribute used for scaling.");
        } finally {
            attrArray.recycle();
        }
        scaleType = getScaleType();     // save scale type in order to make it effective later
        setScaleType(ScaleType.MATRIX);
        addOnLayoutChangeListener(new ApplyInflatedScaleType());
        addOnLayoutChangeListener(new OnImageBoundsChanged());
    }

    /**
     * Get a predefined width for represented content.
     * If it has not any defined size, return screen width.
     */
    public int getNaturalWidth() {
        int width = getDrawable().getIntrinsicWidth();
        if (width == -1)
            width = getResources().getDisplayMetrics().widthPixels;
        return width;
    }

    /**
     * Get a predefined height for represented content.
     * If it has not any defined size, return screen height.
     */
    public int getNaturalHeight() {
        int height = getDrawable().getIntrinsicHeight();
        if (height == -1)
            height = getResources().getDisplayMetrics().heightPixels;
        return height;
    }

    public static class PivotPoint implements Parcelable {
        public float x, y;

        public static final Parcelable.Creator<PivotPoint> CREATOR = new Parcelable.Creator<PivotPoint>() {
            @Override
            public PivotPoint createFromParcel(Parcel source) {
                PivotPoint point = new PivotPoint();
                point.x = source.readFloat();
                point.y = source.readFloat();
                return point;
            }

            @Override
            public PivotPoint[] newArray(int size) {
                return new PivotPoint[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeFloat(x);
            dest.writeFloat(y);
        }
    }

    /**
     * Retrieve coordinates for pivot point used for scaling, relative to the upper-left corner of the whole image.
     *
     * @return A custom representation of the pivot point.
     */
    public PivotPoint getPivot() {
        PivotPoint pivot = new PivotPoint();
        pivot.x = pivotX * getNaturalWidth() * scaleFactor;
        pivot.y = pivotY * getNaturalHeight() * scaleFactor;
        return pivot;
    }

    /**
     * An internal representation of ranges for pivot values along both coordinates.
     */
    private class PivotRange {
        public float minPivotX, maxPivotX, minPivotY, maxPivotY;

        public PivotRange() {
            this(0, 0, 0, 0);
        }

        public PivotRange(float minPX, float maxPX, float minPY, float maxPY) {
            this.minPivotX = minPX;
            this.maxPivotX = maxPX;
            this.minPivotY = minPY;
            this.maxPivotY = maxPY;
        }
    }

    /**
     * Retrieve minimum values for pivot coordinates, so that pivot does not lie close to a border of the framed area.
     *
     * @return An internal represetation for ranges.
     */
    private PivotRange getPivotRange() {
        PivotRange range = new PivotRange();
        float centerX, centerY;
        float naturalPaddingX, naturalPaddingY;
        centerX = getWidth() * 0.5f;
        centerY = getHeight() * 0.5f;
        naturalPaddingX = centerX / (getNaturalWidth() * scaleFactor);
        naturalPaddingY = centerY / (getNaturalHeight() * scaleFactor);
        if (naturalPaddingX > 0.5f)
            naturalPaddingX = 0.5f;
        if (naturalPaddingY > 0.5f)
            naturalPaddingY = 0.5f;
        range.minPivotX = naturalPaddingX;
        range.maxPivotX = 1 - naturalPaddingX;
        range.minPivotY = naturalPaddingY;
        range.maxPivotY = 1 - naturalPaddingY;
        return range;
    }

    /**
     * Correct pivot value in case of excessive movements.
     * @param centered Whether pivot point has to be forced away from any border of the framed area.
     */
    public void correctPivot(boolean centered) {
        PivotRange range;
        if (centered)
            range = getPivotRange();
        else
            range = new PivotRange(0, 1, 0, 1);
        if (pivotX < range.minPivotX)
            pivotX = range.minPivotX;
        else if (pivotX > range.maxPivotX)
            pivotX = range.maxPivotX;
        if (pivotY < range.minPivotY)
            pivotY = range.minPivotY;
        else if (pivotY > range.maxPivotY)
            pivotY = range.maxPivotY;
    }

    /**
     * Perform scaling on the content and make it effective on the next drawing operation.
     */
    private void updateImageTransformation() {
        Matrix scaling = new Matrix(getImageMatrix());
        int naturalWidth, naturalHeight, width, height;
        float px, py;
        naturalWidth = getNaturalWidth();
        naturalHeight = getNaturalHeight();
        width = getMeasuredWidth();     // effective dimensions of the rendered image
        height = getMeasuredHeight();
        if (width >= naturalWidth * scaleFactor)                // for a scaled image littler than available space
            px = (width - naturalWidth * scaleFactor) * 0.5f;   // center it anyway
        else {
            px = width * 0.5f - naturalWidth * scaleFactor * pivotX;
            if (px > 0)     // avoid upper cropping effect for scaled content
                px = 0;
            else if (px + naturalWidth * scaleFactor < width)   // avoid lower cropping effect for scaled content
                px = width - naturalWidth * scaleFactor;
        }
        if (height >= naturalHeight * scaleFactor)              // for a scaled image littler than available space
            py = (height - naturalHeight * scaleFactor) * 0.5f; // center it anyway
        else {
            py = height * 0.5f - naturalHeight * scaleFactor * pivotY;
            if (py > 0)     // avoid upper cropping effect for scaled content
                py = 0;
            else if (py + naturalHeight * scaleFactor < height) // avoid lower cropping effect for scaled content
                py = height - naturalHeight * scaleFactor;
        }
        scaling.setScale(scaleFactor, scaleFactor);
        scaling.postTranslate(px, py);
        setImageMatrix(scaling);
        if (scaleListener != null)
            scaleListener.onScaleImage();
    }

    public void setScaleFactor(float newScale) {
        scaleFactor = newScale;
        updateImageTransformation();
    }

    public float getScaleFactor() {
        return scaleFactor;
    }

    /**
     * On changes to our layout, according changes must be done on the view.
     */
    private class OnImageBoundsChanged implements OnLayoutChangeListener {

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
            updateImageTransformation();
        }
    }

    /**
     * Adapt given scale type to the scaling model by setting a fit scale factor.
     * For now, the only supported types are CENTER, CENTER_INSIDE and CENTER_CROP.
     */
    private class ApplyInflatedScaleType implements OnLayoutChangeListener {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
            removeOnLayoutChangeListener(this);     // scale type is effective just at the beginning
            if (scaleType != ScaleType.CENTER && scaleType != ScaleType.MATRIX)      // CENTER scale type should imply no scaling and MATRIX is used anyway
                setScaleFactor(getScaleTypeFactor(scaleType));
            scaleType = ScaleType.MATRIX;
        }
    }

    /**
     * Interface for the listener called on changes regarding the scaled image and its presentation.
     */
    public interface OnScaleImageListener {
        void onScaleImage();
    }

    /**
     * Set the listener called on changes regarding the scaled image and its presentation.
     */
    public void setOnScaleImageListener(OnScaleImageListener listener) {
        scaleListener = listener;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        updateImageTransformation();
    }

    /**
     * Translate pivot point for scaling by the given amount in order to move the framed area.
     *
     * @param deltaX      Horizontal amount.
     * @param deltaY      Vertical amount.
     * @param anchored Whether pivot must be corrected so that image stays anchored to one of its borders.
     */
    public void translatePivot(float deltaX, float deltaY, boolean anchored) {
        pivotX += deltaX / (getNaturalWidth() * scaleFactor);
        pivotY += deltaY / (getNaturalHeight() * scaleFactor);
        correctPivot(anchored);
        updateImageTransformation();
    }

    /**
     * Retrieve a scale factor that may be used to apply the given scale type after the starting phase.
     *
     * @param type The desired scale type.
     * @return A scale factor representing the given scale type as much as possible.
     */
    public float getScaleTypeFactor(ScaleType type) {
        float scale = 1;
        float ratioX, ratioY;
        ratioX = getWidth() / (float) getNaturalWidth();
        ratioY = getHeight() / (float) getNaturalHeight();
        if (type == ScaleType.CENTER)
            scale = 1;
        else if (type == ScaleType.CENTER_INSIDE)
            scale = Math.min(ratioX, ratioY);
        else if (type == ScaleType.CENTER_CROP)
            scale = Math.max(ratioX, ratioY);
        return scale;
    }

}
