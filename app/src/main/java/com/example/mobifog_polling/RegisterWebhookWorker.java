package com.example.mobifog_polling;

import android.content.SharedPreferences;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


public class RegisterWebhookWorker extends Worker {

    private final String ForchUrl;

    private final OkHttpClient client;
    private static final String TAG = "RegisterWebhookWorker";

    private final Context context;
    public RegisterWebhookWorker(@NonNull Context context, @NonNull WorkerParameters workerParameters){
        super(context, workerParameters);
        SharedPreferences sharedPreferences = context.getSharedPreferences("MyPrefs",
                Context.MODE_PRIVATE);
        this.ForchUrl = sharedPreferences.getString("ForchUrl", "");
        this.client = HttpClientSingleton.INSTANCE.getClient();
        this.context = context;
    }

    @NonNull
    @Override
    public Result doWork(){
        String NodeUrl = getDeviceIp();
        //Costruzione del corpo della richiesta
        RequestBody formBody = new FormBody.Builder()
                .add("callback_url", NodeUrl)
                //eventi di esempio
                .add("event_types", "task_completed, resource_allocation")
                .build();

        //Costruzione della richiesta HTTP POST (endpoint POST)
        Request request = new Request.Builder()
                .url(ForchUrl + "/polling-response")
                .post(formBody)
                .build();

        // Analisi della risposta
        try (Response response = client.newCall(request).execute()) {


            if (response.isSuccessful()) {
                Log.d(TAG, "Webhook registrato con successo.");
            } else {
                Log.e(TAG, "Errore nella registrazione del Webhook: " + response.code());
            }
            return Result.failure();
        } catch (IOException e) {
            Log.e(TAG, "Errore di rete durante la registrazione del Webhook.", e);
            return Result.failure();
        }
    }

    //Metodo per estrarre l'IP del dispoditivo
    public String getDeviceIp() {
        String ip = null;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork != null) {
                // Verifica la connessione Wi-Fi
                WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null && wifiManager.isWifiEnabled()) {
                    WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                    if (wifiInfo != null) {
                        int ipAddress = wifiInfo.getIpAddress();
                        ip = String.format(Locale.getDefault(),"%d.%d.%d.%d",
                                (ipAddress & 0xff),
                                (ipAddress >> 8 & 0xff),
                                (ipAddress >> 16 & 0xff),
                                (ipAddress >> 24 & 0xff));
                    }
                }
            }
        }

        if (ip == null) {
            /* Se non è riuscito a ottenere l'IP Wi-Fi, tenta di ottenere l'IP di rete
               tramite altre interfacce di rete */
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = interfaces.nextElement();
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress inetAddress = addresses.nextElement();
                        if (!inetAddress.isLoopbackAddress()) {
                            ip = inetAddress.getHostAddress();
                            if (ip != null) {
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return ip;  // Restituisce l'IP del dispositivo
    }
}