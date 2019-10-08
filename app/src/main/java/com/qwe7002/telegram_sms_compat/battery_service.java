package com.qwe7002.telegram_sms_compat;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class battery_service extends Service {
    static String bot_token;
    static String chat_id;
    static boolean doh_switch;
    private Context context;
    private battery_receiver battery_receiver = null;
    private stop_broadcast_receiver stop_broadcast_receiver = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = public_func.get_notification_obj(context, getString(R.string.battery_monitoring_notify));
        startForeground(1, notification);
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
        SharedPreferences sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);
        chat_id = sharedPreferences.getString("chat_id", "");
        bot_token = sharedPreferences.getString("bot_token", "");
        doh_switch = sharedPreferences.getBoolean("doh_switch", true);
        final boolean charger_status = sharedPreferences.getBoolean("charger_status", false);
        IntentFilter intentFilter = new IntentFilter(public_func.broadcast_stop_service);
        stop_broadcast_receiver = new stop_broadcast_receiver();

        battery_receiver = new battery_receiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_OKAY);
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        if (charger_status) {
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        }
        registerReceiver(battery_receiver, filter);
        registerReceiver(stop_broadcast_receiver, intentFilter);

    }

    @Override
    public void onDestroy() {
        unregisterReceiver(stop_broadcast_receiver);
        unregisterReceiver(battery_receiver);
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    class stop_broadcast_receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i("battery_service", "Received stop signal, quitting now...");
            stopSelf();
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

}

class battery_receiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, final Intent intent) {
        Log.d("battery_receiver", "Receive action: " + intent.getAction());
        String request_uri = public_func.get_url(battery_service.bot_token, "sendMessage");
        final message_json request_body = new message_json();
        request_body.chat_id = battery_service.chat_id;
        StringBuilder message_body = new StringBuilder(context.getString(R.string.system_message_head) + "\n");
        final String action = intent.getAction();
        switch (Objects.requireNonNull(action)) {
            case Intent.ACTION_BATTERY_OKAY:
                message_body.append(context.getString(R.string.low_battery_status_end));
                break;
            case Intent.ACTION_BATTERY_LOW:
                message_body.append(context.getString(R.string.battery_low));
                break;
            case Intent.ACTION_POWER_CONNECTED:
                message_body.append(context.getString(R.string.charger_connect));
                break;
            case Intent.ACTION_POWER_DISCONNECTED:
                message_body.append(context.getString(R.string.charger_disconnect));
                break;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, filter);
        assert batteryStatus != null;
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int battery_level = (int) ((level / (float) scale) * 100);
        if (battery_level > 100) {
            battery_level = 100;
        }
        request_body.text = message_body.append("\n").append(context.getString(R.string.current_battery_level)).append(battery_level).append("%").toString();

        if (!public_func.check_network_status(context)) {
            public_func.write_log(context, public_func.network_error);
            if (action.equals(Intent.ACTION_BATTERY_LOW)) {
                public_func.send_fallback_sms(context, request_body.text);
            }
            return;
        }

        OkHttpClient okhttp_client = public_func.get_okhttp_obj(battery_service.doh_switch);
        String request_body_raw = new Gson().toJson(request_body);
        RequestBody body = RequestBody.create(public_func.JSON, request_body_raw);
        Request request = new Request.Builder().url(request_uri).method("POST", body).build();
        Call call = okhttp_client.newCall(request);
        final String error_head = "Send battery info failed:";
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                e.printStackTrace();
                String error_message = error_head + e.getMessage();
                public_func.write_log(context, error_message);
                if (action.equals(Intent.ACTION_BATTERY_LOW)) {
                    public_func.send_fallback_sms(context, request_body.text);
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.code() != 200) {
                    assert response.body() != null;
                    String error_message = error_head + response.code() + " " + response.body().string();
                    public_func.write_log(context, error_message);
                }
            }
        });


    }
}
