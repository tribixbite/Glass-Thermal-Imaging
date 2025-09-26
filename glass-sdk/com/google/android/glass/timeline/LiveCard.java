package com.google.android.glass.timeline;

import android.app.PendingIntent;
import android.content.Context;
import android.widget.RemoteViews;

public class LiveCard {
    public enum PublishMode {
        REVEAL, SILENT
    }

    private Context mContext;
    private String mTag;

    public LiveCard(Context context, String tag) {
        this.mContext = context;
        this.mTag = tag;
    }

    public void setViews(RemoteViews remoteViews) {
        // Set the remote views for the live card
    }

    public void setAction(PendingIntent pendingIntent) {
        // Set the action that occurs when the live card is tapped
    }

    public void publish(PublishMode mode) {
        // Publish the live card to the timeline
    }

    public void unpublish() {
        // Remove the live card from the timeline
    }

    public boolean isPublished() {
        // Check if the live card is currently published
        return false;
    }
}