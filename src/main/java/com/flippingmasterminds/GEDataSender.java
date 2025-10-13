package com.flippingmasterminds;

import com.google.gson.Gson;
import okhttp3.*;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import javax.inject.Inject;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class GEDataSender
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    @Inject private Gson gson;
    @Inject private OkHttpClient okHttpClient;
    private final String serverUrl;
    private final String apiToken;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;

    public GEDataSender(String serverUrl, String apiToken)
    {
        this.serverUrl = serverUrl;
        this.apiToken = apiToken;

        Thread worker = new Thread(this::processQueue, "GEDataSender-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    public void send(Object payload)
    {
        String json = gson.toJson(payload);
        queue.offer(json);
    }

    private void processQueue()
    {
        while (running)
        {
            try
            {
                String json = queue.take();
                RequestBody body = RequestBody.create(JSON, json);

                Request.Builder builder = new Request.Builder()
                        .url(serverUrl)
                        .post(body);

                if (apiToken != null && !apiToken.isEmpty())
                {
                    // Flask expects "Authorization: Bearer <token>"
                    builder.addHeader("Authorization", "Bearer " + apiToken);
                }

                Request request = builder.build();

                okHttpClient.newCall(request).enqueue(new Callback()
                {
                    @Override
                    public void onFailure(Call call, IOException e)
                    {
                        queue.offer(json); // retry
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException
                    {
                        if (!response.isSuccessful())
                        {
                            queue.offer(json); // retry
                        }
                        response.close();
                    }
                });
            }
            catch (InterruptedException ignored) { }
        }
    }

    public void shutdown() {
        running = false;
        okHttpClient.dispatcher().executorService().shutdown();
        okHttpClient.connectionPool().evictAll();
        if (okHttpClient.cache() != null) {
            try {
                okHttpClient.cache().close();
            } catch (IOException ignored) {}
        }
    }

}
