package com.inpulync.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the process (and therefore the Bluetooth HID registration) alive while the app is
 * in the background, so the PC stays connected when you switch apps or turn the screen off.
 */
public class HidService extends Service {
    static final String ACTION_STOP = "com.inpulync.app.STOP_HID";
    private static final String CHANNEL = "hid_link";
    private static final int NOTIFICATION_ID = 41;
    private static volatile boolean running;

    private BluetoothHidController controller;

    static boolean isRunning() {
        return running;
    }

    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, HidService.class));
        } catch (RuntimeException e) {
            // Not allowed right now (permission or background limits): fall back to
            // foreground-only behaviour instead of crashing.
            Log.w("InpuLync", "Could not start background link", e);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, HidService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        controller = HidHub.get(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Background input link", NotificationManager.IMPORTANCE_LOW));
        Notification notification = buildNotification(controller.shortStatus());
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            Log.w("InpuLync", "Foreground start refused", e);
            stopSelf();
            return;
        }
        running = true;
        controller.setForeground(true);
        controller.setObserver(this::refreshNotification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences("controls", MODE_PRIVATE).edit()
                    .putInt("active_mode", 0).apply();
            controller.setActive(false);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (controller != null) {
            controller.setObserver(null);
            // If no screen is attached any more, nothing else needs the link: release it.
            if (!controller.hasListener()) HidHub.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void refreshNotification() {
        if (!running || controller == null) return;
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildNotification(controller.shortStatus()));
    }

    private Notification buildNotification(String status) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, HidService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("InpuLync")
                .setContentText(status)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Stop", stop).build());
        if (Build.VERSION.SDK_INT >= 31)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }
}
