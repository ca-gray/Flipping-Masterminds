package com.flippingmasterminds;

import com.google.gson.Gson;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class GEDataSender
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();

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
                System.out.println("📤 Sending payload to " + serverUrl + ": " + json);

                client.newCall(request).enqueue(new Callback()
                {
                    @Override
                    public void onFailure(Call call, IOException e)
                    {
                        System.err.println("❌ Failed to send GE data: " + e.getMessage());
                        queue.offer(json); // retry
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException
                    {
                        if (!response.isSuccessful())
                        {
                            System.err.println("⚠️ Server responded with: " + response.code());
                            queue.offer(json); // retry
                        }
                        else
                        {
                            System.out.println("✅ Server accepted GE data: " + response.code());
                        }
                        response.close();
                    }
                });
            }
            catch (InterruptedException ignored) { }
        }
    }

    public void shutdown()
    {
        running = false;
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        if (client.cache() != null)
        {
            try { client.cache().close(); } catch (IOException ignored) {}
        }
    }
}
