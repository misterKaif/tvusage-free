package com.tvusage.free.util;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Receiver enabling Device Administrator capabilities.
 * Allows remote screen locking/sleeping and prevents accidental app uninstallation by children.
 */
public class TvDeviceAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context, "TVUsage: Права администратора ТВ активированы", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context, "TVUsage: Права администратора отключены", Toast.LENGTH_SHORT).show();
    }
}
