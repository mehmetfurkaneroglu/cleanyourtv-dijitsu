package com.gathin.tvhome;

import android.content.Context;
import android.provider.Settings;
import android.widget.Toast;

/**
 * The More menu's DNS row (more_menu): Android's Private DNS on an ad-blocking resolver, or back to the default
 * automatic mode when an app stops playing because a tracking domain is blocked. Like AdbSwitch it writes
 * Settings.Global, so it needs the same one-time WRITE_SECURE_SETTINGS grant.
 */
final class DnsSwitch {
    static final String HOST = "dns.adguard-dns.com";
    private static final String MODE = "private_dns_mode";
    private static final String SPECIFIER = "private_dns_specifier";

    private DnsSwitch() {
    }

    static boolean isOn(Context context) {
        return "hostname".equals(Settings.Global.getString(context.getContentResolver(), MODE));
    }

    /** Flips Private DNS between HOST and automatic; false (with the grant toast) when the permission is missing. */
    static boolean toggle(Context context) {
        try {
            if (isOn(context)) {
                Settings.Global.putString(context.getContentResolver(), MODE, "opportunistic");
            } else {
                Settings.Global.putString(context.getContentResolver(), SPECIFIER, HOST);
                Settings.Global.putString(context.getContentResolver(), MODE, "hostname");
            }
            return true;
        } catch (SecurityException e) {
            Toast.makeText(context, R.string.adb_no_permission, Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
