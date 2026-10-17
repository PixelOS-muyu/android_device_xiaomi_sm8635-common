/*
 * Copyright (C) 2026 The PixelOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class PerformanceTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile(ThermalProfile.get(this));
    }

    @Override
    public void onClick() {
        super.onClick();
        ThermalProfile profile = ThermalProfile.get(this).next();
        ThermalProfile.set(this, profile);
        updateTile(profile);
    }

    private void updateTile(ThermalProfile profile) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(profile == ThermalProfile.BALANCED ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        tile.setSubtitle(getString(profile.label));
        tile.updateTile();
    }
}
