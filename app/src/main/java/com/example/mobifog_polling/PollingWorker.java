package com.example.mobifog_polling;

import android.content.SharedPreferences;

// Import delle librerie per WorkManager
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
// Import delle librerie per gestire la comunicazione HTTP
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
// Import delle librerie per la gestione delle eccezioni e per la crezione del log per il debug
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import android.util.Log;
// Import delle librerie per il parsing JSON
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
// Import delle librerie per l'implementazione del foreground service
import androidx.work.ForegroundInfo;
import androidx.core.app.NotificationCompat;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class PollingWorker extends Worker {

    private static final String TAG = "PollingWorker"; // Tag per il log
    private final OkHttpClient client;
    private final String ForchUrl;

    private static final String CHANNEL_ID = "PollingWorkerChannel"; // ID per il canale di notifica

    //private final WebhookClient webhookClient;

    /*Costruttore della classe PollingWorker: passa i parametri context(dati per accedere 
    alle risorse del dispositivo) e WorkerParameters al costruttore della superclasse e crea un
    client HTTP per l'invio delle richieste HTTP di risposta*/
    public PollingWorker(@NonNull Context context, @NonNull WorkerParameters workerParameters) {
        // Chiamata al costruttore della superclasse
        super(context, workerParameters);
        // Inizializzazione del client HTTP singleton
        this.client = HttpClientSingleton.INSTANCE.getClient();
        // Ottenimento dell'URL dell'orchestratore inserito dall'utente e salvato nelle SP
        SharedPreferences sharedPreferences = context.getSharedPreferences("MyPrefs",
                Context.MODE_PRIVATE);
        this.ForchUrl = sharedPreferences.getString("ForchUrl", "");
        createNotificationChannel(); // Creazione del canale di notifica per il foreground service
    }


    /* Metodo per eseguire tutte le operazioni in background: polling, ottenimento della task
    e invio della risposta */
    @NonNull
    @Override
    public Result doWork() {

        createNotificationChannel();

        // Passaggio del worker in modalità foreground
        setForegroundAsync(createForegroundInfo());

        /* Ottenimento del flag ottenuto dall'attivazione/disattivazione del polling da parte
        dell'utente e salvato nelle SP */
        SharedPreferences sharedPreferences = getApplicationContext().getSharedPreferences
                ("MyPrefs", Context.MODE_PRIVATE);
        boolean isPollingActive = sharedPreferences.getBoolean("isPollingActive", true);

        // Interruzione del polling se è stato arrestato
        if (!isPollingActive) {
            Log.d(TAG, "Polling interrotto");
            return Result.failure();
        }

        // Invio della richiesta HTTP e ottenimento della risposta
        String task = fetchTask();

        if(task == null || task.isEmpty()) {
            Log.e("PollingWorker", "Risposta del server vuota o nulla.");
            return Result.retry();
        }

        // Ottenimento del taskId
        String taskId = getTaskId(task);

        // Senza non si riesce ad ottenere il task_id, la task può essere esguita
        if (taskId == null) {
            Log.e(TAG, "Impossibile ottenere il taskId.");
            return Result.failure();
        }

        // Esecuzione della task
        boolean taskResult = executeTask(taskId);

        if(taskResult) {
            Log.d(TAG, "Task eseguita!");
        }

        else {
            Log.e(TAG, "Errore nell'esecuzione della task");
        }

        // Risultato svolgimento task
        String status = taskResult ? "task completata" : "task fallita";

        // Ottenimento di eventuali dati aggiuntivi relativi allo svolgimento della task
        String data = getData();

        // Invio di una richiesta HTTP POST come risposta allo svolgimento della task
        sendTaskResponse(taskId, status, data);

        scheduleNextPolling();

        return Result.success();
    }

    private void scheduleNextPolling() {
        // Pianifica il prossimo polling dopo 10 secondi
        WorkRequest nextWorkRequest = new OneTimeWorkRequest.Builder(PollingWorker.class)
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build();

        // Enqueue il nuovo lavoro con WorkManager
        WorkManager.getInstance(getApplicationContext()).enqueue(nextWorkRequest);
        Log.d("PollingWorker", "Scheduling della prossima richiesta effettuato");
    }

    // Metodo per eseguire richieste HTTP GET e ottenere le relative risposte
    public String fetchTask() {

        // Creazione di una richiesta HTTP GET all'URL dell'orchestratore (endpoint GET)
        String pollingUrl = ForchUrl+"/polling-startup";
        Request request = new Request.Builder()
                .url(pollingUrl)
                .build();

        // Lettura della risposta relativa alla richiesta appena mandata
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                // Verifica che il corpo della risposta non sia vuoto
                if (response.body() != null) {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Polling riuscito! Risposta: " + responseBody);
                    // Viene restituito il corpo della risposta
                    return responseBody;
                } else {
                    Log.w(TAG, "La risposta non contiene un corpo.");
                }
            } else {
                // Se la risposta non è positiva
                Log.e(TAG, "Errore durante il polling, codice di risposta: " + response.code());
            }
        } catch (IOException e) {
            // Se si verifica un errore nella connessione
            Log.e(TAG, "Errore nella connessione: " + e.getMessage(), e);
        }

        return null;
    }

    // Metodo per effettuare il parsing del campo task_id della risposta
    private String getTaskId(String response) {
        try {
            JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
            return jsonResponse.get("task_id").getAsString();
        } catch (Exception e) {
            Log.e(TAG, "Errore nell'estrazione del task_id dalla risposta.", e);
            return null;
        }
    }

    // Metodo per eseguire la task, simula l'esecuzione della task basata sull'ID
    private boolean executeTask(String taskId) {
        int taskIdInt = Integer.parseInt(taskId);

        if (taskIdInt != 0) {
            Log.d(TAG, "Task " + taskId + " completata con successo.");
            return true;
        } else {
            Log.d(TAG, "Task " + taskId + " fallita.");
            return false;
        }
    }

    /*Metodo che deve estrapolare dati aggiuntivi in base alla task svolta, a titolo esemplificativo
     ritorna solo una stringa*/
    private String getData() {
        return "{\"message\": \"Non ci sono dati aggiuntivi\"}";
    }

    /* Metodo per inviare la risposta del nodo riguardo a una task svolta sotto forma di richiesta
       HTTP POST */
    public void sendTaskResponse(String taskId, String status, String Data) {

        RequestBody body = new FormBody.Builder()
                .add("task_id", taskId)
                .add("status", status)
                .add("data", Data)
                .build();

        // Creazione della richiesta HTTP POST (endpoint POST)
        Request request = new Request.Builder()
                .url(ForchUrl + "/polling-response")
                .post(body)
                .build();

        // Controllo del corretto invio
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                Log.d(TAG, "Risposta inviata al server con successo.");
            } else {
                Log.e(TAG, "Errore nell'invio della risposta al server: " + response.code());
            }
        } catch (IOException e) {
            Log.e(TAG, "Errore di rete durante l'invio della risposta al server.", e);
        }
    }


    // Metodo per creare i parametri necessari al setting del foreground service
    private ForegroundInfo createForegroundInfo() {
        String title = "Polling";
        String content = "Polling in esecuzione";

        Notification notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH) // Alta priorità
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        return new ForegroundInfo(1, notification);
    }

    // Metodo per creare il canale di notifica
    private void createNotificationChannel() {
        CharSequence name = "PollingWorker Channel";
        String description = "Canale per le notifiche del PollingWorker";
        int importance = NotificationManager.IMPORTANCE_HIGH; // Alta priorità
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
        channel.setDescription(description);

        NotificationManager notificationManager = getApplicationContext()
                .getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }
}



