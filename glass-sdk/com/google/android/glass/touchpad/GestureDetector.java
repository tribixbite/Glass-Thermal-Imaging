package com.google.android.glass.touchpad;

import android.content.Context;
import android.view.MotionEvent;

public class GestureDetector {
    public interface BaseListener {
        boolean onGesture(Gesture gesture);
    }

    public interface FingerListener {
        void onFingerCountChanged(int previousCount, int currentCount);
    }

    public interface ScrollListener {
        boolean onScroll(float displacement, float delta, float velocity);
    }

    private Context mContext;
    private BaseListener mBaseListener;
    private FingerListener mFingerListener;
    private ScrollListener mScrollListener;

    public GestureDetector(Context context) {
        this.mContext = context;
    }

    public GestureDetector setBaseListener(BaseListener listener) {
        this.mBaseListener = listener;
        return this;
    }

    public GestureDetector setFingerListener(FingerListener listener) {
        this.mFingerListener = listener;
        return this;
    }

    public GestureDetector setScrollListener(ScrollListener listener) {
        this.mScrollListener = listener;
        return this;
    }

    public boolean onTouchEvent(MotionEvent event) {
        // Basic gesture detection logic would go here
        // For Glass compatibility, we'll handle basic TAP gestures
        if (event.getAction() == MotionEvent.ACTION_UP && mBaseListener != null) {
            return mBaseListener.onGesture(Gesture.TAP);
        }
        return false;
    }

    public boolean onMotionEvent(MotionEvent event) {
        return onTouchEvent(event);
    }
}