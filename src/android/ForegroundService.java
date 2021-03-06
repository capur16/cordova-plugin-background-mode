/*
    Copyright 2013-2017 appPlant GmbH

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */

package de.appplant.cordova.plugin.background;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import it.peopletrust.octobike.R;

import static android.os.PowerManager.PARTIAL_WAKE_LOCK;

/**
 * Puts the service in a foreground state, where the system considers it to be
 * something the user is actively aware of and thus not a candidate for killing
 * when low on memory.
 */
public class ForegroundService extends Service {
  private static final String TAG = "ForegroundService";
  private static JSONObject defaultSettings = new JSONObject();

  // Fixed ID for the 'foreground' notification
  public static final int NOTIFICATION_ID = -574543954;

  // Default title of the background notification
  private static final String NOTIFICATION_TITLE =
    "App is running in background";

  // Default text of the background notification
  private static final String NOTIFICATION_TEXT =
    "Doing heavy tasks.";

  // Default icon of the background notification
  private static final String NOTIFICATION_ICON = "icon";

  // Binder given to clients
  private final IBinder mBinder = new ForegroundBinder();

  // Partial wake lock to prevent the app from going to sleep when locked
  private PowerManager.WakeLock wakeLock;

  static final String CHANNEL_ID = "it.peopletrust.octobike.BikeBT";

  /**
   * Allow clients to call on to the service.
   */
  @Override
  public IBinder onBind (Intent intent) {
    return mBinder;
  }

  /**
   * Class used for the client Binder.  Because we know this service always
   * runs in the same process as its clients, we don't need to deal with IPC.
   */
  public class ForegroundBinder extends Binder {
    ForegroundService getService() {
      // Return this instance of ForegroundService
      // so clients can call public methods
      return ForegroundService.this;
    }
  }

  /**
   * Put the service in a foreground state to prevent app from being killed
   * by the OS.
   */
  @Override
  public void onCreate () {
    Log.d(TAG, "ForegroundService created");
    super.onCreate();
    keepAwake();
  }

  /**
   * No need to run headless on destroy.
   */
  @Override
  public void onDestroy() {
    Log.d(TAG, "ForegroundService destroyed");
    super.onDestroy();
    sleepWell();
  }

  /**
   * Prevent Android from stopping the background service automatically
   */
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {

    return START_STICKY;
  }

  /**
   * Put the service in a foreground state to prevent app from being killed
   * by the OS.
   */
  private void keepAwake() {
    if (wakeLock != null) wakeLock.release();
    //JSONObject settings = BackgroundMode.getSettings();
    SharedPreferences sharedPref = getSharedPreferences(BackgroundMode.JS_NAMESPACE, Context.MODE_PRIVATE);
    JSONObject settings = new JSONObject();
    try {
      settings = new JSONObject(sharedPref.getString("defaultSettings", ""));
    } catch (JSONException e) {
      e.printStackTrace();
    }
    boolean isSilent = settings.optBoolean("silent", false);

    if (!isSilent) {
      startForeground(NOTIFICATION_ID, makeNotification(settings));
    }

    PowerManager pm = (PowerManager)
      getSystemService(POWER_SERVICE);

    wakeLock = pm.newWakeLock(
      PARTIAL_WAKE_LOCK, "BackgroundMode");

    wakeLock.acquire();
  }

  /**
   * Stop background mode.
   */
  private void sleepWell() {
    stopForeground(true);
    getNotificationManager().cancel(NOTIFICATION_ID);

    if (wakeLock != null) {
      wakeLock.release();
      wakeLock = null;
    }
  }

  /**
   * Create a notification as the visible part to be able to put the service
   * in a foreground state by using the default settings.
   */
  private Notification makeNotification() {
    return makeNotification(BackgroundMode.getSettings());
  }

  /**
   * Create a notification as the visible part to be able to put the service
   * in a foreground state.
   *
   * @param settings The config settings
   */
  private Notification makeNotification(JSONObject settings) {
    String title    = settings.optString("title", NOTIFICATION_TITLE);
    String text     = settings.optString("text", NOTIFICATION_TEXT);
    boolean bigText = settings.optBoolean("bigText", false);

    Context context = getApplicationContext();
    String pkgName  = context.getPackageName();
    Intent intent   = context.getPackageManager()
      .getLaunchIntentForPackage(pkgName);

    NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(getIconResId(settings))
      .setContentTitle(title)
      .setContentText(text)
      .setSound(null)
      .setVibrate(null)
      .setTicker(text);

    if (settings.optBoolean("hidden", true)) {
      notification.setPriority(Notification.PRIORITY_MIN);
    }

    if (bigText || text.contains("\n")) {
      notification.setStyle(new NotificationCompat.BigTextStyle()
        .bigText(text));
    }

    setColor(notification, settings);

    if (intent != null && settings.optBoolean("resume")) {
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      PendingIntent contentIntent = PendingIntent.getActivity(
        context, NOTIFICATION_ID, intent,
        PendingIntent.FLAG_UPDATE_CURRENT);


      notification.setContentIntent(contentIntent);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      createChannel();
      notification.setChannelId(CHANNEL_ID);
    }

    return notification.build();
  }

  /**
   * Update the notification.
   *
   * @param settings The config settings
   */
  protected void updateNotification (JSONObject settings) {
    boolean isSilent = settings.optBoolean("silent", false);

    if (isSilent) {
      stopForeground(true);
      return;
    }

    Notification notification = makeNotification(settings);

    getNotificationManager().notify(NOTIFICATION_ID, notification);
  }

  /**
   * Retrieves the resource ID of the app icon.
   *
   * @param settings A JSON dict containing the icon name.
   */
  private int getIconResId(JSONObject settings) {
    String icon = settings.optString("icon", NOTIFICATION_ICON);

    // cordova-android 6 uses mipmaps
    int resId = getIconResId(icon, "drawable");

    if (resId == 0) {
      resId = getIconResId(icon, "mipmap");
    }

    return resId;
  }

  /**
   * Retrieve resource id of the specified icon.
   *
   * @param icon The name of the icon.
   * @param type The resource type where to look for.
   *
   * @return The resource id or 0 if not found.
   */
  private int getIconResId(String icon, String type) {
    Resources res  = getResources();
    String pkgName = getPackageName();

    int resId = res.getIdentifier(icon, type, pkgName);

    if (resId == 0) {
      resId = res.getIdentifier("icon", type, pkgName);
    }

    return resId;
  }

  /**
   * Set notification color if its supported by the SDK.
   *
   * @param notification A Notification.Builder instance
   * @param settings A JSON dict containing the color definition (red: FF0000)
   */
  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private void setColor(NotificationCompat.Builder notification,
                        JSONObject settings) {

    String hex = settings.optString("color", null);

    if (Build.VERSION.SDK_INT < 21 || hex == null)
      return;

    try {
      int aRGB = Integer.parseInt(hex, 16) + 0xFF000000;
      notification.setColor(aRGB);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Shared manager for the notification service.
   */
  private NotificationManager getNotificationManager() {
    return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
  }

  @TargetApi(26)
  private void createChannel() {
    String name = "BikeBT";
    String description = "Notifications for bike connection";
    int importance = NotificationManager.IMPORTANCE_DEFAULT;

    NotificationChannel mChannel = new NotificationChannel(CHANNEL_ID, name, importance);
    mChannel.setDescription(description);
    mChannel.setSound(null,null);
    mChannel.enableLights(false);
    mChannel.enableVibration(false);
    getNotificationManager().createNotificationChannel(mChannel);
  }

}
