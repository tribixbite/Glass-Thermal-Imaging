package com.google.android.glass.widget;

import android.content.Context;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;

public class CardBuilder {
    public enum Layout {
        TEXT,
        COLUMNS,
        CAPTION,
        TITLE,
        TEXT_FIXED,
        MENU,
        EMBED_INSIDE,
        ALERT
    }

    private Context mContext;
    private Layout mLayout;
    private CharSequence mText;
    private CharSequence mHeading;
    private CharSequence mSubheading;
    private CharSequence mFootnote;
    private CharSequence mTimestamp;

    public CardBuilder(Context context, Layout layout) {
        this.mContext = context;
        this.mLayout = layout;
    }

    public CardBuilder setText(CharSequence text) {
        this.mText = text;
        return this;
    }

    public CardBuilder setHeading(CharSequence heading) {
        this.mHeading = heading;
        return this;
    }

    public CardBuilder setSubheading(CharSequence subheading) {
        this.mSubheading = subheading;
        return this;
    }

    public CardBuilder setFootnote(CharSequence footnote) {
        this.mFootnote = footnote;
        return this;
    }

    public CardBuilder setTimestamp(CharSequence timestamp) {
        this.mTimestamp = timestamp;
        return this;
    }

    public CardBuilder addImage(int resId) {
        // Image handling would be implemented here
        return this;
    }

    public CardBuilder setAttributionIcon(int resId) {
        // Attribution icon handling would be implemented here
        return this;
    }

    public View getView() {
        // Create a simple view for Glass card layout
        TextView textView = new TextView(mContext);
        if (mText != null) {
            textView.setText(mText);
        }
        textView.setTextColor(0xFFFFFFFF); // White text for Glass
        textView.setBackgroundColor(0xFF000000); // Black background for Glass
        return textView;
    }

    public RemoteViews getRemoteViews() {
        // Remote views implementation would go here
        return null;
    }
}