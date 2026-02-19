package com.shinian.pay;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PayNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "PayNotifyService";

    private static final long HEART_INTERVAL_MS = 60_000L;

    private static final Set<String> PAY_PKGS = new HashSet<>(Arrays.asList(
            "com.tencent.mm",
            "com.tencent.wework",
            "com.eg.android.AlipayGphone"
    ));

    private static final Pattern MONEY_PATTERN =
            Pattern.compile("(￥|¥)?\\s*(\\d+\\.\\d{1,2})");

    private final OkHttpClient httpClient = new OkHttpClient();

    private Thread heartThread;
    private volatile boolean heartRunning;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        startHeartThread();
        acquireWakeLock();
    }

    @Override
    public void onDestroy() {
        stopHeartThread();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {

        String pkg = sbn.getPackageName();
        if (!PAY_PKGS.contains(pkg)) return;

        Notification n = sbn.getNotification();
        if (n == null) return;

        Bundle extras = n.extras;
        if (extras == null) return;

        String title = extras.getString(NotificationCompat.EXTRA_TITLE, "");
        String text = collectNotificationText(extras);

        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return;

        if (pkg.equals("com.tencent.mm") || pkg.equals("com.tencent.wework")) {
            handleWeChat(title, text);
        } else if (pkg.equals("com.eg.android.AlipayGphone")) {
            handleAlipay(title, text);
        }
    }

    // ================== 微信 ==================
    private void handleWeChat(String title, String text) {
        if (!(title.contains("收款") || text.contains("收款"))) return;
        if (text.contains("已支付")) return;

        String money = extractMoney(title + "\n" + text);
        if (TextUtils.isEmpty(money)) {
            toast("微信收款通知，但未解析到金额");
            return;
        }

        if (isDuplicate("WX", money)) return;

        toast("微信到账：" + money + " 元");
        pushAsync(1, Double.parseDouble(money));
    }

    // ================== 支付宝 ==================
    private void handleAlipay(String title, String text) {
        if (!(title.contains("成功收款") || text.contains("成功收款")
                || text.contains("已转入") || text.contains("向你付款"))) {
            return;
        }

        String money = extractMoney(title + "\n" + text);
        if (TextUtils.isEmpty(money)) {
            toast("支付宝收款通知，但未解析到金额");
            return;
        }

        if (isDuplicate("ALI", money)) return;

        toast("支付宝到账：" + money + " 元");
        pushAsync(2, Double.parseDouble(money));
    }

    private void pushAsync(int type, double price) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                sendPush(type, price);
            } catch (Exception e) {
                Log.e(TAG, "回调失败，进入补偿", e);
            }
        });
    }

    private void sendPush(int type, double price) throws Exception {

        SharedPreferences sp = getSharedPreferences("shinian", MODE_PRIVATE);
        String host = sp.getString("host", "");
        String key = sp.getString("key", "");

        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(key)) return;

        String t = String.valueOf(System.currentTimeMillis());
        String sign = md5(type + "" + price + t + key);

        String url = "https://" + host +
                "/appPush?t=" + t +
                "&type=" + type +
                "&price=" + price +
                "&sign=" + sign;

        Request req = new Request.Builder().url(url).get().build();

        try (Response r = httpClient.newCall(req).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("HTTP " + r.code());
            }
        }
    }

    private void startHeartThread() {
        if (heartThread != null && heartThread.isAlive()) return;
        heartRunning = true;
        heartThread = new Thread(() -> {
            while (heartRunning) {
                try {
                    sendHeart();
                } catch (Exception e) {
                    Log.e(TAG, "心跳错误", e);
                }
                try {
                    Thread.sleep(HEART_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        heartThread.setName("PayHeartThread");
        heartThread.start();
    }

    private void stopHeartThread() {
        heartRunning = false;
        if (heartThread != null) {
            heartThread.interrupt();
            heartThread = null;
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":Heart");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void sendHeart() throws Exception {
        SharedPreferences sp = getSharedPreferences("shinian", MODE_PRIVATE);
        String host = sp.getString("host", "");
        String key = sp.getString("key", "");
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(key)) return;
        String t = String.valueOf(System.currentTimeMillis());
        String sign = md5(t + key);
        String url = "http://" + host + "/appHeart?t=" + t + "&sign=" + sign;
        Request req = new Request.Builder().url(url).get().build();
        try (Response r = httpClient.newCall(req).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("HTTP " + r.code());
            }
        }
    }

    private String collectNotificationText(Bundle extras) {
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            StringBuilder sb = new StringBuilder();
            for (CharSequence c : lines) sb.append(c).append("\n");
            return sb.toString();
        }
        return extras.getString(NotificationCompat.EXTRA_TEXT, "");
    }

    private String extractMoney(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher m = MONEY_PATTERN.matcher(text);
        if (m.find()) return m.group(2);
        return null;
    }

    private boolean isDuplicate(String type, String money) {
        SharedPreferences sp = getSharedPreferences("dedup", MODE_PRIVATE);
        String k = type + "_" + money;
        long last = sp.getLong(k, 0);
        long now = System.currentTimeMillis();
        if (now - last < 10_000) return true;
        sp.edit().putLong(k, now).apply();
        return false;
    }

    private void toast(String msg) {
        new android.os.Handler(getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show()
        );
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
