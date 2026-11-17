package com.ustreaming.setup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Shows the app icon again when Setup is installed again over a hidden copy. */
public class ReinstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SetupActivity.setIconVisible(context, true);
    }
}
