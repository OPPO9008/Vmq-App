package com.shinian.pay;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PayNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "PayNotifyService";

    private void logMatched(String pkg, String title, String content, String money) {
    Log.d(TAG,
            "\n【到账匹配】"
            + "\n包名: " + pkg
            + "\n标题: " + title
            + "\n内容: " + content
            + "\n金额: " + money
         );
    }
    /* ================== OkHttp 单例 ================== */
    private static final OkHttpClient HTTP_CLIENT =
            new OkHttpClient.Builder()
                    .retryOnConnectionFailure(true)
                    .build();

    /* ================== 心跳 ================== */
    private ScheduledExecutorService heartExecutor;
    private volatile boolean heartRunning = false;

    /* ================== WakeLock ================== */
    private PowerManager.WakeLock mWakeLock;

    /* ================== 通知去重（最多缓存100条） ================== */
    private final Set<String> processedKeys =
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 100;
                }
            });

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /* ================== WakeLock ================== */
    @SuppressLint("InvalidWakeLockTag")
    private void acquireWakeLock() {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PayNotify:WakeLock"
            );
        }
        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire(2 * 60 * 1000L); // 最多2分钟
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        mWakeLock = null;
    }

    /* ================== 心跳控制 ================== */
    private void startHeartBeat() {
        if (heartRunning) return;

        heartRunning = true;
        acquireWakeLock();

        heartExecutor = Executors.newSingleThreadScheduledExecutor();
        heartExecutor.scheduleAtFixedRate(
                this::sendHeartBeat,
                0,
                60, // 建议 ≥60 秒，稳定
                TimeUnit.SECONDS
        );
        Log.d(TAG, "心跳启动");
    }

    private void stopHeartBeat() {
        heartRunning = false;
        if (heartExecutor != null) {
            heartExecutor.shutdownNow();
            heartExecutor = null;
        }
        releaseWakeLock();
        Log.d(TAG, "心跳停止");
    }

    private void sendHeartBeat() {
        SharedPreferences sp = getSharedPreferences("shinian", MODE_PRIVATE);
        String host = sp.getString("host", "");
        String key = sp.getString("key", "");
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(key)) return;

        String t = String.valueOf(System.currentTimeMillis());
        String sign = md5(t + key);

        Request request = new Request.Builder()
                .url("http://" + host + "/appHeart?t=" + t + "&sign=" + sign)
                .get()
                .build();

        HTTP_CLIENT.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "心跳失败", e);
            }

            @Override public void onResponse(Call call, Response response) {
                response.close();
            }
        });
    }

    /* ================== 通知监听 ================== */
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {

        String key = sbn.getKey();
        if (processedKeys.contains(key)) return;
        processedKeys.add(key);

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        if (extras == null) return;

        String pkg = sbn.getPackageName();
        String title = extras.getString(NotificationCompat.EXTRA_TITLE, "");
        String content = extras.getString(NotificationCompat.EXTRA_TEXT, "");

        if (TextUtils.isEmpty(content)) return;

        /* ===== 微信 ===== */
        if ("com.tencent.mm".equals(pkg)) {
            if (title.contains("微信支付") || title.contains("收款")) {
                String money = getMoney(content);
                if (money != null) {
                    logMatched(pkg, title, content, money);
                    appPush(1, Double.parseDouble(money));
                }
            }
        }

        /* ===== 支付宝 ===== */
        else if ("com.eg.android.AlipayGphone".equals(pkg)) {
            if (content.contains("收款") || title.contains("成功收款")) {
                String money = getMoney(title + content);
                if (money != null) {
                    logMatched(pkg, title, content, money);
                    appPush(2, Double.parseDouble(money));
                }
            }
        }
    }

    /* ================== 服务生命周期 ================== */
    @Override
    public void onListenerConnected() {
        startHeartBeat();
        mainHandler.post(() ->
                Toast.makeText(getApplicationContext(), "监听服务已启动", Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    public void onListenerDisconnected() {
        stopHeartBeat();
        requestRebind(new ComponentName(this, PayNotificationListenerService.class));
    }

    @Override
    public void onDestroy() {
        stopHeartBeat();
        super.onDestroy();
    }

    /* ================== 推送 ================== */
    private void appPush(int type, double price) {
        SharedPreferences sp = getSharedPreferences("shinian", MODE_PRIVATE);
        String host = sp.getString("host", "");
        String key = sp.getString("key", "");
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(key)) return;

        String t = String.valueOf(System.currentTimeMillis());
        String sign = md5(type + "" + price + t + key);

        String url = "http://" + host + "/appPush"
                + "?t=" + t
                + "&type=" + type
                + "&price=" + price
                + "&sign=" + sign;

        Request request = new Request.Builder().url(url).get().build();
        HTTP_CLIENT.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "推送失败", e);
            }
            @Override public void onResponse(Call call, Response response) {
                response.close();
            }
        });
    }

    /* ================== 工具 ================== */
    private static String getMoney(String text) {
        String s = text.replaceAll("[^0-9.]", ",");
        for (String p : s.split(",")) {
            if (p.matches("\\d+(\\.\\d{1,2})?")) return p;
        }
        return null;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte c : b) {
                String t = Integer.toHexString(c & 0xff);
                if (t.length() == 1) sb.append('0');
                sb.append(t);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
