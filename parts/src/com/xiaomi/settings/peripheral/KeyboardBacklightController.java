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
import android.os.SystemProperties;
import android.util.Log;
import android.view.InputDevice;

/**
 * Adaptive keyboard backlight: maps the ambient light to a backlight level and hands it
 * to the xiaomi-keyboard vendor daemon, which also switches the light off while idle.
 * The light sensor is only used while the keyboard is attached and the screen is on.
 */
public final class KeyboardBacklightController
        implements InputManager.InputDeviceListener, SensorEventListener {

    private static final String TAG = "XiaomiPartsKbdBacklight";
    private static final String PROP_LEVEL = "sys.xiaomi.kbd_backlight";

    private static final int KEYBOARD_VENDOR = 0x15d9;
    private static final int KEYBOARD_PRODUCT = 0x00a3;

    // Same curve as HyperOS (BackLightFeature): the ambient light is clamped to 0..50 lux
    // and split into 5 lux steps, each step maps to a fraction of the full backlight.
    private static final float MAX_LUX = 50f;
    private static final float LUX_PER_STEP = 5f;
    private static final float[] STEP_FACTOR =
            {0.6f, 0.8f, 1.0f, 0.8f, 0.7f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f, 0f};
    // Stay on the current step until the light is this far past the rounding border,
    // so sensor noise around a border does not make the backlight flicker.
    private static final float HYSTERESIS_LUX = 1f;

    private static KeyboardBacklightController sInstance;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final InputManager mInputManager;
    private final SensorManager mSensorManager;
    private final Sensor mLightSensor;

    private boolean mScreenOn = true;
    private boolean mKeyboardPresent;
    private boolean mListening;
    private int mZone = -1;
    private int mLevel = -1;

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
        mContext = context;
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

    private void update() {
        boolean listen = mKeyboardPresent && mScreenOn;
        if (listen == mListening) return;
        mListening = listen;
        if (listen) {
            mZone = -1;
            mSensorManager.registerListener(this, mLightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            Log.d(TAG, "Listening to ambient light");
        } else {
            mSensorManager.unregisterListener(this);
            setLevel(0);
            Log.d(TAG, "Stopped listening to ambient light");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float lux = Math.max(0f, Math.min(event.values[0], MAX_LUX));
        int step = Math.round(lux / LUX_PER_STEP);
        if (step == mZone) return;
        if (mZone >= 0) {
            // Border between the current step and its neighbour in that direction.
            float border = (mZone + (step > mZone ? 0.5f : -0.5f)) * LUX_PER_STEP;
            if (Math.abs(lux - border) < HYSTERESIS_LUX) return;
        }
        mZone = step;
        int level = Math.round(STEP_FACTOR[step] * 100);
        setLevel(level);
        Log.d(TAG, "Ambient " + event.values[0] + " lux -> backlight " + level);
    }

    private void setLevel(int level) {
        if (level == mLevel) return;
        mLevel = level;
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
