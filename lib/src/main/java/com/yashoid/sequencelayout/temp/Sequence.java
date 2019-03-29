package com.yashoid.sequencelayout.temp;

import android.content.Context;
import android.content.res.Resources;
import android.util.SparseIntArray;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class Sequence {

    public static class SequenceEdge {

        public int targetId;
        public float portion;

        public SequenceEdge(String definition, Context context) {
            int atIndex = definition.indexOf("@");

            String targetRawId = definition.substring(atIndex + 1);

            if (targetRawId.isEmpty()) {
                targetId = 0;
            }
            else {
                targetId = SizeInfo.resolveViewId(targetRawId, context);
            }

            String portion = definition.substring(0, atIndex);

            switch (portion) {
                case "start":
                    this.portion = 0;
                    break;
                case "end":
                    this.portion = 1;
                    break;
                default:
                    this.portion = Float.parseFloat(portion) / 100f;
                    break;
            }
        }

        protected int resolve(Sequence sequence, int totalSize, ResolutionBox resolvedSizes) {
            if (targetId == 0) {
                return (int) (totalSize * portion);
            }

            ResolveUnit resolveUnit = resolvedSizes.find(targetId, sequence.mIsHorizontal);

            if (resolveUnit == null) {
                return -1;
            }

            if (!resolveUnit.isPositionSet()) {
                return -1;
            }

            return (int) (resolveUnit.getStart() + portion * (resolveUnit.getEnd() - resolveUnit.getStart()));
        }

    }

    private String mId;

    private boolean mIsHorizontal;

    private SequenceEdge mStart;
    private SequenceEdge mEnd;

    private View mView;

    private List<SizeInfo> mSizeInfo = new ArrayList<>();
    private SparseIntArray mMeasuredSizes = new SparseIntArray(12);

    private SizeResolver mSizeResolver;

    public Sequence(String id, boolean isHorizontal, String start, String end, Context context) {
        this(id, isHorizontal, new SequenceEdge(start, context), new SequenceEdge(end, context));
    }

    public Sequence(String id, boolean isHorizontal, SequenceEdge start, SequenceEdge end) {
        mId = id;

        mIsHorizontal = isHorizontal;

        mStart = start;
        mEnd = end;

        mSizeResolver = new SizeResolver();
    }

    public String getId() {
        return mId;
    }

    public boolean isHorizontal() {
        return mIsHorizontal;
    }

    public void addSizeInfo(SizeInfo sizeInfo) {
        mSizeInfo.add(sizeInfo);
    }

    public List<SizeInfo> getSizeInfo() {
        return mSizeInfo;
    }

    public void setup(View view, PageSizeProvider sizeProvider) {
        mView = view;

        mSizeResolver.setup(view, Resources.getSystem().getDisplayMetrics(), sizeProvider);
    }

    protected View getView() {
        return mView;
    }

    public int resolve(ResolutionBox resolvedUnits, ResolutionBox unresolvedUnits, int maxWidth, int maxHeight, boolean wrapping) {
        int totalSize = mIsHorizontal ? maxWidth : maxHeight;

        int start = mStart.resolve(this, totalSize, resolvedUnits);
        int end = mEnd.resolve(this, totalSize, resolvedUnits);

        if (start < 0 || end < 0) {
            for (int index = 0; index < mSizeInfo.size(); index++) {
                SizeInfo sizeInfo = mSizeInfo.get(index);

                if (sizeInfo instanceof Element) {
                    Element element = (Element) sizeInfo;

                    if (element.isStatic()) {
                        ResolveUnit unit = unresolvedUnits.take(element.elementId, mIsHorizontal);

                        if (unit != null) {
                            unit.setSize(mSizeResolver.resolveSize(element, mIsHorizontal));

                            resolvedUnits.add(unit);
                        }
                    }
                }
            }

            return -1;
        }

        if (end < start) {
            end = start;
        }

        totalSize = end - start;

        float weightSum = 0;
        int calculatedSize = 0;
        int currentPosition = start;

        mMeasuredSizes.clear();

        mSizeResolver.setResolutionInfo(resolvedUnits, unresolvedUnits, totalSize, maxWidth, maxHeight);

        boolean hasUnresolvedSizes = false;
        boolean hasEncounteredPositionResolutionGap = false;

        for (int index = 0; index < mSizeInfo.size(); index++) {
            SizeInfo sizeInfo = mSizeInfo.get(index);

            if (sizeInfo.metric != SizeInfo.METRIC_WEIGHT) {
                int size = mSizeResolver.resolveSize(sizeInfo, mIsHorizontal);

                if (size != -1) {
                    mMeasuredSizes.put(index, size);
                    calculatedSize += size;

                    mSizeResolver.setCurrentPosition(calculatedSize);

                    ResolveUnit unit = null;

                    if (sizeInfo.elementId != 0) {
                        unit = unresolvedUnits.take(sizeInfo.elementId, mIsHorizontal);

                        if (unit != null) {
                            unit.setSize(size);

                            resolvedUnits.add(unit);
                        }
                    }

                    if (!hasEncounteredPositionResolutionGap) {
                        if (unit != null) {
                            unit.setStart(currentPosition);
                            unit.setEnd(currentPosition + size);
                        }

                        currentPosition += size;
                    }
                }
                else {
                    hasUnresolvedSizes |= size == -1;
                    hasEncounteredPositionResolutionGap = true;
                    mSizeResolver.setHasEncounteredPositionResolutionGap(true);
                }
            }
            else {
                hasEncounteredPositionResolutionGap = true;
                mSizeResolver.setHasEncounteredPositionResolutionGap(true);

                weightSum += sizeInfo.size;

                mMeasuredSizes.put(index, SizeInfo.SIZE_WEIGHTED);
            }
        }

        if (hasUnresolvedSizes) {
            mSizeResolver.reset();
            return -1;
        }

        if (hasEncounteredPositionResolutionGap) {
            int remainingSize = totalSize - calculatedSize;
            float remainingWeight = weightSum;

            calculatedSize = 0;

            currentPosition = start;

            for (int index = 0; index < mSizeInfo.size(); index++) {
                SizeInfo sizeInfo = mSizeInfo.get(index);

                int size = mMeasuredSizes.get(index, -1);

                if (size == SizeInfo.SIZE_WEIGHTED) {
                    if (!wrapping) {
                        size = (int) (sizeInfo.size * remainingSize / remainingWeight);
                    }
                    else {
                        size = 0;

                        if (sizeInfo instanceof Space) {
                            Space space = (Space) sizeInfo;

                            if (space.min != null) {
                                size = mSizeResolver.resolveSize(space.min, mIsHorizontal);
                            }

                            if (size < 0) {
                                mSizeResolver.reset();

                                return -1;
                            }
                        }
                    }

                    remainingWeight -= sizeInfo.size;
                    remainingSize -= size;
                }

                if (sizeInfo.elementId != 0) {
                    ResolveUnit unit = unresolvedUnits.take(sizeInfo.elementId, mIsHorizontal);

                    if (unit == null) {
                        unit = resolvedUnits.find(sizeInfo.elementId, mIsHorizontal);
                    }

                    if (unit != null) {
                        unit.setSize(size);
                        unit.setStart(currentPosition);
                        unit.setEnd(currentPosition + size);

                        resolvedUnits.add(unit);
                    }
                }

                currentPosition += size;

                calculatedSize += size;
            }
        }

        mSizeResolver.reset();

        return start + calculatedSize;
    }

}
