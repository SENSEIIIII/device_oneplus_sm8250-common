/*
 * Copyright (C) 2016 The OmniROM Project
 *               2022 The Evolution X Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.lineageos.settings.modeswitch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.Preference.OnPreferenceChangeListener;

import org.lineageos.settings.FileUtils;
import org.lineageos.settings.R;
import android.os.Handler;
import android.os.Looper;
import org.lineageos.settings.OPlusExtras;
import org.lineageos.settings.services.AutoHBMService;

public class OnePulsePWMSwitch implements OnPreferenceChangeListener {

    private static final int NODE = R.string.node_onepulse_pwm_switch;

    public static String getFile(Context context) {
        String file = context.getResources().getString(NODE);
        if (FileUtils.fileWritable(file)) {
            return file;
        }
        return null;
    }

    public static boolean isSupported(Context context) {
        return FileUtils.fileWritable(getFile(context));
    }

    public static boolean isCurrentlyEnabled(Context context) {
        return FileUtils.getFileValueAsBoolean(getFile(context), false,
            context.getResources().getString(R.string.node_onepulse_pwm_switch_true),
            context.getResources().getString(R.string.node_onepulse_pwm_switch_false));
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        Boolean enabled = (Boolean) newValue;
        Context context = preference.getContext();
        FileUtils.writeValue(getFile(preference.getContext()), enabled ? "1" : "0");
        if (enabled) {
            Handler h = new Handler(Looper.getMainLooper());
            h.post(() -> {
                if (OPlusExtras.mAutoHBMSwitch != null && OPlusExtras.mAutoHBMSwitch.isChecked()) {
                Log.d("OnePulsePWMSwitch", "Disabling AutoHBM because OnePulse is being enabled.");

                OPlusExtras.mAutoHBMSwitch.setChecked(false);

                SharedPreferences.Editor prefChange = PreferenceManager.getDefaultSharedPreferences(context).edit();
                prefChange.putBoolean(OPlusExtras.KEY_AUTO_HBM_SWITCH, false).commit();

                Intent intent = new Intent(context, AutoHBMService.class);
                context.stopService(intent);
            }
            });
        }
        return true;
    }
}
