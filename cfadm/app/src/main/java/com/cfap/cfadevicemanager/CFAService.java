package com.cfap.cfadevicemanager;

/**
 * Created by Shreya Jagarlamudi on 27/07/15.
 */

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;


/**
 * This class does all the work in the background even when the app is killed: connecting to the server, updating data to server etc.
 * Here is what is happening:
 * When this service is first created on launch of mainactivity, we set up location and all the other details and start sendtask
 * from then on, everytime the location is changed, onLocationChanged gets triggered and we execute sendtask again
 * As of now, we do not hear for any queries from server. We only publish to server. Next version will deal with subscribing
 * and taking requests from server.
 */
public class CFAService extends Service implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
        LocationListener {

    private String TAG = "CFAService";
    private String KEY_IMEI = "imei";
    private String KEY_Battery = "battery";
    private String KEY_Model = "model";
    private String KEY_Version = "version";
    private String KEY_Location = "locationlatlong";
    private String KEY_LocTime = "lastloctime";
    private String Server = "tcp://104.155.237.100:1883";
    public static final String BROADCAST_ACTION = "com.cfap.cfadevicemanager.CFAService";
    GlobalState gs;
    private final Handler handler = new Handler();
    Intent intent;
    int counter = 0;

    private MqttClient mqttClient;
    GoogleApiClient mGoogleApiClient;
    Location mLastLocation;
    LocationRequest mLocationRequest;
    SendTask sendData;

    private long UPDATE_INTERVAL = 300000; // updates location every 30 mins
    private long FASTEST_INTERVAL = 150000;
    private String currLocation; // string version of curr location or last known location
    private String lastLocTime; // time of curr location or last known location
    private String jsonStr;

    @Override
    public void onCreate() {
        Log.e(TAG, "in onCreate");
        super.onCreate();
        gs = (GlobalState) getApplication();
        intent = new Intent(BROADCAST_ACTION);
        buildGoogleApiClient();
        if(mGoogleApiClient!= null){
            mGoogleApiClient.connect();
        }else{
            Log.e(TAG, "not connected");
        }
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(UPDATE_INTERVAL);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        mLocationRequest.setFastestInterval(FASTEST_INTERVAL);
        sendData = new SendTask();
        sendData.execute();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e(TAG, "in onStartCommand");
        gs = (GlobalState) getApplication();
        handler.removeCallbacks(sendUpdatesToUI);
        handler.postDelayed(sendUpdatesToUI, 30000); // 30 seconds
        return START_STICKY;
    }

    private Runnable sendUpdatesToUI = new Runnable() {
        public void run() {
            DisplayLoggingInfo();
            handler.postDelayed(this, 5000); // 5 seconds
        }
    };

    private void DisplayLoggingInfo() {
        Log.d(TAG, "entered DisplayLoggingInfo");

        intent.putExtra("jstring", jsonStr);
        sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.e(TAG, "in onBind!");
        return null;
    }

    @Override
    public void onDestroy() {
        Log.e(TAG, "in onDestroy");
        super.onDestroy();
    }

    @Override
    public void onConnected(Bundle bundle) {
        Log.e(TAG, "in onConnected");
        mLastLocation = LocationServices.FusedLocationApi.getLastLocation(
                mGoogleApiClient);
        if (mLastLocation != null) {
            setCurrLocation("lat "+String.valueOf(mLastLocation.getLatitude()) + " long " + String.valueOf(mLastLocation.getLongitude()));
            Long time = mLastLocation.getTime();
            setLastLocTime(time);
            Log.e(TAG, currLocation+" "+getLastLocTime());
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.e(TAG, "in onConnectionSuspended");
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.e(TAG, "in onConnectionFailed");
    }



    protected synchronized void buildGoogleApiClient() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
    }

    @Override
    public void onLocationChanged(Location location) {
        mLastLocation = location;
        if (mLastLocation != null) {
           setCurrLocation("lat "+String.valueOf(mLastLocation.getLatitude())+" long "+String.valueOf(mLastLocation.getLongitude()));
            setLastLocTime(mLastLocation.getTime());
            Log.e(TAG, "currLocation changed: "+currLocation+" "+getLastLocTime());
        }
        sendData.execute();
    }

    private void setCurrLocation(String loc){
        currLocation = loc;
    }
    private String getCurrLocation(){
        return currLocation;
    }

    private void setLastLocTime(long t){
        SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy hh:mm:ss.SSS");
        lastLocTime = formatter.format(t);
    }
    private String getLastLocTime(){
        return lastLocTime;
    }

    public String getDeviceModel(){
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model.startsWith(manufacturer)) {
            return capitalize(model);
        } else {
            return capitalize(manufacturer) + " " + model;
        }
    }

    private String capitalize(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        char first = s.charAt(0);
        if (Character.isUpperCase(first)) {
            return s;
        } else {
            return Character.toUpperCase(first) + s.substring(1);
        }
    }

    public String getAndroidVersion() {
        String release = Build.VERSION.RELEASE;
        int sdkVersion = Build.VERSION.SDK_INT;
        return "Android SDK: " + sdkVersion + " (" + release +")";
    }

    public String getDeviceImei(){
        TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        return telephonyManager.getDeviceId().toString();
    }

    public String getBatteryStatus(){
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = this.registerReceiver(null, ifilter);

        // are we charging?
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL;

        // How are we charging?
        int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        boolean usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB;
        boolean acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC;

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = (level / (float)scale)*100;
        return String.valueOf(batteryPct)+"% charging?: "+isCharging+" AC Charging?: "+acCharge+" USB Charging?: "+usbCharge;
    }

    /** We run all the connections code in an async task so that we do not block the main/UI thread. everything runs
     *on a separate background thread here. We connect to the server using mqttclient and publish data in a json format.
     * */
    private class SendTask extends AsyncTask<String, String, String>{

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

        }

        @Override
        protected String doInBackground(String... params) {
            try {
                mqttClient = new MqttClient(Server, "123", new MemoryPersistence());
                mqttClient.connect();
            } catch (MqttException e) {
                e.printStackTrace();
            }
            JSONObject json = new JSONObject();
            try {
                json.put(KEY_Location, getCurrLocation());
                json.put(KEY_LocTime, getLastLocTime());
                json.put(KEY_IMEI, getDeviceImei());
                json.put(KEY_Model, getDeviceModel());
                json.put(KEY_Version, getAndroidVersion());
                json.put(KEY_Battery, getBatteryStatus());
            } catch (JSONException e) {
                e.printStackTrace();
            }
            String jString = json.toString();
            try {
                final MqttMessage message = new MqttMessage(jString.getBytes());
                final byte[] b = message.getPayload();
                mqttClient.publish("Details", b, 2, false);
            } catch (MqttException e) {
                e.printStackTrace();
            }
            Log.e(TAG, "sendTask: locationStr: "+getCurrLocation());
            Log.e(TAG, "sendTask: json String: "+jString);
            jsonStr = jString;
            gs.setJStr(jString);
            return jString;
        }

        @Override
        protected void onPostExecute(String s) {

        }
    }
}
