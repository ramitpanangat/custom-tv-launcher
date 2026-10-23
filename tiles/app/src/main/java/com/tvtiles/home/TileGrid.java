package com.tvtiles.home;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays its children out on a grid UNITS wide, each child taking the units of its TileSize. Tiles are placed in
 * child order, each at the first free spot reading left to right, top to bottom, so a small or medium tile fills
 * a gap a wide tile left behind, as on the Windows Phone start screen.
 */
final class TileGrid extends ViewGroup {
    static final int UNITS = 10;

    private final int gap;
    private int unit;
    /** How far the tiles sit below the top: half-ish of the spare height when they don't fill the screen. */
    private int yOffset;
    /** Told whenever yOffset changes, so the header above can move down with the tiles. */
    interface OffsetListener { void onOffset(int yOffset); }
    private OffsetListener offsetListener;
    private int reportedOffset = -1;

    void setOffsetListener(OffsetListener listener) {
        offsetListener = listener;
        reportedOffset = -1;
    }
    /** Each child's place, in grid units (left, top, right, bottom), in child order. */
    private final List<Rect> cells = new ArrayList<>();

    TileGrid(Context context) {
        super(context);
        gap = Math.round(8 * context.getResources().getDisplayMetrics().density);
        setClipChildren(false);
        setClipToPadding(false);
    }

    static final class LayoutParams extends ViewGroup.LayoutParams {
        final TileSize size;

        LayoutParams(TileSize size) {
            super(WRAP_CONTENT, WRAP_CONTENT);
            this.size = size;
        }
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    /** The grid place of child i, valid after a layout pass; null before. */
    Rect cellAt(int i) {
        return i >= 0 && i < cells.size() ? cells.get(i) : null;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int inner = width - getPaddingLeft() - getPaddingRight();
        unit = Math.max(1, (inner - gap * (UNITS - 1)) / UNITS);
        pack();
        int rows = 0;
        for (int i = 0; i < getChildCount(); i++) {
            Rect r = cells.get(i);
            rows = Math.max(rows, r.bottom);
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(span(r.width()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(span(r.height()), MeasureSpec.EXACTLY));
        }
        int height = (rows == 0 ? 0 : span(rows)) + getPaddingTop() + getPaddingBottom();
        // The ScrollView (fillViewport) asks for exactly the screen's height when the tiles are shorter than it:
        // take it and sit the tiles a little above the middle (optical centre), not pinned to the top.
        int available = MeasureSpec.getSize(heightSpec);
        if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY && available > height) {
            yOffset = Math.round((available - height) * 0.45f);
            setMeasuredDimension(width, available);
        } else {
            yOffset = 0;
            setMeasuredDimension(width, height);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (offsetListener != null && yOffset != reportedOffset) {
            reportedOffset = yOffset;
            offsetListener.onOffset(yOffset);
        }
        for (int i = 0; i < getChildCount(); i++) {
            Rect c = cells.get(i);
            int left = getPaddingLeft() + c.left * (unit + gap);
            int top = yOffset + getPaddingTop() + c.top * (unit + gap);
            View v = getChildAt(i);
            v.layout(left, top, left + v.getMeasuredWidth(), top + v.getMeasuredHeight());
        }
    }

    private int span(int units) {
        return units * unit + (units - 1) * gap;
    }

    private void pack() {
        cells.clear();
        List<boolean[]> taken = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            TileSize s = ((LayoutParams) getChildAt(i).getLayoutParams()).size;
            Rect place = null;
            for (int row = 0; place == null; row++) {
                for (int col = 0; col + s.w <= UNITS; col++) {
                    if (free(taken, row, col, s)) {
                        place = new Rect(col, row, col + s.w, row + s.h);
                        break;
                    }
                }
            }
            for (int y = place.top; y < place.bottom; y++) {
                for (int x = place.left; x < place.right; x++) taken.get(y)[x] = true;
            }
            cells.add(place);
        }
    }

    private static boolean free(List<boolean[]> taken, int row, int col, TileSize s) {
        while (taken.size() < row + s.h) taken.add(new boolean[UNITS]);
        for (int y = row; y < row + s.h; y++) {
            for (int x = col; x < col + s.w; x++) if (taken.get(y)[x]) return false;
        }
        return true;
    }
}
