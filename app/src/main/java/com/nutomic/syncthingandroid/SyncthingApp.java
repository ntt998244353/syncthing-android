package com.nutomic.syncthingandroid;

import android.app.Application;
import android.os.Build;
import android.os.StrictMode;
import android.util.Log;

import javax.inject.Inject;

public class SyncthingApp extends Application {

    @Inject DaggerComponent mComponent;

    @Override
    public void onCreate() {
        super.onCreate();

        DaggerDaggerComponent.builder()
                .syncthingModule(new SyncthingModule(this))
                .build()
                .inject(this);

        /*
         * Shizuku hands its binder over through a broadcast
         * (ShizukuProvider.ACTION_BINDER_RECEIVED). The ShizukuProvider ContentProvider only
         * validates its own declaration in attachInfo(), so every process that wants to talk to
         * Shizuku has to subscribe on its own. Doing it here guarantees the binder is available
         * before any settings screen or service asks for it - without this, Shizuku.pingBinder()
         * always stays false and the permission dialog can never be shown.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(this);
            } catch (Throwable t) {
                Log.w("SyncthingApp", "Failed to request the Shizuku binder", t);
            }
        }

        // Set VM policy to avoid crash when sending folder URI to file manager.
        StrictMode.VmPolicy vmPolicy = new StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build();
        StrictMode.setVmPolicy(vmPolicy);

        /*
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
        {
            StrictMode.ThreadPolicy threadPolicy = new StrictMode.ThreadPolicy.Builder()
                .permitAll()
                .build();
            StrictMode.setThreadPolicy(threadPolicy);
        }
        */
    }

    public DaggerComponent component() {
        return mComponent;
    }
}
