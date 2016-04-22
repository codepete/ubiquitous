/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {

        final String TAG = Engine.class.getSimpleName();
        static final String GET_WEATHER_DATA_PATH = "/weather-data-request";
        static final String WEATHER_DATA_PATH = "/weather-data";
        static final String HIGH_TEMP_KEY = "highTemp";
        static final String LOW_TEMP_KEY = "lowTemp";
        static final String WEATHER_IMAGE_KEY = "weatherImage";

        final float ICON_SIZE = 70;
        final float ICON_RIGHT_PADDING = 30f;
        final float TEMP_TEXT_RIGHT_PADDING = 10f;
        final float DIVIDER_LINE_WIDTH = 2f;

        GoogleApiClient mGoogleApiClient;
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mLowTempPaint;
        Paint mHighTempPaint;
        Paint mLinePaint;
        Paint mWeatherPaint;
        boolean mAmbient;
        Calendar mCalendar;
        SimpleDateFormat mTimeFormat;
        SimpleDateFormat mDateFormat;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;


        String mHighTemp;
        String mLowTemp;
        Bitmap mWeatherBitmap;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mTimePaint.setTextAlign(Paint.Align.CENTER);

            mDatePaint = createTextPaint(resources.getColor(R.color.sub_text));
            mDatePaint.setTextAlign(Paint.Align.CENTER);
            mDatePaint.setTextSize(resources.getDimension(R.dimen.date_text_size));

            mHighTempPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mHighTempPaint.setTextAlign(Paint.Align.CENTER);
            mHighTempPaint.setTextSize(resources.getDimension(R.dimen.temp_font_size));

            mLowTempPaint = createTextPaint(resources.getColor(R.color.sub_text));
            mLowTempPaint.setTextAlign(Paint.Align.CENTER);
            mLowTempPaint.setTextSize(resources.getDimension(R.dimen.temp_font_size));

            mLinePaint = new Paint();
            mLinePaint.setColor(resources.getColor(R.color.sub_text));
            mLinePaint.setAntiAlias(true);

            mWeatherPaint = new Paint();

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            mTimeFormat = new SimpleDateFormat("hh:mm");
            mDateFormat = new SimpleDateFormat("EEE, MMM d yyyy");

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                getWeatherDataFromPhone();
            } else {
                mGoogleApiClient.disconnect();
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mYOffset = resources.getDimension(isRound ? R.dimen.digital_y_offset : R.dimen.digital_y_offset_square);
            mTimePaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mLinePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mLowTempPaint.setAntiAlias(!inAmbientMode);
                    mHighTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            int centerX = bounds.centerX();
            int centerY = bounds.centerY();

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            Date date = mCalendar.getTime();
            String timeText = mTimeFormat.format(date);
            String dateText = mDateFormat.format(date);

            Rect timeBound = getTextBounds(timeText, mTimePaint);
            Rect dateBound = getTextBounds(dateText, mDatePaint);

            // Draw time
            canvas.drawText(timeText, centerX, mYOffset, mTimePaint);

            // Draw date
            float dateYOffset = mYOffset + mTimePaint.getFontMetrics().bottom + dateBound.height();
            canvas.drawText(dateText.toUpperCase(), centerX, dateYOffset, mDatePaint);


            if (!isInAmbientMode() && mHighTemp != null && mLowTemp != null && mWeatherBitmap != null) {
                // Draw line below time
                float lineYOffset = dateYOffset + mDatePaint.getFontMetrics().bottom + 15f;
                canvas.drawLine(
                        centerX - bounds.width()*.12f,
                        lineYOffset,
                        centerX + bounds.width()*.12f,
                        lineYOffset + DIVIDER_LINE_WIDTH,
                        mLinePaint
                );

                float lowTempWidth = mLowTempPaint.measureText(mLowTemp);
                float highTempWidth = mHighTempPaint.measureText(mHighTemp);
                float totalWidth = ICON_SIZE  + ICON_RIGHT_PADDING + lowTempWidth + TEMP_TEXT_RIGHT_PADDING + highTempWidth;;
                float xOffset = centerX - (totalWidth/2);
                float yOffset = lineYOffset + 10f;

                Rect tempBound = getTextBounds(mHighTemp, mHighTempPaint);
                if (!isInAmbientMode()) {
                    canvas.drawBitmap(mWeatherBitmap, null, new Rect((int) xOffset, (int)(yOffset), (int) (xOffset + ICON_SIZE), (int) (yOffset + ICON_SIZE)), mWeatherPaint);
                    xOffset += ICON_SIZE + ICON_RIGHT_PADDING;
                    yOffset += tempBound.height() + (ICON_SIZE - tempBound.height())/2;
                } else {
                    yOffset += tempBound.height();
                }


                canvas.drawText(mHighTemp, xOffset, yOffset, mHighTempPaint);
                canvas.drawText(mLowTemp, xOffset + highTempWidth + TEMP_TEXT_RIGHT_PADDING, yOffset, mLowTempPaint);

            }


        }

        private Rect getTextBounds(String timeText, Paint timePaint) {
            Rect textBounds = new Rect();
            timePaint.getTextBounds(timeText, 0, timeText.length(), textBounds);
            return textBounds;
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = dataEvent.getDataItem();
                    if (item.getUri().getPath().compareTo(WEATHER_DATA_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();

                        int weatherId = dataMap.getInt(WEATHER_IMAGE_KEY);
                        mWeatherBitmap = BitmapFactory.decodeResource(getResources(), getIconResourceForWeatherCondition(weatherId));

                        mHighTemp = dataMap.getString(HIGH_TEMP_KEY);
                        mLowTemp = dataMap.getString(LOW_TEMP_KEY);
                    }
                }
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            getWeatherDataFromPhone();
        }

        @Override
        public void onConnectionSuspended(int i) {
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
        }

        private void getWeatherDataFromPhone(){
            if(mGoogleApiClient.isConnected()) {
                new Thread(){
                    @Override
                    public void run() {
                        NodeApi.GetConnectedNodesResult nodesList =
                                Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();

                        for(Node node : nodesList.getNodes()){
                            Wearable.MessageApi.sendMessage(
                                    mGoogleApiClient,
                                    node.getId(),
                                    GET_WEATHER_DATA_PATH,
                                    null).await();
                        }
                    }
                }.start();
            }
        }

        public int getIconResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.art_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.art_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.art_rain;
            } else if (weatherId == 511) {
                return R.drawable.art_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.art_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.art_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.art_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.art_storm;
            } else if (weatherId == 800) {
                return R.drawable.art_clear;
            } else if (weatherId == 801) {
                return R.drawable.art_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.art_clouds;
            }
            return -1;
        }

    }
}
