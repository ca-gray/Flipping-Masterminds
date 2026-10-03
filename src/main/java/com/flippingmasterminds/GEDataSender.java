package com.flippingmasterminds;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
@Slf4j
class GEDataSender
{
	static final String API_BASE_URL = "https://api.flippingmasterminds.net";
	static final String PLUGIN_VERSION = "1.0";

	enum Status
	{
		IDLE,
		ACTIVE,
		TOKEN_REJECTED,
		BACKING_OFF,
		RATE_LIMITED
	}

	private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

	long minSendIntervalMs = 1_000;
	long initialBackoffMs = 2_000;
	long maxBackoffMs = 60_000;
	long defaultRetryAfterMs = 30_000;

	private final OkHttpClient httpClient;
	private final ScheduledExecutorService scheduler;
	private final String geEndpointUrl;

	private volatile Status status = Status.IDLE;
	private String pendingPayload;
	private String pendingToken;
	private String lastDeliveredPayload;
	private boolean inFlight;
	private long nextSendTimeMs;
	private long currentBackoffMs;
	private String rejectedToken;
	private ScheduledFuture<?> scheduledFlush;

	GEDataSender(OkHttpClient httpClient, ScheduledExecutorService scheduler)
	{
		this(httpClient, scheduler, API_BASE_URL);
	}

	GEDataSender(OkHttpClient httpClient, ScheduledExecutorService scheduler, String baseUrl)
	{
		this.httpClient = httpClient;
		this.scheduler = scheduler;
		while (baseUrl.endsWith("/"))
		{
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		this.geEndpointUrl = baseUrl + "/ge";
	}

	void submit(String jsonPayload, String token)
	{
		try
		{
			scheduler.execute(() ->
			{
				if (rejectedToken != null && rejectedToken.equals(token))
				{
					return;
				}
				pendingPayload = jsonPayload;
				pendingToken = token;
				scheduleFlush(0);
			});
		}
		catch (RejectedExecutionException ignored)
		{
		}
	}

	void onTokenChanged(String newToken)
	{
		try
		{
			scheduler.execute(() ->
			{
				if (rejectedToken != null && !rejectedToken.equals(newToken))
				{
					rejectedToken = null;
					currentBackoffMs = 0;
					status = pendingPayload != null ? Status.ACTIVE : Status.IDLE;
					scheduleFlush(0);
				}
			});
		}
		catch (RejectedExecutionException ignored)
		{
		}
	}

	void shutdown()
	{
		if (scheduledFlush != null)
		{
			scheduledFlush.cancel(false);
		}
		scheduledFlush = null;
		pendingPayload = null;
	}

	private void scheduleFlush(long pauseMs)
	{
		if (scheduledFlush != null && !scheduledFlush.isDone())
		{
			scheduledFlush.cancel(false);
		}
		long now = System.currentTimeMillis();
		long delay = Math.max(pauseMs, nextSendTimeMs - now);
		delay = Math.max(0, delay);
		scheduledFlush = scheduler.schedule(this::flush, delay, TimeUnit.MILLISECONDS);
	}

	private void flush()
	{
		scheduledFlush = null;
		if (inFlight || pendingPayload == null || rejectedToken != null)
		{
			return;
		}

		String payload = pendingPayload;
		String token = pendingToken;

		if (payload.equals(lastDeliveredPayload))
		{
			pendingPayload = null;
			return;
		}

		inFlight = true;
		status = Status.ACTIVE;
		nextSendTimeMs = System.currentTimeMillis() + minSendIntervalMs;

		Request request = new Request.Builder()
				.url(geEndpointUrl)
				.post(RequestBody.create(JSON_MEDIA, payload))
				.addHeader("Authorization", "Bearer " + token)
				.addHeader("X-FM-Plugin-Version", PLUGIN_VERSION)
				.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				try
				{
					scheduler.execute(() ->
					{
						inFlight = false;
						applyBackoff();
					});
				}
				catch (RejectedExecutionException ignored)
				{
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				int code = response.code();
				long retryAfterMs = code == 429 ? parseRetryAfter(response) : 0;
				response.close();

				try
				{
					scheduler.execute(() -> handleResult(code, retryAfterMs, payload, token));
				}
				catch (RejectedExecutionException ignored)
				{
				}
			}
		});
	}

	private void handleResult(int code, long retryAfterMs, String sentPayload, String sentToken)
	{
		inFlight = false;

		if (code >= 200 && code < 300)
		{
			lastDeliveredPayload = sentPayload;
			if (sentPayload.equals(pendingPayload))
			{
				pendingPayload = null;
			}
			currentBackoffMs = 0;
			status = pendingPayload != null ? Status.ACTIVE : Status.IDLE;
			log.debug("GE data sent, server returned {}", code);
			if (pendingPayload != null)
			{
				scheduleFlush(0);
			}
			return;
		}

		if (code == 401 || code == 403)
		{
			rejectedToken = sentToken;
			pendingPayload = null;
			status = Status.TOKEN_REJECTED;
			log.warn("API token rejected ({}), pausing sends until token changes", code);
			return;
		}

		if (code == 429)
		{
			status = Status.RATE_LIMITED;
			scheduleFlush(retryAfterMs);
			return;
		}

		if (code == 400 || code == 413)
		{
			log.debug("Dropped payload: server returned {}", code);
			if (sentPayload.equals(pendingPayload))
			{
				pendingPayload = null;
			}
			return;
		}

		applyBackoff();
	}

	private void applyBackoff()
	{
		currentBackoffMs = currentBackoffMs == 0
				? initialBackoffMs
				: Math.min(currentBackoffMs * 2, maxBackoffMs);
		long jitter = (long) (currentBackoffMs * Math.random() * 0.25);
		status = Status.BACKING_OFF;
		scheduleFlush(currentBackoffMs + jitter);
	}

	private long parseRetryAfter(Response response)
	{
		String header = response.header("Retry-After");
		if (header != null)
		{
			try
			{
				return Long.parseLong(header.trim()) * 1000L;
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		return defaultRetryAfterMs;
	}
}
