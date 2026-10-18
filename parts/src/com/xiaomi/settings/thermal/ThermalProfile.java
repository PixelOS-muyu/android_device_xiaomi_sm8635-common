/*
 * Copyright (C) 2026 The PixelOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.xiaomi.settings.R;

import java.io.FileWriter;
import java.io.IOException;

/**
 * Switches the Xiaomi mi_thermald profile through
 * /sys/class/thermal/thermal_message/sconfig.
 *
 * Profile ids were mapped on muyu (OS3.0.7.0 thermal configs):
 *    0      normal    big cores throttled from 37 C in 10 steps (-1 is ignored)
 *   18      tgame     no CPU / GPU throttling until 48 C
 *    6      nolimits  no CPU / GPU throttling until 51 C
 */
public enum ThermalProfile {
    BALANCED(0, R.string.thermal_profile_balanced),
    PERFORMANCE(18, R.string.thermal_profile_performance),
    MAXIMUM(6, R.string.thermal_profile_maximum);

    private static final String TAG = "XiaomiPartsThermal";
    private static final String SCONFIG = "/sys/class/thermal/thermal_message/sconfig";
    private static final String PREFS = "thermal";
    private static final String KEY_PROFILE = "profile";

    public final int sconfig;
    public final int label;

    ThermalProfile(int sconfig, int label) {
        this.sconfig = sconfig;
        this.label = label;
    }

    public ThermalProfile next() {
        ThermalProfile[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static ThermalProfile get(Context context) {
        String name = prefs(context).getString(KEY_PROFILE, BALANCED.name());
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return BALANCED;
        }
    }

    public static void set(Context context, ThermalProfile profile) {
        prefs(context).edit().putString(KEY_PROFILE, profile.name()).apply();
        profile.apply();
    }

    /** Re-applies the stored profile, mi_thermald starts with the normal one. */
    public static void restore(Context context) {
        get(context).apply();
    }

    public void apply() {
        try (FileWriter writer = new FileWriter(SCONFIG)) {
            writer.write(Integer.toString(sconfig));
            Log.i(TAG, "Thermal profile " + name() + " (sconfig " + sconfig + ")");
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + SCONFIG, e);
        }
    }
}
