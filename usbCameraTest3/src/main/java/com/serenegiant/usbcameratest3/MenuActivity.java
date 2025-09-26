package com.serenegiant.usbcameratest3;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;

import java.util.ArrayList;
import java.util.List;

public class MenuActivity extends Activity {

    public static final String EXTRA_THERMAL_MODE = "thermal_mode";
    public static final String EXTRA_GPS_ENABLED = "gps_enabled";
    public static final String EXTRA_PALETTE = "palette";
    public static final String EXTRA_TOGGLE_RECORDING = "toggle_recording";

    private CardScrollView mCardScroller;
    private CardScrollAdapter mAdapter;

    private boolean mThermalMode;
    private boolean mGpsEnabled;
    private int mPalette;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        Intent intent = getIntent();
        mThermalMode = intent.getBooleanExtra(EXTRA_THERMAL_MODE, false);
        mGpsEnabled = intent.getBooleanExtra(EXTRA_GPS_ENABLED, true);
        mPalette = intent.getIntExtra(EXTRA_PALETTE, 0);

        mAdapter = new MenuCardAdapter(this, createCards());
        mCardScroller = new CardScrollView(this);
        mCardScroller.setAdapter(mAdapter);
        setContentView(mCardScroller);

        mCardScroller.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Intent resultIntent = new Intent();
                switch (position) {
                    case 0:
                        resultIntent.putExtra(EXTRA_THERMAL_MODE, !mThermalMode);
                        break;
                    case 1:
                        int nextPalette = (mPalette + 1) % 3;
                        resultIntent.putExtra(EXTRA_PALETTE, nextPalette);
                        break;
                    case 2:
                        resultIntent.putExtra(EXTRA_GPS_ENABLED, !mGpsEnabled);
                        break;
                    case 3:
                        resultIntent.putExtra(EXTRA_TOGGLE_RECORDING, true);
                        break;
                }
                setResult(RESULT_OK, resultIntent);
                finish();
            }
        });
    }

    private List<CardBuilder> createCards() {
        ArrayList<CardBuilder> cards = new ArrayList<>();
        cards.add(new CardBuilder(this, CardBuilder.Layout.TEXT)
                .setText("Thermal Mode")
                .setFootnote(mThermalMode ? "On" : "Off"));

        cards.add(new CardBuilder(this, CardBuilder.Layout.TEXT)
                .setText("Palette")
                .setFootnote(getPaletteName(mPalette)));

        cards.add(new CardBuilder(this, CardBuilder.Layout.TEXT)
                .setText("GPS")
                .setFootnote(mGpsEnabled ? "On" : "Off"));

        cards.add(new CardBuilder(this, CardBuilder.Layout.TEXT)
                .setText("Toggle Recording"));

        return cards;
    }

    private String getPaletteName(int palette) {
        switch (palette) {
            case 0: return "Iron";
            case 1: return "Rainbow";
            case 2: return "Gray";
            default: return "Unknown";
        }
    }

    private class MenuCardAdapter extends CardScrollAdapter {
        private final List<CardBuilder> mCards;

        public MenuCardAdapter(Context context, List<CardBuilder> cards) {
            mCards = cards;
        }

        @Override
        public int getCount() {
            return mCards.size();
        }

        @Override
        public Object getItem(int position) {
            return mCards.get(position);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return mCards.get(position).getView(convertView, parent);
        }

        @Override
        public int getPosition(Object item) {
            for (int i = 0; i < mCards.size(); i++) {
                if (mCards.get(i).equals(item)) {
                    return i;
                }
            }
            return AdapterView.INVALID_POSITION;
        }
    }
}
