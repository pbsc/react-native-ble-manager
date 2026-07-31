package it.innove;

import android.util.Log;

final class PbscLog {
    private PbscLog() {
    }

    static void d(String message) {
        if (BuildConfig.DEBUG) {
            Log.d(BleManager.LOG_TAG, message);
        }
    }

    static void d(String message, Throwable throwable) {
        if (BuildConfig.DEBUG) {
            Log.d(BleManager.LOG_TAG, message, throwable);
        }
    }
}
