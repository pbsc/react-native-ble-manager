package it.innove;

import static android.app.Activity.RESULT_OK;
import static android.bluetooth.BluetoothProfile.GATT;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.companion.CompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.common.LifecycleState;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BleManager extends NativeBleManagerSpec {

    public static final String LOG_TAG = "RNBleManager";
    private static final int ENABLE_REQUEST = 539;

    private static class BondRequest {
        private String uuid;
        private String pin;
        private Callback callback;

        BondRequest(String _uuid, Callback _callback) {
            uuid = _uuid;
            callback = _callback;
        }

        BondRequest(String _uuid, String _pin, Callback _callback) {
            uuid = _uuid;
            pin = _pin;
            callback = _callback;
        }
    }

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothManager bluetoothManager;
    private Context context;
    private ReactApplicationContext reactContext;
    private Callback enableBluetoothCallback;
    private ScanManager scanManager;
    private BondRequest bondRequest;
    private BondRequest removeBondRequest;
    private boolean forceLegacy;
    /**
     * Used for companion scanning, if supported.
     */
    private final @Nullable CompanionScanner companionScanner;
    public static ReadableMap moduleOptions;

    /** PBSC: mirrors RN AppState host resume/pause for background BLE recovery. */
    private static volatile boolean hostResumed = false;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private final LifecycleEventListener hostLifecycleListener = new LifecycleEventListener() {
        @Override
        public void onHostResume() {
            hostResumed = true;
        }

        @Override
        public void onHostPause() {
            hostResumed = false;
        }

        @Override
        public void onHostDestroy() {
            hostResumed = false;
        }
    };

    static boolean isHostInForeground() {
        return hostResumed;
    }

    private ResultReceiver getReceiver(final Callback callback) {
        return new ResultReceiver(MAIN_HANDLER) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                ArrayList args = new Gson().fromJson(resultData.getString("ARGS"), ArrayList.class);
                if (args != null) {
                    callback.invoke(args.toArray(new Object[args.size()]));
                } else {
                    callback.invoke();
                }
            }
        };
    }

    private ResultReceiver getEventReciever() {
        return new ResultReceiver(MAIN_HANDLER) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                String eventName = resultData.getString("EVENTNAME");
                String paramsStr = resultData.getString("PARAMS");
                WritableMap params = null;
                if (paramsStr != null) {
                    try {
                        params = convertJsonToMap(new JSONObject(paramsStr));
                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "PBSC event params parse failed", e);
                        return;
                    }
                }
                switch (eventName) {
                    case "BleManagerConnectPeripheral":
                        emitOnConnectPeripheral(params);
                        break;
                    case "BleManagerDisconnectPeripheral":
                        emitOnDisconnectPeripheral(params);
                        break;
                    case "BleManagerDidUpdateValueForCharacteristic":
                        emitOnDidUpdateValueForCharacteristic(params);
                        break;
                    default:
                        Log.w(LOG_TAG, "Unknown PBSC event: " + eventName);
                }
            }
        };
    }

    private void startPbscService(Intent intent) {
        ReactApplicationContext context = getReactApplicationContext();
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (IllegalStateException e) {
            // Android 12+ may block startForegroundService from background when the service
            // is already running from an earlier in-foreground operation.
            PbscLog.d("startForegroundService blocked, trying startService");
            try {
                context.startService(intent);
            } catch (Exception fallbackError) {
                Log.e(LOG_TAG, "Failed to start PBSC service", fallbackError);
                notifyServiceStartFailed(intent, "Foreground service not allowed");
            }
        }
    }

    private void notifyServiceStartFailed(Intent intent, String message) {
        ResultReceiver receiver = IntentCompat.getParcelableExtra(
                intent, "resultReciever", ResultReceiver.class);
        if (receiver == null) {
            return;
        }
        Bundle bundle = new Bundle();
        bundle.putString("ARGS", new Gson().toJson(new Object[]{message}));
        receiver.send(0, bundle);
    }

    public ReactApplicationContext getReactContext() {
        return reactContext;
    }

    private final ActivityEventListener mActivityEventListener = new BaseActivityEventListener() {

        @Override
        public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent intent) {
            PbscLog.d( "onActivityResult");
            if (requestCode == ENABLE_REQUEST && enableBluetoothCallback != null) {
                if (resultCode == RESULT_OK) {
                    enableBluetoothCallback.invoke();
                } else {
                    enableBluetoothCallback.invoke("User refused to enable");
                }
                enableBluetoothCallback = null;
            }
        }

    };

    private class MyBroadcastReceiver extends BroadcastReceiver {
        private final BleManager bleManager;

        public MyBroadcastReceiver(BleManager bleManager) {
            this.bleManager = bleManager;
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            PbscLog.d( "onReceive");
            final String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                String stringState = "";

                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        stringState = "off";
                        clearPeripherals();
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        stringState = "turning_off";
                        disconnectPeripherals();
                        break;
                    case BluetoothAdapter.STATE_ON:
                        stringState = "on";
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        stringState = "turning_on";
                        break;
                    default:
                        // should not happen as per https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#EXTRA_STATE
                        stringState = "off";
                        break;
                }

                WritableMap map = Arguments.createMap();
                map.putString("state", stringState);
                PbscLog.d( "state: " + stringState);
                emitOnDidUpdateState(map);

            } else if (action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)) {
                final int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                final int prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.ERROR);
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }

                String bondStateStr = "UNKNOWN";
                switch (bondState) {
                    case BluetoothDevice.BOND_BONDED:
                        bondStateStr = "BOND_BONDED";
                        break;
                    case BluetoothDevice.BOND_BONDING:
                        bondStateStr = "BOND_BONDING";
                        break;
                    case BluetoothDevice.BOND_NONE:
                        bondStateStr = "BOND_NONE";
                        break;
                }
                PbscLog.d( "bond state: " + bondStateStr);

                if (bondRequest != null && bondRequest.uuid.equals(device.getAddress())) {
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        bondRequest.callback.invoke();
                        bondRequest = null;
                    } else if (bondState == BluetoothDevice.BOND_NONE || bondState == BluetoothDevice.ERROR) {
                        bondRequest.callback.invoke("Bond request has been denied");
                        bondRequest = null;
                    }
                }

                if (bondState == BluetoothDevice.BOND_BONDED) {
                    Peripheral peripheral;
                    if (!forceLegacy) {
                        peripheral = new DefaultPeripheral(device, bleManager);
                    } else {
                        peripheral = new Peripheral(device, bleManager);
                    }
                    WritableMap map = peripheral.asWritableMap();
                    emitOnPeripheralDidBond(map);
                }

                if (removeBondRequest != null && removeBondRequest.uuid.equals(device.getAddress())
                        && bondState == BluetoothDevice.BOND_NONE && prevState == BluetoothDevice.BOND_BONDED) {
                    removeBondRequest.callback.invoke();
                    removeBondRequest = null;
                }
            } else if (action.equals(BluetoothDevice.ACTION_PAIRING_REQUEST)) {
                BluetoothDevice bluetoothDevice;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    bluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }
                if (bondRequest != null && bondRequest.uuid.equals(bluetoothDevice.getAddress()) && bondRequest.pin != null) {
                    bluetoothDevice.setPin(bondRequest.pin.getBytes());
                    bluetoothDevice.createBond();
                }
            }

        }
    }

    // key is the MAC Address
    private final Map<String, Peripheral> peripherals = new LinkedHashMap<>();
    // scan session id

    public BleManager(ReactApplicationContext reactContext) {
        super(reactContext);
        context = reactContext;
        this.reactContext = reactContext;

        boolean supportsCompanion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP);
        this.companionScanner = supportsCompanion
                ? new CompanionScanner(reactContext, this)
                : null;

        reactContext.addActivityEventListener(mActivityEventListener);
        hostResumed = reactContext.getLifecycleState() == LifecycleState.RESUMED;
        reactContext.addLifecycleEventListener(hostLifecycleListener);
        PbscLog.d( "BleManager created");
    }

    @NonNull
    @Override
    public String getName() {
        return "BleManager";
    }

    private BluetoothAdapter getBluetoothAdapter() {
        if (bluetoothAdapter == null) {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothAdapter = manager.getAdapter();
        }
        return bluetoothAdapter;
    }

    private BluetoothManager getBluetoothManager() {
        if (bluetoothManager == null) {
            bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        }
        return bluetoothManager;
    }

    @ReactMethod
    public void start(ReadableMap options, Callback callback) {
        PbscLog.d( "start");
        if (getBluetoothAdapter() == null) {
            PbscLog.d( "No bluetooth support");
            callback.invoke("No bluetooth support");
            return;
        }
        forceLegacy = false;
        moduleOptions = options;
        if (options.hasKey("forceLegacy")) {
            forceLegacy = options.getBoolean("forceLegacy");
        }

        scanManager = new DefaultScanManager(reactContext, this);

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        IntentFilter intentFilter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        if (Build.VERSION.SDK_INT >= 34) {
            // Google in 2023 decides that flag RECEIVER_NOT_EXPORTED or RECEIVER_EXPORTED should be explicit set SDK 34(UPSIDE_DOWN_CAKE) on registering receivers.
            // Also the export flags are available on Android 8 and higher, should be used with caution so that don't break compability with that devices.
            context.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
            context.registerReceiver(mReceiver, intentFilter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(mReceiver, filter);
            context.registerReceiver(mReceiver, intentFilter);
        }

        callback.invoke();
        PbscLog.d( "BleManager initialized");
    }

    @ReactMethod
    public void isStarted(Callback callback) {
        PbscLog.d( "isStarted");
        callback.invoke(null, scanManager != null);
    }

    @SuppressLint("MissingPermission")
    @ReactMethod
    public void enableBluetooth(Callback callback) {
        if (getBluetoothAdapter() == null) {
            PbscLog.d( "No bluetooth support");
            callback.invoke("No bluetooth support");
            return;
        }
        if (!getBluetoothAdapter().isEnabled()) {
            Intent intentEnable = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (getCurrentActivity() == null)
                callback.invoke("Current activity not available");
            else {
                enableBluetoothCallback = callback;
                try {
                    getCurrentActivity().startActivityForResult(intentEnable, ENABLE_REQUEST);
                } catch (Exception e) {
                    enableBluetoothCallback = null;
                    callback.invoke("Error starting enable bluetooth activity");
                }

            }

        } else
            callback.invoke();
    }

    @ReactMethod
    public void scan(ReadableMap scanningOptions,
                     Callback callback) {
        PbscLog.d( "scan");
        if (getBluetoothAdapter() == null) {
            PbscLog.d( "No bluetooth support");
            callback.invoke("No bluetooth support");
            return;
        }
        if (!getBluetoothAdapter().isEnabled()) {
            return;
        }

        synchronized (peripherals) {
            for (Iterator<Map.Entry<String, Peripheral>> iterator = peripherals.entrySet().iterator(); iterator
                    .hasNext(); ) {
                Map.Entry<String, Peripheral> entry = iterator.next();
                if (!(entry.getValue().isConnected() || entry.getValue().isConnecting())) {
                    iterator.remove();
                }
            }
        }

        if (scanManager != null)
            scanManager.scan(scanningOptions, callback);
    }

    @SuppressLint("NewApi") // NOTE: constructor checks the API version.
    @ReactMethod
    public void companionScan(ReadableArray serviceUUIDs, ReadableMap options, Callback callback) {
        if (this.companionScanner == null) {
            callback.invoke("not supported");
        } else {
            this.companionScanner.scan(serviceUUIDs, options, callback);
        }
    }

    @ReactMethod
    public void supportsCompanion(Callback callback) {
        callback.invoke(companionScanner != null);
    }

    @ReactMethod
    public void stopScan(Callback callback) {
        PbscLog.d( "Stop scan");
        if (getBluetoothAdapter() == null) {
            PbscLog.d( "No bluetooth support");
            callback.invoke("No bluetooth support");
            return;
        }
        if (!getBluetoothAdapter().isEnabled()) {
            callback.invoke();
            return;
        }
        if (scanManager != null) {
            scanManager.stopScan(callback);
            WritableMap map = Arguments.createMap();
            map.putInt("status", 0);
            emitOnStopScan(map);
        }
    }


    @SuppressLint("MissingPermission")
    @ReactMethod
    public void createBond(String peripheralUUID, String peripheralPin, Callback callback) {
        PbscLog.d( "Request bond to: " + peripheralUUID);

        Set<BluetoothDevice> deviceSet = getBluetoothAdapter().getBondedDevices();
        for (BluetoothDevice device : deviceSet) {
            if (peripheralUUID.equalsIgnoreCase(device.getAddress())) {
                callback.invoke();
                return;
            }
        }

        Peripheral peripheral = retrieveOrCreatePeripheral(peripheralUUID);
        if (peripheral == null) {
            callback.invoke("Invalid peripheral uuid");
            return;
        } else if (bondRequest != null) {
            callback.invoke("Only allow one bond request at a time");
            return;
        } else if (peripheral.getDevice().createBond()) {
            PbscLog.d( "Request bond successful for: " + peripheralUUID);
            bondRequest = new BondRequest(peripheralUUID, peripheralPin, callback); // request bond success, waiting for broadcast
            return;
        }

        callback.invoke("Create bond request fail");
    }

    @ReactMethod
    public void removeBond(String peripheralUUID, Callback callback) {
        PbscLog.d( "Remove bond to: " + peripheralUUID);

        Peripheral peripheral = retrieveOrCreatePeripheral(peripheralUUID);
        if (peripheral == null) {
            callback.invoke("Invalid peripheral uuid");
            return;
        } else {
            try {
                Method m = peripheral.getDevice().getClass().getMethod("removeBond", (Class[]) null);
                m.invoke(peripheral.getDevice(), (Object[]) null);
                removeBondRequest = new BondRequest(peripheralUUID, callback);
                return;
            } catch (Exception e) {
                PbscLog.d( "Error in remove bond: " + peripheralUUID, e);
                callback.invoke("Remove bond request fail");
            }
        }

    }

    @ReactMethod
    public void connect(String peripheralUUID, ReadableMap options, Callback callback) {
        PbscLog.d( "Connect to: " + peripheralUUID);

        Peripheral peripheral = retrieveOrCreatePeripheral(peripheralUUID);
        if (peripheral == null) {
            callback.invoke("Invalid peripheral uuid");
            return;
        }
        startPbscService(new Intent(getReactApplicationContext(), PeripheralService.class)
                .putExtra("UUID", peripheralUUID)
                .putExtra("ACTION", "CONNECT")
                .putExtra("resultReciever", getReceiver(callback))
                .putExtra("eventReciever", getEventReciever()));
    }

    @ReactMethod
    public void disconnect(String peripheralUUID, boolean force, Callback callback) {
        PbscLog.d( "Disconnect from: " + peripheralUUID);

        Peripheral peripheral = peripherals.get(peripheralUUID);
        if (peripheral != null) {
            startPbscService(new Intent(getReactApplicationContext(), PeripheralService.class)
                    .putExtra("UUID", peripheralUUID)
                    .putExtra("ACTION", "DISCONNECT")
                    .putExtra("resultReciever", getReceiver(callback))
                    .putExtra("eventReciever", getEventReciever()));
        } else
            callback.invoke("Peripheral not found");
    }

    private static SharedPreferences getDefaultSharedPreferences(Context context) {
        Context appContext = context.getApplicationContext();
        return appContext.getSharedPreferences(
                appContext.getPackageName() + "_preferences",
                Context.MODE_PRIVATE);
    }

    @ReactMethod
    public void setServiceRecoveryData(ReadableMap data, Callback callback) {
        if (data != null) {
            try {
                getDefaultSharedPreferences(getReactApplicationContext())
                        .edit()
                        .putString("serviceRecoveryData", convertMapToJson(data).toString())
                        .commit();
            } catch (JSONException e) {
                callback.invoke("Write service recovery data failed due to JSONException");
                return;
            }
        } else {
            getDefaultSharedPreferences(getReactApplicationContext())
                    .edit()
                    .putString("serviceRecoveryData", new JsonObject().toString())
                    .commit();
        }
        callback.invoke();
    }

    @ReactMethod
    public void startNotificationWithBuffer(String deviceUUID, String serviceUUID, String characteristicUUID,
                                            double bufferLength, Callback callback) {
        PbscLog.d( "startNotification");
        if (serviceUUID == null || characteristicUUID == null) {
            callback.invoke("ServiceUUID and characteristicUUID required.");
            return;
        }
        // Validate UUID formats to prevent crash
        if (!UUIDHelper.isValidBLEUUID(serviceUUID)) {
            callback.invoke("Invalid service UUID format: " + serviceUUID);
            return;
        }
        if (!UUIDHelper.isValidBLEUUID(characteristicUUID)) {
            callback.invoke("Invalid characteristic UUID format: " + characteristicUUID);
            return;
        }
        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral != null) {
            peripheral.registerNotify(UUIDHelper.uuidFromString(serviceUUID),
                    UUIDHelper.uuidFromString(characteristicUUID), (int) bufferLength, callback);
        } else
            callback.invoke("Peripheral not found");
    }

    @ReactMethod
    public void startNotification(String deviceUUID, String serviceUUID, String characteristicUUID, Callback callback) {
        PbscLog.d( "startNotification");
        if (serviceUUID == null || characteristicUUID == null) {
            callback.invoke("ServiceUUID and characteristicUUID required.");
            return;
        }
        // Validate UUID formats to prevent crash
        if (!UUIDHelper.isValidBLEUUID(serviceUUID)) {
            callback.invoke("Invalid service UUID format: " + serviceUUID);
            return;
        }
        if (!UUIDHelper.isValidBLEUUID(characteristicUUID)) {
            callback.invoke("Invalid characteristic UUID format: " + characteristicUUID);
            return;
        }
        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral != null) {
            startPbscService(new Intent(getReactApplicationContext(), PeripheralService.class)
                    .putExtra("UUID", deviceUUID)
                    .putExtra("SERVICEUUID", serviceUUID)
                    .putExtra("CHARACTERISTICUUID", characteristicUUID)
                    .putExtra("ACTION", "STARTNOTIFICATION")
                    .putExtra("resultReciever", getReceiver(callback))
                    .putExtra("eventReciever", getEventReciever()));
        } else
            callback.invoke("Peripheral not found");
    }

    @ReactMethod
    public void stopNotification(String deviceUUID, String serviceUUID, String characteristicUUID, Callback callback) {
        PbscLog.d( "stopNotification");
        if (serviceUUID == null || characteristicUUID == null) {
            callback.invoke("ServiceUUID and characteristicUUID required.");
            return;
        }
        // Validate UUID formats to prevent crash
        if (!UUIDHelper.isValidBLEUUID(serviceUUID)) {
            callback.invoke("Invalid service UUID format: " + serviceUUID);
            return;
        }
        if (!UUIDHelper.isValidBLEUUID(characteristicUUID)) {
            callback.invoke("Invalid characteristic UUID format: " + characteristicUUID);
            return;
        }
        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral != null) {
            startPbscService(new Intent(getReactApplicationContext(), PeripheralService.class)
                    .putExtra("UUID", deviceUUID)
                    .putExtra("SERVICEUUID", serviceUUID)
                    .putExtra("CHARACTERISTICUUID", characteristicUUID)
                    .putExtra("ACTION", "STOPNOTIFICATION")
                    .putExtra("resultReciever", getReceiver(callback))
                    .putExtra("eventReciever", getEventReciever()));
        } else
            callback.invoke("Peripheral not found");
    }

    @ReactMethod
    public void write(String deviceUUID, String serviceUUID, String characteristicUUID, ReadableArray message,
                      double maxByteSize, Callback callback) {
        PbscLog.d( "Write to: " + deviceUUID);
        if (serviceUUID == null || characteristicUUID == null) {
            callback.invoke("ServiceUUID and characteristicUUID required.");
            return;
        }
        // Validate UUID formats to prevent crash
        if (!UUIDHelper.isValidBLEUUID(serviceUUID)) {
            callback.invoke("Invalid service UUID format: " + serviceUUID);
            return;
        }
        if (!UUIDHelper.isValidBLEUUID(characteristicUUID)) {
            callback.invoke("Invalid characteristic UUID format: " + characteristicUUID);
            return;
        }
        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral != null) {
            byte[] decoded = new byte[message.size()];
            for (int i = 0; i < message.size(); i++) {
                decoded[i] = Integer.valueOf(message.getInt(i)).byteValue();
            }
            String strMessage = bytesToHex(decoded);
            PbscLog.d( "Message(" + decoded.length + "): " + strMessage);
            startPbscService(new Intent(getReactApplicationContext(), PeripheralService.class)
                    .putExtra("UUID", deviceUUID)
                    .putExtra("SERVICEUUID", serviceUUID)
                    .putExtra("DECODED", decoded)
                    .putExtra("MESSAGE", strMessage)
                    .putExtra("MAXBYTESIZE", (int) maxByteSize)
                    .putExtra("CHARACTERISTICUUID", characteristicUUID)
                    .putExtra("ACTION", "WRITE")
                    .putExtra("resultReciever", getReceiver(callback))
                    .putExtra("eventReciever", getEventReciever()));
        } else
            callback.invoke("Peripheral not found");
    }

    @ReactMethod
    public void writeWithoutResponse(String deviceUUID, String serviceUUID, String characteristicUUID,
                                     ReadableArray message, double maxByteSize, double queueSleepTime, Callback callback) {
        PbscLog.d( "Write without response to: " + deviceUUID);
        if (serviceUUID == null || characteristicUUID == null) {
            callback.invoke("ServiceUUID and characteristicUUID required.");
            return;
        }
        // Validate UUID formats to prevent crash
        if (!UUIDHelper.isValidBLEUUID(serviceUUID)) {
            callback.invoke("Invalid service UUID format: " + serviceUUID);
            return;
        }
        if (!UUIDHelper.isValidBLEUUID(characteristicUUID)) {
            callback.invoke("Invalid characteristic UUID format: " + characteristicUUID);
            return;
        }
        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral != null) {
            byte[] decoded = new byte[message.size()];
            for (int i = 0; i < message.size(); i++) {
                decoded[i] = Integer.valueOf(message.getInt(i)).byteValue();
            }
            PbscLog.d( "Message(" + decoded.length + "): " + bytesToHex(decoded));
            startPbscService(new Intent(getReactApplicationContext(), PeripheralService.class)
                    .putExtra("UUID", deviceUUID)
                    .putExtra("SERVICEUUID", serviceUUID)
                    .putExtra("DECODED", decoded)
                    .putExtra("MAXBYTESIZE", (int) maxByteSize)
                    .putExtra("CHARACTERISTICUUID", characteristicUUID)
                    .putExtra("ACTION", "WRITEWITHOUTRESPONSE")
                    .putExtra("resultReciever", getReceiver(callback))
                    .putExtra("eventReciever", getEventReciever()));
        } else
            callback.invoke("Peripheral not found");
    }

    @ReactMethod
    public void read(String deviceUUID, String serviceUUID, String characteristicUUID, Callback callback) {
        PbscLog.d( "Read from: " + deviceUUID);
        if (serviceUUID == null || characteristicUUID == null) {
            callback.invoke("ServiceUUID and characteristicUUID required.");
            return;
        }
        // Validate UUID formats to prevent crash
        if (!UUIDHelper.isValidBLEUUID(serviceUUID)) {
            callback.invoke("Invalid service UUID format: " + serviceUUID);
            return;
        }
        if (!UUIDHelper.isValidBLEUUID(characteristicUUID)) {
            callback.invoke("Invalid characteristic UUID format: " + characteristicUUID);
            return;
        }
        ResultReceiver reciever = new ResultReceiver(MAIN_HANDLER) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                ArrayList args = new Gson().fromJson(resultData.getString("ARGS"), ArrayList.class);
                String paramsStr = resultData.getString("MAP");
                if (paramsStr != null) {
                    try {
                        WritableArray params = convertJsonToArray(new JSONArray(paramsStr));
                        if (args != null) {
                            args.add(params);
                        } else {
                            args = new ArrayList();
                            args.add(null);
                            args.add(params);
                        }
                    } catch (JSONException e) {
                        callback.invoke();
                        return;
                    }
                }
                if (args != null) {
                    callback.invoke(args.toArray(new Object[args.size()]));
                } else {
                    callback.invoke();
                }
            }
        };
        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral != null) {
            startPbscService(new Intent(getReactApplicationContext(), PeripheralService.class)
                    .putExtra("UUID", deviceUUID)
                    .putExtra("SERVICEUUID", serviceUUID)
                    .putExtra("CHARACTERISTICUUID", characteristicUUID)
                    .putExtra("ACTION", "READ")
                    .putExtra("resultReciever", reciever)
                    .putExtra("eventReciever", getEventReciever()));
        } else
            callback.invoke("Peripheral not found", null);
    }

    @ReactMethod
    public void readDescriptor(String deviceUUID, String serviceUUID, String characteristicUUID, String descriptorUUID, Callback callback) {
        PbscLog.d( "Read descriptor from: " + deviceUUID);
        if (serviceUUID == null || characteristicUUID == null || descriptorUUID == null) {
            callback.invoke("ServiceUUID, CharacteristicUUID and descriptorUUID required.", null);
            return;
        }
        // Validate UUID formats to prevent crash
        if (!UUIDHelper.isValidBLEUUID(serviceUUID)) {
            callback.invoke("Invalid service UUID format: " + serviceUUID, null);
            return;
        }
        if (!UUIDHelper.isValidBLEUUID(characteristicUUID)) {
            callback.invoke("Invalid characteristic UUID format: " + characteristicUUID, null);
            return;
        }
        if (!UUIDHelper.isValidBLEUUID(descriptorUUID)) {
            callback.invoke("Invalid descriptor UUID format: " + descriptorUUID, null);
            return;
        }

        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral == null) {
            callback.invoke("Peripheral not found", null);
        } else if (!peripheral.isConnected()) {
            callback.invoke("Peripheral not connected", null);
        } else {
            peripheral.readDescriptor(
                    UUIDHelper.uuidFromString(serviceUUID),
                    UUIDHelper.uuidFromString(characteristicUUID),
                    UUIDHelper.uuidFromString(descriptorUUID),
                    callback);
        }
    }

    @ReactMethod
    public void writeDescriptor(String deviceUUID, String serviceUUID, String characteristicUUID, String descriptorUUID, ReadableArray message, Callback callback) {
        PbscLog.d( "Write descriptor from: " + deviceUUID);
        if (serviceUUID == null || characteristicUUID == null || descriptorUUID == null) {
            callback.invoke("ServiceUUID, CharacteristicUUID and descriptorUUID required.", null);
            return;
        }
        // Validate UUID formats to prevent crash
        if (!UUIDHelper.isValidBLEUUID(serviceUUID)) {
            callback.invoke("Invalid service UUID format: " + serviceUUID, null);
            return;
        }
        if (!UUIDHelper.isValidBLEUUID(characteristicUUID)) {
            callback.invoke("Invalid characteristic UUID format: " + characteristicUUID, null);
            return;
        }
        if (!UUIDHelper.isValidBLEUUID(descriptorUUID)) {
            callback.invoke("Invalid descriptor UUID format: " + descriptorUUID, null);
            return;
        }

        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral == null) {
            callback.invoke("Peripheral not found", null);
        } else if (!peripheral.isConnected()) {
            callback.invoke("Peripheral not connected", null);
        } else {
            byte[] decoded = new byte[message.size()];
            for (int i = 0; i < message.size(); i++) {
                decoded[i] = Integer.valueOf(message.getInt(i)).byteValue();
            }
            PbscLog.d( "Message(" + decoded.length + "): " + bytesToHex(decoded));
            peripheral.writeDescriptor(UUIDHelper.uuidFromString(serviceUUID), UUIDHelper.uuidFromString(characteristicUUID), UUIDHelper.uuidFromString(descriptorUUID), decoded, callback);
        }
    }

    @ReactMethod
    public void retrieveServices(String deviceUUID, ReadableArray services, Callback callback) {
        PbscLog.d( "Retrieve services from: " + deviceUUID);
        ResultReceiver reciever = new ResultReceiver(MAIN_HANDLER) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                ArrayList args = new Gson().fromJson(resultData.getString("ARGS"), ArrayList.class);
                String paramsStr = resultData.getString("MAP");
                if (paramsStr != null) {
                    try {
                        WritableMap params = convertJsonToMap(new JSONObject(paramsStr));
                        if (args != null) {
                            args.add(params);
                        } else {
                            args = new ArrayList();
                            args.add(null);
                            args.add(params);
                        }
                    } catch (JSONException e) {
                        callback.invoke();
                        return;
                    }
                }
                if (args != null) {
                    callback.invoke(args.toArray(new Object[args.size()]));
                } else {
                    callback.invoke();
                }
            }
        };
        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral != null) {
            startPbscService(new Intent(getReactApplicationContext(), PeripheralService.class)
                    .putExtra("UUID", deviceUUID)
                    .putExtra("ACTION", "RETRIEVESERVICES")
                    .putExtra("resultReciever", reciever)
                    .putExtra("eventReciever", getEventReciever()));
        } else
            callback.invoke("Peripheral not found", null);
    }

    @ReactMethod
    public void refreshCache(String deviceUUID, Callback callback) {
        PbscLog.d( "Refreshing cache for: " + deviceUUID);
        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral != null) {
            startPbscService(new Intent(getReactApplicationContext(), PeripheralService.class)
                    .putExtra("UUID", deviceUUID)
                    .putExtra("ACTION", "REFRESHCACHE")
                    .putExtra("resultReciever", getReceiver(callback))
                    .putExtra("eventReciever", getEventReciever()));
        } else
            callback.invoke("Peripheral not found");
    }

    @ReactMethod
    public void readRSSI(String deviceUUID, Callback callback) {
        PbscLog.d( "Read RSSI from: " + deviceUUID);
        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral != null) {
            startPbscService(new Intent(getReactApplicationContext(), PeripheralService.class)
                    .putExtra("UUID", deviceUUID)
                    .putExtra("ACTION", "READRSSI")
                    .putExtra("resultReciever", getReceiver(callback))
                    .putExtra("eventReciever", getEventReciever()));
        } else
            callback.invoke("Peripheral not found", null);
    }

    public Peripheral savePeripheral(BluetoothDevice device) {
        String address = device.getAddress();
        synchronized (peripherals) {
            if (!peripherals.containsKey(address)) {
                Peripheral peripheral;
                if (!forceLegacy) {
                    peripheral = new DefaultPeripheral(device, this);
                } else {
                    peripheral = new Peripheral(device, this);
                }
                peripherals.put(device.getAddress(), peripheral);
            }
        }
        return peripherals.get(address);
    }

    public Peripheral getPeripheral(BluetoothDevice device) {
        String address = device.getAddress();
        return peripherals.get(address);
    }

    public Peripheral savePeripheral(Peripheral peripheral) {
        synchronized (peripherals) {
            peripherals.put(peripheral.getDevice().getAddress(), peripheral);
        }
        return peripheral;
    }

    @ReactMethod
    public void checkState(Callback callback) {
        PbscLog.d( "checkState");

        BluetoothAdapter adapter = getBluetoothAdapter();
        String state = "off";
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            state = "unsupported";
        } else if (adapter != null) {
            switch (adapter.getState()) {
                case BluetoothAdapter.STATE_ON:
                    state = "on";
                    break;
                case BluetoothAdapter.STATE_TURNING_ON:
                    state = "turning_on";
                    break;
                case BluetoothAdapter.STATE_TURNING_OFF:
                    state = "turning_off";
                    if (scanManager != null) {
                        scanManager.setScanning(false);
                    }
                    break;
                case BluetoothAdapter.STATE_OFF:
                default:
                    // should not happen as per https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getState()
                    state = "off";
                    if (scanManager != null) {
                        scanManager.setScanning(false);
                    }
                    break;
            }
        }

        WritableMap map = Arguments.createMap();
        map.putString("state", state);
        PbscLog.d( "state:" + state);
        emitOnDidUpdateState(map);
        callback.invoke(state);
    }

    @ReactMethod
    public void isScanning(Callback callback) {
        if (scanManager != null) {
            callback.invoke(null, scanManager.isScanning());
        } else {
            callback.invoke(null, false);
        }
    }

    @Override
    public void getMaximumWriteValueLengthForWithoutResponse(String peripheralUUID, Callback callback) {
        callback.invoke("Not implemented");
    }

    @Override
    public void getMaximumWriteValueLengthForWithResponse(String deviceUUID, Callback callback) {
        callback.invoke("Not implemented");
    }

    @ReactMethod
    @SuppressLint("MissingPermission")
    public void setName(String name) {
        BluetoothAdapter adapter = getBluetoothAdapter();
        adapter.setName(name);
    }

    private final BroadcastReceiver mReceiver = new MyBroadcastReceiver(this) {
    };

    private void clearPeripherals() {
        if (!peripherals.isEmpty()) {
            synchronized (peripherals) {
                peripherals.clear();
            }
        }
    }

    private void disconnectPeripherals() {
        if (!peripherals.isEmpty()) {
            synchronized (peripherals) {
                for (Peripheral peripheral : peripherals.values()) {
                    if (peripheral.isConnected()) {
                        peripheral.disconnect(null, true);
                    }
                    peripheral.errorAndClearAllCallbacks("disconnected by BleManager");
                    peripheral.resetQueuesAndBuffers();
                }
            }
        }
    }

    @ReactMethod
    public void getDiscoveredPeripherals(Callback callback) {
        PbscLog.d( "Get discovered peripherals");
        WritableArray map = Arguments.createArray();
        synchronized (peripherals) {
            for (Map.Entry<String, Peripheral> entry : peripherals.entrySet()) {
                Peripheral peripheral = entry.getValue();
                WritableMap jsonBundle = peripheral.asWritableMap();
                map.pushMap(jsonBundle);
            }
        }
        callback.invoke(null, map);
    }

    @SuppressLint("MissingPermission")
    @ReactMethod
    public void getConnectedPeripherals(ReadableArray serviceUUIDs, Callback callback) {
        PbscLog.d( "Get connected peripherals");
        WritableArray map = Arguments.createArray();

        if (getBluetoothAdapter() == null) {
            PbscLog.d( "No bluetooth support");
            callback.invoke("No bluetooth support");
            return;
        }

        List<BluetoothDevice> peripherals = getBluetoothManager().getConnectedDevices(GATT);
        for (BluetoothDevice entry : peripherals) {
            Peripheral peripheral = savePeripheral(entry);
            WritableMap jsonBundle = peripheral.asWritableMap();
            map.pushMap(jsonBundle);
        }
        callback.invoke(null, map);
    }

    @Override
    public void isPeripheralConnected(String deviceUUID, Callback callback) {
        PbscLog.d( "Checking connection state for: " + deviceUUID);
        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral != null) {
            callback.invoke(null, peripheral.isConnected());
        } else
            callback.invoke("Peripheral not found");
    }

    @SuppressLint("MissingPermission")
    @ReactMethod
    public void getBondedPeripherals(Callback callback) {
        PbscLog.d( "Get bonded peripherals");
        WritableArray map = Arguments.createArray();
        Set<BluetoothDevice> deviceSet = getBluetoothAdapter().getBondedDevices();
        for (BluetoothDevice device : deviceSet) {
            Peripheral peripheral;
            if (!forceLegacy) {
                peripheral = new DefaultPeripheral(device, this);
            } else {
                peripheral = new Peripheral(device, this);
            }
            WritableMap jsonBundle = peripheral.asWritableMap();
            map.pushMap(jsonBundle);
        }
        callback.invoke(null, map);
    }

    @ReactMethod
    public void removePeripheral(String deviceUUID, Callback callback) {
        PbscLog.d( "Removing from list: " + deviceUUID);
        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral != null) {
            synchronized (peripherals) {
                if (peripheral.isConnected()) {
                    callback.invoke("Peripheral can not be removed while connected");
                } else {
                    peripherals.remove(deviceUUID);
                    callback.invoke();
                }
            }
        } else
            callback.invoke("Peripheral not found");
    }

    @ReactMethod
    public void requestConnectionPriority(String deviceUUID, double connectionPriority, Callback callback) {
        PbscLog.d( "Request connection priority of " + connectionPriority + " from: " + deviceUUID);
        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral != null) {
            startPbscService(new Intent(getReactApplicationContext(), PeripheralService.class)
                    .putExtra("UUID", deviceUUID)
                    .putExtra("CONNECTIONPRIORITY", (int) connectionPriority)
                    .putExtra("ACTION", "REQUESTCONNECTIONPRIORITY")
                    .putExtra("resultReciever", getReceiver(callback))
                    .putExtra("eventReciever", getEventReciever()));
        } else {
            callback.invoke("Peripheral not found", null);
        }
    }

    @ReactMethod
    public void requestMTU(String deviceUUID, double mtu, Callback callback) {
        PbscLog.d( "Request MTU of " + mtu + " bytes from: " + deviceUUID);
        Peripheral peripheral = peripherals.get(deviceUUID);
        if (peripheral != null) {
            startPbscService(new Intent(getReactApplicationContext(), PeripheralService.class)
                    .putExtra("UUID", deviceUUID)
                    .putExtra("MTU", (int) mtu)
                    .putExtra("ACTION", "REQUESTMTU")
                    .putExtra("resultReciever", getReceiver(callback))
                    .putExtra("eventReciever", getEventReciever()));
        } else {
            callback.invoke("Peripheral not found", null);
        }
    }

    @ReactMethod
    public void getAssociatedPeripherals(Callback callback) {
        PbscLog.d( "Get associated peripherals");
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            callback.invoke("Not supported");
            return;
        }

        WritableArray peripherals = Arguments.createArray();
        for (String address : ((CompanionDeviceManager) getCompanionDeviceManager()).getAssociations()) {
            peripherals.pushMap(retrieveOrCreatePeripheral(address).asWritableMap());
        }

        callback.invoke(null, peripherals);
    }

    @ReactMethod
    public void removeAssociatedPeripheral(String address, Callback callback) {
        PbscLog.d( "Remove associated peripheral: " + address);
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            callback.invoke("Not supported");
            return;
        }

        CompanionDeviceManager manager = (CompanionDeviceManager) getCompanionDeviceManager();
        for (String association : manager.getAssociations()) {
            if (association.equals(address)) {
                manager.disassociate(address);
                callback.invoke();
                return;
            }
        }

        callback.invoke("device not found");
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public Object getCompanionDeviceManager() {
        return reactContext
                .getCurrentActivity().getSystemService(Context.COMPANION_DEVICE_SERVICE);
    }

    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static WritableArray bytesToWritableArray(byte[] bytes) {
        WritableArray value = Arguments.createArray();
        for (int i = 0; i < bytes.length; i++)
            value.pushInt((bytes[i] & 0xFF));
        return value;
    }


    private Peripheral retrieveOrCreatePeripheral(String peripheralUUID) {
        Peripheral peripheral = peripherals.get(peripheralUUID);
        if (peripheral == null) {
            synchronized (peripherals) {
                if (peripheralUUID != null) {
                    peripheralUUID = peripheralUUID.toUpperCase();
                }
                if (BluetoothAdapter.checkBluetoothAddress(peripheralUUID)) {
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(peripheralUUID);
                    if (!forceLegacy) {
                        peripheral = new DefaultPeripheral(device, this);
                    } else {
                        peripheral = new Peripheral(device, this);
                    }
                    peripherals.put(peripheralUUID, peripheral);
                }
            }
        }
        return peripheral;
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Keep: Required for RN built in Event Emitter Calls.
    }

    @ReactMethod
    public void removeListeners(double count) {
        // Keep: Required for RN built in Event Emitter Calls.
    }

    @Override
    public void invalidate() {
        reactContext.removeLifecycleEventListener(hostLifecycleListener);
        try {
            context.unregisterReceiver(mReceiver);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Receiver not registered or already unregistered", e);
        }
        try {
            // Disconnect all known peripherals, otherwise android system will think we are still connected
            // while we have lost the gatt instance
            disconnectPeripherals();
        } catch (Exception e) {
            PbscLog.d( "Could not disconnect peripherals", e);
        }

        if (scanManager != null) {
            // Stop scan in case one was started to stop events from being emitted after destroy
            scanManager.stopScan(args -> {
            });
        }
    }

    private static WritableMap convertJsonToMap(JSONObject jsonObject) throws JSONException {
        WritableMap map = new WritableNativeMap();
        Iterator<String> iterator = jsonObject.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            Object value = jsonObject.get(key);
            if (value instanceof JSONObject) {
                map.putMap(key, convertJsonToMap((JSONObject) value));
            } else if (value instanceof JSONArray) {
                map.putArray(key, convertJsonToArray((JSONArray) value));
            } else if (value instanceof Boolean) {
                map.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                map.putInt(key, (Integer) value);
            } else if (value instanceof Double) {
                map.putDouble(key, (Double) value);
            } else if (value instanceof String) {
                map.putString(key, (String) value);
            } else {
                map.putString(key, value.toString());
            }
        }
        return map;
    }

    private static WritableArray convertJsonToArray(JSONArray jsonArray) throws JSONException {
        WritableArray array = new WritableNativeArray();
        for (int i = 0; i < jsonArray.length(); i++) {
            Object value = jsonArray.get(i);
            if (value instanceof JSONObject) {
                array.pushMap(convertJsonToMap((JSONObject) value));
            } else if (value instanceof JSONArray) {
                array.pushArray(convertJsonToArray((JSONArray) value));
            } else if (value instanceof Boolean) {
                array.pushBoolean((Boolean) value);
            } else if (value instanceof Integer) {
                array.pushInt((Integer) value);
            } else if (value instanceof Double) {
                array.pushDouble((Double) value);
            } else if (value instanceof String) {
                array.pushString((String) value);
            } else {
                array.pushString(value.toString());
            }
        }
        return array;
    }

    private static JSONObject convertMapToJson(ReadableMap readableMap) throws JSONException {
        JSONObject object = new JSONObject();
        ReadableMapKeySetIterator iterator = readableMap.keySetIterator();
        while (iterator.hasNextKey()) {
            String key = iterator.nextKey();
            switch (readableMap.getType(key)) {
                case Null:
                    object.put(key, JSONObject.NULL);
                    break;
                case Boolean:
                    object.put(key, readableMap.getBoolean(key));
                    break;
                case Number:
                    object.put(key, readableMap.getDouble(key));
                    break;
                case String:
                    object.put(key, readableMap.getString(key));
                    break;
                case Map:
                    object.put(key, convertMapToJson(readableMap.getMap(key)));
                    break;
                case Array:
                    object.put(key, convertArrayToJson(readableMap.getArray(key)));
                    break;
            }
        }
        return object;
    }

    private static JSONArray convertArrayToJson(ReadableArray readableArray) throws JSONException {
        JSONArray array = new JSONArray();
        for (int i = 0; i < readableArray.size(); i++) {
            switch (readableArray.getType(i)) {
                case Null:
                    break;
                case Boolean:
                    array.put(readableArray.getBoolean(i));
                    break;
                case Number:
                    array.put(readableArray.getDouble(i));
                    break;
                case String:
                    array.put(readableArray.getString(i));
                    break;
                case Map:
                    array.put(convertMapToJson(readableArray.getMap(i)));
                    break;
                case Array:
                    array.put(convertArrayToJson(readableArray.getArray(i)));
                    break;
            }
        }
        return array;
    }

}
