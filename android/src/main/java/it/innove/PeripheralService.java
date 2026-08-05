package it.innove;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.Log;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.IntentCompat;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PeripheralService extends Service {
    private static final String CHANNEL_ID = "my_channel_01";
    private static final int NOTIFICATION_ID = 1;

    public Map<String, Peripheral> peripherals = new LinkedHashMap<>();
    private BluetoothAdapter bluetoothAdapter;
    public ResultReceiver broadcastReciever;

    private static final String LOCK_STATUS_CHARACTERISTIC = "00001524-e513-11e5-9260-0002a5d5c51b";
    private String lastUUID;
    private String lastWrittenMessage;
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public PeripheralService() {
        super();
    }

    private static final String getConfirmTemplockURL(String networkUrl, String lockuid) {
        return networkUrl + "/mobile/v2/member/smartlocks/" + lockuid + "/confirm-temp-lock";
    }

    private static final String getLockReturnURL(String networkUrl, String lockuid) {
        return networkUrl + "/mobile/v2/member/smartlocks/" + lockuid + "/event/return";
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        PbscLog.d("bind attempt");
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Interaction with the bike's Smartlock",
                NotificationManager.IMPORTANCE_LOW);

        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        promoteToForeground();
    }

    private Notification buildForegroundNotification() {
        int icon = getApplicationInfo().icon;
        if (icon == 0) {
            icon = android.R.drawable.stat_sys_data_bluetooth;
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle("Interacting with smartlock")
                .setContentText("")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private boolean promoteToForeground() {
        try {
            ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildForegroundNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            return true;
        } catch (Exception e) {
            Log.e(BleManager.LOG_TAG, "PeripheralService startForeground failed", e);
            return false;
        }
    }

    private void sendError(ResultReceiver reciever, String message) {
        if (reciever == null) {
            return;
        }
        Bundle bundle = new Bundle();
        bundle.putString("ARGS", new Gson().toJson(new Object[]{message}));
        reciever.send(0, bundle);
    }

    private BluetoothAdapter getBluetoothAdapter() {
        if (bluetoothAdapter == null) {
            BluetoothManager manager = (BluetoothManager) this.getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothAdapter = manager.getAdapter();
        }
        return bluetoothAdapter;
    }

    public void stopService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.w(BleManager.LOG_TAG, "PeripheralService restarted with null intent, stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        final ResultReceiver reciever = IntentCompat.getParcelableExtra(
                intent, "resultReciever", ResultReceiver.class);

        if (!promoteToForeground()) {
            sendError(reciever, "Foreground service not allowed");
            stopSelf();
            return START_NOT_STICKY;
        }

        final String action = intent.getStringExtra("ACTION");
        if (action == null) {
            Log.w(BleManager.LOG_TAG, "PeripheralService missing ACTION, stopping");
            sendError(reciever, "Missing service action");
            stopSelf();
            return START_NOT_STICKY;
        }

        final Peripheral peripheral = retrieveOrCreatePeripheral(intent.getStringExtra("UUID"));
        if (peripheral == null) {
            Log.w(BleManager.LOG_TAG, "PeripheralService invalid UUID, stopping");
            sendError(reciever, "Invalid peripheral uuid");
            stopSelf();
            return START_NOT_STICKY;
        }
        this.broadcastReciever = IntentCompat.getParcelableExtra(
                intent, "eventReciever", ResultReceiver.class);
        this.lastUUID = intent.getStringExtra("UUID");

        PbscLog.d("Service started");
        PbscLog.d(action);


        if(action.equals("CONNECT")) {
            PbscLog.d("Service connect");
            peripheral.connect(new Callback() {
                @Override
                public void invoke(Object... args) {
                    PbscLog.d(args.toString());
                    PbscLog.d("Callback Called");
                    Bundle bundle = new Bundle();
                    bundle.putString("ARGS", new Gson().toJson(args));
                    reciever.send(0, bundle);
                }
            }, this);

        }

        if(action.equals("DISCONNECT")) {
            peripheral.disconnect(new Callback() {
                @Override
                public void invoke(Object... args) {
                    if (reciever != null) {
                        reciever.send(0, new Bundle());
                    }
                }
            }, true);
        }

        if(action.equals("STARTNOTIFICATION")) {
            PbscLog.d("Service start notify");
            UUID serviceUUID = UUIDHelper.uuidFromString(intent.getStringExtra("SERVICEUUID"));
            UUID characteristicUUID = UUIDHelper.uuidFromString(intent.getStringExtra("CHARACTERISTICUUID"));
            peripheral.registerNotify(serviceUUID, characteristicUUID, 1, new Callback() {
                @Override
                public void invoke(Object... args) {
                    PbscLog.d(args.toString());
                    PbscLog.d("Callback Called");
                    Bundle bundle = new Bundle();
                    bundle.putString("ARGS", new Gson().toJson(args));
                    reciever.send(0, bundle);
                }
            });
        }

        if(action.equals("STOPNOTIFICATION")) {
            PbscLog.d("Service stop notify");
            UUID serviceUUID = UUIDHelper.uuidFromString(intent.getStringExtra("SERVICEUUID"));
            UUID characteristicUUID = UUIDHelper.uuidFromString(intent.getStringExtra("CHARACTERISTICUUID"));
            peripheral.removeNotify(serviceUUID, characteristicUUID, new Callback() {
                @Override
                public void invoke(Object... args) {
                    PbscLog.d(args.toString());
                    PbscLog.d("Callback Called");
                    Bundle bundle = new Bundle();
                    bundle.putString("ARGS", new Gson().toJson(args));
                    reciever.send(0, bundle);
                }
            });
        }

        if(action.equals("WRITE")) {
            PbscLog.d("Service start write");
            UUID serviceUUID = UUIDHelper.uuidFromString(intent.getStringExtra("SERVICEUUID"));
            UUID characteristicUUID = UUIDHelper.uuidFromString(intent.getStringExtra("CHARACTERISTICUUID"));
            final String strMessage = intent.getStringExtra("MESSAGE");
            peripheral.write(serviceUUID, characteristicUUID, intent.getByteArrayExtra("DECODED"), intent.getIntExtra("MAXBYTESIZE", 20), null, new Callback() {
                @Override
                public void invoke(Object... args) {
                    PbscLog.d(args.toString());
                    PbscLog.d("Callback Called");
                    Bundle bundle = new Bundle();
                    bundle.putString("ARGS", new Gson().toJson(args));
                    lastWrittenMessage = strMessage;
                    reciever.send(0, bundle);
                }
            }, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        }

        if(action.equals("WRITEWITHOUTRESPONSE")) {
            UUID serviceUUID = UUIDHelper.uuidFromString(intent.getStringExtra("SERVICEUUID"));
            UUID characteristicUUID = UUIDHelper.uuidFromString(intent.getStringExtra("CHARACTERISTICUUID"));
            peripheral.write(serviceUUID, characteristicUUID, intent.getByteArrayExtra("DECODED"), intent.getIntExtra("MAXBYTESIZE", 20), null, new Callback() {
                @Override
                public void invoke(Object... args) {
                    PbscLog.d(args.toString());
                    PbscLog.d("Callback Called");
                    Bundle bundle = new Bundle();
                    bundle.putString("ARGS", new Gson().toJson(args));
                    reciever.send(0, bundle);
                }
            }, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        }

        if(action.equals("READ")) {
            PbscLog.d("Service read");
            UUID serviceUUID = UUIDHelper.uuidFromString(intent.getStringExtra("SERVICEUUID"));
            UUID characteristicUUID = UUIDHelper.uuidFromString(intent.getStringExtra("CHARACTERISTICUUID"));
            peripheral.read(serviceUUID, characteristicUUID, new Callback() {
                @Override
                public void invoke(Object... args) {
                    PbscLog.d(args.toString());
                    PbscLog.d("Callback Called");
                    Bundle bundle = new Bundle();

                    if(args.length > 1 && args[1] != null) {
                        WritableArray map = (WritableArray) args[1];
                        ArrayList<String> nargs = new ArrayList<String>();
                        nargs.add(args[0] != null ? (String) args[0] : null);
                        bundle.putString("ARGS", new Gson().toJson(nargs));
                        bundle.putString("MAP", new JSONArray(map.toArrayList()).toString());
                    } else {
                        bundle.putString("ARGS", new Gson().toJson(args));
                    }
                    reciever.send(0, bundle);
                }
            });
        }

        if(action.equals("RETRIEVESERVICES")) {
            PbscLog.d("Service retrieve");
            peripheral.retrieveServices(new Callback() {
                @Override
                public void invoke(Object... args) {
                    PbscLog.d(args.toString());
                    PbscLog.d("Callback Called");
                    Bundle bundle = new Bundle();
                    bundle.putString("ARGS", new Gson().toJson(args[0]));
                    if(args.length > 1 && args[1] != null) {
                        WritableMap map = (WritableMap) args[1];
                        ArrayList<String> nargs = new ArrayList<String>();
                        nargs.add(args[0] != null ? (String) args[0] : null);
                        bundle.putString("ARGS", new Gson().toJson(nargs));
                        bundle.putString("MAP", new JSONObject(map.toHashMap()).toString());
                    } else {
                        bundle.putString("ARGS", new Gson().toJson(args));
                    }
                    reciever.send(0, bundle);
                }
            });
        }

        if(action.equals("REFRESHCACHE")) {
            PbscLog.d("Service refresh cache");
            peripheral.refreshCache(new Callback() {
                @Override
                public void invoke(Object... args) {
                    PbscLog.d(args.toString());
                    PbscLog.d("Callback Called");
                    Bundle bundle = new Bundle();
                    bundle.putString("ARGS", new Gson().toJson(args));
                    reciever.send(0, bundle);
                }
            });
        }

        if(action.equals("READRSSI")) {
            peripheral.readRSSI(new Callback() {
                @Override
                public void invoke(Object... args) {
                    PbscLog.d(args.toString());
                    PbscLog.d("Callback Called");
                    Bundle bundle = new Bundle();
                    bundle.putString("ARGS", new Gson().toJson(args));
                    reciever.send(0, bundle);
                }
            });
        }

        if(action.equals("REQUESTCONNECTIONPRIORITY")) {
            int connectionPriority = intent.getIntExtra("CONNECTIONPRIORITY", 0);
            peripheral.requestConnectionPriority(connectionPriority, new Callback() {
                @Override
                public void invoke(Object... args) {
                    PbscLog.d(args.toString());
                    PbscLog.d("Callback Called");
                    Bundle bundle = new Bundle();
                    bundle.putString("ARGS", new Gson().toJson(args));
                    reciever.send(0, bundle);
                }
            });
        }

        if(action.equals("REQUESTMTU")) {
            int mtu = intent.getIntExtra("MTU", 0);
            peripheral.requestMTU(mtu, new Callback() {
                @Override
                public void invoke(Object... args) {
                    PbscLog.d(args.toString());
                    PbscLog.d("Callback Called");
                    Bundle bundle = new Bundle();
                    bundle.putString("ARGS", new Gson().toJson(args));
                    reciever.send(0, bundle);
                }
            });
        }

        return START_NOT_STICKY;
    }

    private String getRandomHexString(int numchars){
        Random r = new Random();
        StringBuffer sb = new StringBuffer();
        while(sb.length() < numchars){
            sb.append(Integer.toHexString(r.nextInt()));
        }

        return sb.toString().substring(0, numchars);
    }

    private static SharedPreferences getDefaultSharedPreferences(Context context) {
        Context appContext = context.getApplicationContext();
        return appContext.getSharedPreferences(
                appContext.getPackageName() + "_preferences",
                Context.MODE_PRIVATE);
    }

    public void backupEventHandler(String eventName, JSONObject params) {
        if(!eventName.equals("BleManagerDidUpdateValueForCharacteristic")) {
            retrieveOrCreatePeripheral(lastUUID).disconnect(null, true);
            return;
        }
        try {
            JSONObject serviceRecoveryData = new JSONObject(
                    getDefaultSharedPreferences(this).getString("serviceRecoveryData", ""));
            String lastSmartlockUsage = serviceRecoveryData.getString("lastSmartlockUsage");
            String lockuid = serviceRecoveryData.getString("lockuid");
            Boolean lastUsageIsLocking = lastSmartlockUsage.equals("TEMPORARY_LOCK")  || lastSmartlockUsage.equals("RETURN");
            JSONArray value = params.getJSONArray("value");

            Boolean valueExists = value != null && value.length() > 0;
            Boolean lockStatusIsLocked = valueExists  && ((value.getInt(0) & 0x01) == 0x01);
            Boolean lockStatusIsFreedToLock = valueExists && ((value.getInt(0) & 0x08) == 0x08);
            Boolean chainIsNotDisconnected = valueExists && ((value.getInt(0) & 0x80) == 0x80);
            Boolean midLockingIgnoreEvent = lastUsageIsLocking && params.getString("characteristic").equals(LOCK_STATUS_CHARACTERISTIC)  && lockStatusIsFreedToLock;
            Boolean midUnlockingIgnoreEvent = !lastUsageIsLocking && params.getString("characteristic").equals(LOCK_STATUS_CHARACTERISTIC)  && (chainIsNotDisconnected && !lockStatusIsLocked && !lockStatusIsFreedToLock);
            OkHttpClient client = new OkHttpClient();


            if(lastUsageIsLocking && params.getString("characteristic").equals(LOCK_STATUS_CHARACTERISTIC)  && lockStatusIsLocked) {
                    boolean isInSSOMode = serviceRecoveryData.getString("ssoMode") != null ? serviceRecoveryData.getBoolean("ssoMode") : false;
                    if(lastSmartlockUsage.equals("TEMPORARY_LOCK") ) {
                        JSONObject requestBody = new JSONObject();
                        requestBody.put("otpKey", lastWrittenMessage);
                        String res = post(getConfirmTemplockURL(serviceRecoveryData.getString("url"), lockuid), requestBody.toString(), client, serviceRecoveryData.getString("apiKey"), serviceRecoveryData.getString("token"), isInSSOMode);
                        PbscLog.d("tempLockConfirmed " + res);
                    } else if(lastSmartlockUsage.equals("RETURN")){
                        TimeZone tz = TimeZone.getTimeZone("UTC");
                        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
                        df.setTimeZone(tz);
                        String timestamp = df.format(new Date());
                        JSONObject requestBody = new JSONObject();
                        requestBody.put("otpKey", lastWrittenMessage != null ? lastWrittenMessage : serviceRecoveryData.getString("returnFirstOtp"));
                        requestBody.put("stationId", serviceRecoveryData.getString("stationId"));
                        requestBody.put("sequence", getRandomHexString(10));
                        requestBody.put("timestamp", timestamp);
                        String res = post(getLockReturnURL(serviceRecoveryData.getString("url"), lockuid), requestBody.toString(), client, serviceRecoveryData.getString("apiKey"), serviceRecoveryData.getString("token"), isInSSOMode);
                        PbscLog.d("returnDone " + res);
                    }
                    retrieveOrCreatePeripheral(lastUUID).disconnect(null, true);
            } else {
                if(midLockingIgnoreEvent || midUnlockingIgnoreEvent) {
                    return;
                }
                retrieveOrCreatePeripheral(lastUUID).disconnect(null, true);
            }
        } catch (JSONException | IOException e) {
            retrieveOrCreatePeripheral(lastUUID).disconnect(null, true);
            Log.e(BleManager.LOG_TAG, "backupEventHandler failed", e);
        }
    }

    private String post(String url, String json, OkHttpClient client, String apiKey, String token, boolean isInSSOMode) throws IOException {
        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-API-KEY", apiKey)
                .addHeader(isInSSOMode ? "Authorization" : "X-AUTH-TOKEN", token)
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.body().string();
        }
    }

    private Peripheral retrieveOrCreatePeripheral(String peripheralUUID) {
        BluetoothAdapter adapter = getBluetoothAdapter();
        Peripheral peripheral = peripherals.get(peripheralUUID);
        if (peripheral == null) {
            if (peripheralUUID != null) {
                peripheralUUID = peripheralUUID.toUpperCase();
            }
            if (BluetoothAdapter.checkBluetoothAddress(peripheralUUID)) {
                BluetoothDevice device = adapter.getRemoteDevice(peripheralUUID);
                peripheral = new Peripheral(device, this, this);
                peripherals.put(peripheralUUID, peripheral);
            }
        }
        return peripheral;
    }
}
