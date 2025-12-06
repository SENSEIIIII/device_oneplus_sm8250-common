package org.lineageos.settings.services;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import androidx.preference.PreferenceManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import android.os.Handler;
import android.os.Looper;

import org.lineageos.settings.OPlusExtras;
import org.lineageos.settings.FileUtils;
import org.lineageos.settings.R;

public class AutoHBMService extends Service {
    private static final int HBM_NODE = R.string.node_hbm_mode_switch;
    private static final String TAG = "AutoHBMService";

    private static boolean mAutoHBMActive = false;
    private ExecutorService mExecutorService;

    private SensorManager mSensorManager;
    Sensor mLightSensor;

    private SharedPreferences mSharedPrefs;
    private static final String PREF_SAVED_MIN_RATE = "pref_saved_min_rate";
    private static final String PREF_SAVED_PEAK_RATE = "pref_saved_peak_rate";
    private static final String PREF_HAS_SAVED_RATES = "pref_has_saved_rates";
    private static final String PREF_SAVED_BRIGHTNESS_MODE = "pref_saved_brightness_mode";
    private static final String PREF_HAS_SAVED_BRIGHTNESS_MODE = "pref_has_saved_brightness_mode";

    public void activateLightSensorRead() {
        submit(() -> {
        mSensorManager = (SensorManager) getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        mSensorManager.registerListener(mSensorEventListener, mLightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        });
    }

    public void deactivateLightSensorRead() {
        submit(() -> {
        mSensorManager.unregisterListener(mSensorEventListener);
        mAutoHBMActive = false;
        enableHBM(false);
        });
    }

    private String getFile() {
        String file = getApplicationContext().getString(HBM_NODE);
        if (FileUtils.fileWritable(file)) {
            return file;
        }
        return null;
    }

    private void enableHBM(boolean enable) {
        submit(() -> {
            if (enable) {
                try {
                    int mode = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    boolean hasSavedMode = mSharedPrefs.getBoolean(PREF_HAS_SAVED_BRIGHTNESS_MODE, false);
                    if (!hasSavedMode && mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                        mSharedPrefs.edit()
                                .putInt(PREF_SAVED_BRIGHTNESS_MODE, mode)
                                .putBoolean(PREF_HAS_SAVED_BRIGHTNESS_MODE, true)
                                .apply();
                        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                        Log.d(TAG, "Disabled automatic brightness mode for HBM");
                    }

                    String onepulseFile = getApplicationContext().getString(R.string.node_onepulse_pwm_switch);
                    if (onepulseFile != null && FileUtils.fileWritable(onepulseFile)) {
                        String oneFalse = getApplicationContext().getString(R.string.node_onepulse_pwm_switch_false);
                        Log.d(TAG, "Disabling PWM OnePulse: writing " + oneFalse + " to " + onepulseFile);
                        FileUtils.writeValue(onepulseFile, oneFalse);
                    }

                    boolean hasSaved = mSharedPrefs.getBoolean(PREF_HAS_SAVED_RATES, false);
                    if (!hasSaved) {
                        float curMin = Settings.System.getFloat(getContentResolver(), Settings.System.MIN_REFRESH_RATE, 60f);
                        float curPeak = Settings.System.getFloat(getContentResolver(), Settings.System.PEAK_REFRESH_RATE, 60f);
                        Log.d(TAG, "Saving current refresh rates: min=" + curMin + " peak=" + curPeak);
                        mSharedPrefs.edit()
                                .putFloat(PREF_SAVED_MIN_RATE, curMin)
                                .putFloat(PREF_SAVED_PEAK_RATE, curPeak)
                                .putBoolean(PREF_HAS_SAVED_RATES, true)
                                .apply();
                    }
                    float peak = Settings.System.getFloat(getContentResolver(), Settings.System.PEAK_REFRESH_RATE, 60f);
                    Log.d(TAG, "Setting refresh rates to peak=" + peak);
                    Settings.System.putFloat(getContentResolver(), Settings.System.MIN_REFRESH_RATE, peak);
                    Settings.System.putFloat(getContentResolver(), Settings.System.PEAK_REFRESH_RATE, peak);
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    Log.d(TAG, "Enabling HBM: writing node 1 to " + getFile());
                    FileUtils.writeValue(getFile(), "1");

                } catch (Exception e) {
                    Log.d(TAG, "Exception while enabling HBM: " + e.getMessage());
                }
            } else {
                Log.d(TAG, "Disabling HBM: writing node 0 to " + getFile());
                FileUtils.writeValue(getFile(), "0");
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                try {
                    boolean hasSavedMode = mSharedPrefs.getBoolean(PREF_HAS_SAVED_BRIGHTNESS_MODE, false);
                    if (hasSavedMode) {
                        int savedMode = mSharedPrefs.getInt(PREF_SAVED_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, savedMode);
                        mSharedPrefs.edit().putBoolean(PREF_HAS_SAVED_BRIGHTNESS_MODE, false).apply();
                        Log.d(TAG, "Restored automatic brightness mode=" + savedMode);
                    }

                    boolean hasSaved = mSharedPrefs.getBoolean(PREF_HAS_SAVED_RATES, false);
                    if (hasSaved) {
                        float savedMin = mSharedPrefs.getFloat(PREF_SAVED_MIN_RATE, 60f);
                        float savedPeak = mSharedPrefs.getFloat(PREF_SAVED_PEAK_RATE, 60f);
                        Log.d(TAG, "Restoring saved refresh rates: min=" + savedMin + " peak=" + savedPeak);
                        Settings.System.putFloat(getContentResolver(), Settings.System.MIN_REFRESH_RATE, savedMin);
                        Settings.System.putFloat(getContentResolver(), Settings.System.PEAK_REFRESH_RATE, savedPeak);
                        mSharedPrefs.edit().putBoolean(PREF_HAS_SAVED_RATES, false).apply();
                        Log.d(TAG, "Cleared saved refresh rates flag");
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Exception while restoring settings: " + e.getMessage());
                }
            }
        });
    }

    private boolean isCurrentlyEnabled() {
        return FileUtils.getFileValueAsBoolean(getFile(), false);
    }

    SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            float lux = event.values[0];
            KeyguardManager km =
                    (KeyguardManager) getSystemService(getApplicationContext().KEYGUARD_SERVICE);
            boolean keyguardShowing = km.inKeyguardRestrictedInputMode();
            float threshold = Float.parseFloat(mSharedPrefs.getString(OPlusExtras.KEY_AUTO_HBM_THRESHOLD, "10000"));
            if (lux > threshold) {
                if ((!mAutoHBMActive | !isCurrentlyEnabled()) && !keyguardShowing) {
                    // ensure user didn't disable AutoHBM while this callback was queued
                    if (!mSharedPrefs.getBoolean(OPlusExtras.KEY_AUTO_HBM_SWITCH, false)) {
                        return;
                    }
                    mAutoHBMActive = true;
                    enableHBM(true);
                }
            }
            if (lux < threshold) {
                if (mAutoHBMActive) {
                    mAutoHBMActive = false;
                    enableHBM(false);
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // do nothing
        }
    };

    private BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                activateLightSensorRead();
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                // disable HBM immediately when screen turns off (user locking)
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    enableHBM(false);
                    deactivateLightSensorRead();
                }, 300);
            }
        }
    };

    @Override
    public void onCreate() {
        mExecutorService = Executors.newSingleThreadExecutor();
        IntentFilter screenStateFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenStateReceiver, screenStateFilter);
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm.isInteractive()) {
            activateLightSensorRead();
        }
    }

    private Future<?> submit(Runnable runnable) {
        return mExecutorService.submit(runnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mScreenStateReceiver);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm.isInteractive()) {
            deactivateLightSensorRead();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
