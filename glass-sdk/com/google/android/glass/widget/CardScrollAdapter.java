package com.google.android.glass.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

public abstract class CardScrollAdapter extends BaseAdapter {
    private Context mContext;

    public CardScrollAdapter() {
        super();
    }

    public CardScrollAdapter(Context context) {
        this.mContext = context;
    }

    public int getPosition(Object item) {
        // Return position of item in adapter
        return 0;
    }

    public int getHomePosition() {
        // Return the home position (usually 0)
        return 0;
    }

    @Override
    public abstract int getCount();

    @Override
    public abstract Object getItem(int position);

    @Override
    public abstract long getItemId(int position);

    @Override
    public abstract View getView(int position, View convertView, ViewGroup parent);
}