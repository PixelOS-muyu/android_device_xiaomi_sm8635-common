/*
 * Copyright (C) 2026 The PixelOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.peripheral;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import android.view.InputDevice;

/**
 * Adaptive keyboard backlight, modelled on HyperOS (miui-services BackLightFeature):
 * maps the ambient light to a backlight level and hands it to the xiaomi-keyboard vendor
 * daemon, which fades to it over 2 s. Idle handling is done by the keyboard itself.
 * The light sensor is only used while the keyboard is attached and the screen is on.
 */
public final class KeyboardBacklightController
        implements InputManager.InputDeviceListener, SensorEventListener {

    private static final String TAG = "XiaomiPartsKbdBacklight";
    private static final String PROP_LEVEL = "sys.xiaomi.kbd_backlight";

    private static final int KEYBOARD_VENDOR = 0x15d9;
    private static final int KEYBOARD_PRODUCT = 0x00a3;

    // getBackLightBrightnessWithSensor(): ambient light clamped to 0..50 lux, split into
    // 5 lux steps, each step maps to a fraction of the full backlight.
    private static final float MAX_LUX = 50f;
    private static final float LUX_PER_STEP = 5f;
    private static final float[] STEP_FACTOR =
            {0.6f, 0.8f, 1.0f, 0.8f, 0.7f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f, 0f};

    // BackLightAnimator jitter check: changes closer together than ADJUST_TIME_GAP that
    // go both ways more than FLUCTUATION_TIMES times are applied with a delay.
    private static final long ADJUST_TIME_GAP = 1000;
    private static final int FLUCTUATION_TIMES = 3;

    private static KeyboardBacklightController sInstance;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final InputManager mInputManager;
    private final SensorManager mSensorManager;
    private final Sensor mLightSensor;

    private boolean mScreenOn = true;
    private boolean mKeyboardPresent;
    private boolean mListening;

    private int mTarget = -1;
    private long mRequestTime;
    private int mContinueLight;
    private int mContinueDark;
    private final Runnable mApply = () -> setLevel(mTarget);

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mScreenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            update();
        }
    };

    public static synchronized void start(Context context) {
        if (sInstance == null) {
            sInstance = new KeyboardBacklightController(context.getApplicationContext());
        }
    }

    private KeyboardBacklightController(Context context) {
        mInputManager = context.getSystemService(InputManager.class);
        mSensorManager = context.getSystemService(SensorManager.class);
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (mLightSensor == null) {
            Log.w(TAG, "No light sensor, adaptive keyboard backlight disabled");
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(mScreenReceiver, filter, Context.RECEIVER_EXPORTED);
        mInputManager.registerInputDeviceListener(this, mHandler);
        mKeyboardPresent = findKeyboard();
        update();
    }

    private boolean findKeyboard() {
        for (int id : mInputManager.getInputDeviceIds()) {
            InputDevice device = mInputManager.getInputDevice(id);
            if (device != null && device.getVendorId() == KEYBOARD_VENDOR
                    && device.getProductId() == KEYBOARD_PRODUCT) {
                return true;
            }
        }
        return false;
    }

    // onScreenStatusChanged(): only (un)register the sensor, the backlight is left as is.
    private void update() {
        boolean listen = mKeyboardPresent && mScreenOn;
        if (listen == mListening) return;
        mListening = listen;
        if (listen) {
            mTarget = -1;
            mSensorManager.registerListener(this, mLightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            Log.d(TAG, "Listening to ambient light");
        } else {
            mSensorManager.unregisterListener(this);
            mHandler.removeCallbacks(mApply);
            Log.d(TAG, "Stopped listening to ambient light");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float lux = Math.max(0f, Math.min(event.values[0], MAX_LUX));
        int step = Math.max(0, Math.min(Math.round(lux / LUX_PER_STEP), STEP_FACTOR.length - 1));
        request(Math.round(STEP_FACTOR[step] * 100), event.values[0]);
    }

    // BackLightAnimator.doAnimation()
    private void request(int target, float lux) {
        if (target == mTarget) return;
        long now = SystemClock.uptimeMillis();
        int last = mTarget;
        long lastTime = mRequestTime;
        mTarget = target;
        mRequestTime = now;
        long delay = 0;
        if (now - lastTime < ADJUST_TIME_GAP) {
            if (target > last) {
                mContinueLight++;
            } else if (target < last) {
                mContinueDark++;
            }
            if (mContinueLight * mContinueDark != 0
                    && mContinueLight + mContinueDark > FLUCTUATION_TIMES) {
                delay = ADJUST_TIME_GAP;
            }
        } else {
            mContinueLight = 0;
            mContinueDark = 0;
        }
        mHandler.removeCallbacks(mApply);
        mHandler.postDelayed(mApply, delay);
        Log.d(TAG, "Ambient " + lux + " lux -> backlight " + target
                + (delay > 0 ? " (jitter, delayed)" : ""));
    }

    private void setLevel(int level) {
        if (level < 0) return;
        try {
            SystemProperties.set(PROP_LEVEL, Integer.toString(level));
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to set " + PROP_LEVEL, e);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onInputDeviceAdded(int deviceId) {
        mKeyboardPresent = findKeyboard();
        update();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        mKeyboardPresent = findKeyboard();
        update();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {}
}
