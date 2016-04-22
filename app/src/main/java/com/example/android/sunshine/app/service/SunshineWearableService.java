package com.example.android.sunshine.app.service;

import android.database.Cursor;
import android.net.Uri;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

public class SunshineWearableService extends WearableListenerService {

    private static final String GET_WEATHER_DATA_PATH = "/weather-data-request";
    private static final String WEATHER_DATA_PATH = "/weather-data";
    private static final String HIGH_TEMP_KEY = "highTemp";
    private static final String LOW_TEMP_KEY = "lowTemp";
    private static final String WEATHER_IMAGE_KEY = "weatherImage";
    private static final String TIME_RETRIEVED = "timeRetrieved";
    private static final int COL_WEATHER_MAX_TEMP = 0;
    private static final int COL_WEATHER_MIN_TEMP = 1;
    private static final int COL_WEATHER_CONDITION_ID = 2;

    private static final String[] WEATHER_COLUMNS = {
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
    };


    GoogleApiClient mGoogleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (GET_WEATHER_DATA_PATH.equals(messageEvent.getPath())) {

            Uri contentUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(
                    Utility.getPreferredLocation(getBaseContext()),
                    System.currentTimeMillis()
            );

            Cursor cursor = getContentResolver().query(contentUri, WEATHER_COLUMNS, null, null, null);

            if (cursor == null || !cursor.moveToFirst()) {
                return;
            }

            double highTemp = cursor.getDouble(COL_WEATHER_MAX_TEMP);
            double lowTemp = cursor.getDouble(COL_WEATHER_MIN_TEMP);
            int conditionId = cursor.getInt(COL_WEATHER_CONDITION_ID);

            PutDataMapRequest putDataMapReq = PutDataMapRequest.create(WEATHER_DATA_PATH).setUrgent();
            putDataMapReq.getDataMap().putInt(WEATHER_IMAGE_KEY, conditionId);
            putDataMapReq.getDataMap().putLong(TIME_RETRIEVED, System.currentTimeMillis());
            putDataMapReq.getDataMap().putString(HIGH_TEMP_KEY, Utility.formatTemperature(this, highTemp));
            putDataMapReq.getDataMap().putString(LOW_TEMP_KEY, Utility.formatTemperature(this, lowTemp));


            PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
            Wearable.DataApi.putDataItem(mGoogleApiClient, putDataReq);

            cursor.close();
        }
    }


}
