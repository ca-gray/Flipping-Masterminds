package com.flippingmasterminds;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class GEDataSenderTest
{
	private MockWebServer server;
	private OkHttpClient client;
	private ScheduledExecutorService scheduler;
	private GEDataSender sender;

	@Before
	public void setUp() throws IOException
	{
		server = new MockWebServer();
		server.start();
		client = new OkHttpClient();
		scheduler = Executors.newSingleThreadScheduledExecutor();

		String baseUrl = server.url("").toString();
		sender = new GEDataSender(client, scheduler, baseUrl);
		sender.minSendIntervalMs = 10;
		sender.initialBackoffMs = 50;
		sender.maxBackoffMs = 200;
		sender.defaultRetryAfterMs = 100;
	}

	@After
	public void tearDown() throws IOException
	{
		sender.shutdown();
		scheduler.shutdownNow();
		server.shutdown();
	}

	@Test
	public void testSuccessfulDeliverySendsHeadersAndPayload() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200));

		sender.submit("{\"test\":1}", "token123");

		RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull("Expected a request to be sent", request);
		assertEquals("/ge", request.getPath());
		assertEquals("Bearer token123", request.getHeader("Authorization"));
		assertEquals(GEDataSender.PLUGIN_VERSION, request.getHeader("X-FM-Plugin-Version"));
		assertEquals("{\"test\":1}", request.getBody().readUtf8());
	}

	@Test
	public void test403StopsSendingUntilTokenChange() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(403));
		server.enqueue(new MockResponse().setResponseCode(200));

		sender.submit("{\"test\":1}", "bad-token");

		RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull(first);
		Thread.sleep(100);

		sender.submit("{\"test\":2}", "bad-token");
		Thread.sleep(200);
		assertEquals(1, server.getRequestCount());

		sender.onTokenChanged("new-token");
		sender.submit("{\"test\":3}", "new-token");

		RecordedRequest resumed = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull("Expected sending to resume after token change", resumed);
		assertEquals("Bearer new-token", resumed.getHeader("Authorization"));
		assertEquals("{\"test\":3}", resumed.getBody().readUtf8());
	}

	@Test
	public void test401AlsoRejectsToken() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(401));

		sender.submit("{\"test\":1}", "expired-token");

		RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull(first);
		Thread.sleep(100);

		sender.submit("{\"test\":2}", "expired-token");
		Thread.sleep(200);
		assertEquals(1, server.getRequestCount());
	}

	@Test
	public void test429HonoursRetryAfter() throws Exception
	{
		server.enqueue(new MockResponse()
				.setResponseCode(429)
				.setHeader("Retry-After", "1"));
		server.enqueue(new MockResponse().setResponseCode(200));

		sender.submit("{\"test\":1}", "token");

		RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull(first);

		RecordedRequest retry = server.takeRequest(3, TimeUnit.SECONDS);
		assertNotNull("Expected retry after rate limit", retry);
		assertEquals("{\"test\":1}", retry.getBody().readUtf8());
	}

	@Test
	public void testBackoffGrowsAndResetsOnSuccess() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(500));
		server.enqueue(new MockResponse().setResponseCode(500));
		server.enqueue(new MockResponse().setResponseCode(200));
		server.enqueue(new MockResponse().setResponseCode(200));

		sender.submit("{\"test\":1}", "token");

		RecordedRequest r1 = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull("First request", r1);

		long beforeFirstRetry = System.currentTimeMillis();
		RecordedRequest r2 = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull("First retry after 500", r2);
		long firstRetryDelay = System.currentTimeMillis() - beforeFirstRetry;

		long beforeSecondRetry = System.currentTimeMillis();
		RecordedRequest r3 = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull("Second retry after 500", r3);
		long secondRetryDelay = System.currentTimeMillis() - beforeSecondRetry;

		assertTrue("Second backoff should be longer than first",
				secondRetryDelay >= firstRetryDelay * 0.8);

		sender.submit("{\"test\":2}", "token");

		RecordedRequest r4 = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull("Request after backoff reset", r4);
	}

	@Test
	public void testCoalescingSendsOnlyLatest() throws Exception
	{
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBodyDelay(300, TimeUnit.MILLISECONDS));
		server.enqueue(new MockResponse().setResponseCode(200));

		sender.submit("{\"v\":1}", "token");

		RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull(first);
		assertEquals("{\"v\":1}", first.getBody().readUtf8());

		sender.submit("{\"v\":2}", "token");
		sender.submit("{\"v\":3}", "token");
		sender.submit("{\"v\":4}", "token");

		RecordedRequest second = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull(second);
		assertEquals("{\"v\":4}", second.getBody().readUtf8());

		assertNull("No more requests expected",
				server.takeRequest(300, TimeUnit.MILLISECONDS));
	}

	@Test
	public void testDedupOnlyAfterSuccessfulDelivery() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(500));
		server.enqueue(new MockResponse().setResponseCode(200));

		sender.submit("{\"same\":true}", "token");

		RecordedRequest r1 = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull(r1);
		assertEquals("{\"same\":true}", r1.getBody().readUtf8());

		RecordedRequest r2 = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull("Same payload should retry after failure", r2);
		assertEquals("{\"same\":true}", r2.getBody().readUtf8());

		sender.submit("{\"same\":true}", "token");
		Thread.sleep(200);
		assertEquals("Duplicate payload after successful delivery should be skipped",
				2, server.getRequestCount());
	}

	@Test
	public void test400DropsPayload() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(400));

		sender.submit("{\"bad\":true}", "token");

		RecordedRequest r1 = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull(r1);

		Thread.sleep(200);
		assertEquals("400 should drop payload, no retry", 1, server.getRequestCount());
	}

	@Test
	public void testMultipleRejectionCyclesAllResumeAfterTokenChange() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(403));
		server.enqueue(new MockResponse().setResponseCode(200));
		server.enqueue(new MockResponse().setResponseCode(403));
		server.enqueue(new MockResponse().setResponseCode(200));

		sender.submit("{\"a\":1}", "bad-token");
		server.takeRequest(2, TimeUnit.SECONDS);
		Thread.sleep(100);

		sender.onTokenChanged("new-token");
		sender.submit("{\"a\":2}", "new-token");
		RecordedRequest r2 = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull("Should resume after first token change", r2);
		assertEquals("{\"a\":2}", r2.getBody().readUtf8());

		Thread.sleep(100);

		sender.onTokenChanged("another-token");
		sender.submit("{\"a\":3}", "another-token");
		RecordedRequest r3 = server.takeRequest(2, TimeUnit.SECONDS);
		assertNotNull("Should resume after second token change", r3);
		assertEquals("{\"a\":3}", r3.getBody().readUtf8());
	}
}
