package com.google.android.glass.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.AdapterView;

public class CardScrollView extends AdapterView<CardScrollAdapter> {
    public interface OnItemClickListener {
        void onItemClick(AdapterView<?> parent, View view, int position, long id);
    }

    private CardScrollAdapter mAdapter;
    private OnItemClickListener mOnItemClickListener;
    private boolean mActivated = false;

    public CardScrollView(Context context) {
        super(context);
        init();
    }

    public CardScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CardScrollView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        // Initialize Glass-specific card scroll view
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public void setAdapter(CardScrollAdapter adapter) {
        this.mAdapter = adapter;
        // Don't call super.setAdapter due to type constraints
    }

    @Override
    public CardScrollAdapter getAdapter() {
        return mAdapter;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.mOnItemClickListener = listener;
    }

    public void activate() {
        mActivated = true;
        requestFocus();
    }

    public void deactivate() {
        mActivated = false;
        clearFocus();
    }

    public boolean isActivated() {
        return mActivated;
    }

    @Override
    public View getSelectedView() {
        return null;
    }

    @Override
    public void setSelection(int position) {
        // Glass card scroll selection logic
    }
}