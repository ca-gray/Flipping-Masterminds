package com.flippingmasterminds;

import com.google.gson.Gson;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class GeHistoryStoreTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private GeHistoryStore store;
	private long clock = 1_000;

	@Before
	public void setUp()
	{
		store = newStore();
		store.ensureAccount(42);
	}

	private GeHistoryStore newStore()
	{
		return new GeHistoryStore(Filepath.Unchecked.getRooted(tmp.getRoot().toPath()), new Gson(), Runnable::run);
	}

	/** Trade with a distinct total so rows are distinguishable. */
	private static GeHistoryEntry t(int id)
	{
		return new GeHistoryEntry(true, id, 1, id * 10L, id * 10L, 0);
	}

	/** Native list (newest first) holding trades hi..lo. */
	private static List<GeHistoryEntry> nativeList(int hi, int lo)
	{
		List<GeHistoryEntry> l = new ArrayList<>();
		for (int i = hi; i >= lo; i--) l.add(t(i));
		return l;
	}

	private static List<Integer> ids(List<GeHistoryEntry> l)
	{
		List<Integer> out = new ArrayList<>();
		for (GeHistoryEntry e : l) out.add(e.itemId);
		return out;
	}

	private GeHistoryStore.SyncResult sync(List<GeHistoryEntry> rows)
	{
		return store.syncNative(rows, 100);
	}

	private List<Integer> archiveIds(List<GeHistoryEntry> rows)
	{
		return ids(sync(rows).archive);
	}

	/** Record a trade made in RuneLite; returns its timestamp. */
	private long live(int id)
	{
		GeHistoryEntry e = t(id);
		e.timestamp = ++clock;
		store.recordLive(e);
		return e.timestamp;
	}

	@Test
	public void firstViewWithNoLiveTradesArchivesNothing()
	{
		assertTrue(archiveIds(nativeList(5, 1)).isEmpty());
	}

	@Test
	public void rowsThatScrollOffAreArchived()
	{
		sync(nativeList(5, 1));
		// Two new trades pushed 1 and 2 off a 5-row list.
		assertEquals(Arrays.asList(2, 1), archiveIds(nativeList(7, 3)));
		assertEquals(Arrays.asList(4, 3, 2, 1), archiveIds(nativeList(9, 5)));
	}

	@Test
	public void reopeningWithoutNewTradesChangesNothing()
	{
		sync(nativeList(5, 1));
		sync(nativeList(7, 3));
		assertEquals(Arrays.asList(2, 1), archiveIds(nativeList(7, 3)));
	}

	@Test
	public void growingListArchivesNothing()
	{
		sync(nativeList(3, 1));
		assertTrue(archiveIds(nativeList(5, 1)).isEmpty());
	}

	@Test
	public void liveTradesFillGapWhenListFullyTurnedOver()
	{
		sync(nativeList(3, 1));
		// Five trades between views; the 3-row list only shows the newest three.
		for (int id = 4; id <= 8; id++) live(id);
		assertEquals(Arrays.asList(5, 4, 3, 2, 1), archiveIds(nativeList(8, 6)));
	}

	@Test
	public void liveTradesBackfillOnFirstView()
	{
		for (int id = 1; id <= 6; id++) live(id);
		assertEquals(Arrays.asList(2, 1), archiveIds(nativeList(6, 3)));
	}

	@Test
	public void repeatedTradesUseLiveCountAsMinimumShift()
	{
		// Same trade repeated: shifts 0, 1 and 2 all line up; two live trades means at least 2.
		sync(Arrays.asList(t(1), t(1), t(1)));
		live(1);
		GeHistoryEntry again = t(1);
		again.timestamp = ++clock;
		store.recordLive(again);
		assertEquals(2, sync(Arrays.asList(t(1), t(1), t(1))).archive.size());
	}

	// ── Trades made outside RuneLite ─────────────────────────────────────────

	@Test
	public void tradesOutsideRuneLiteWithinWindowAreNotLostOrDuplicated()
	{
		sync(nativeList(5, 1));
		live(6);                 // in RuneLite
		// 7 and 8 made on mobile, never recorded live
		assertEquals(Arrays.asList(3, 2, 1), archiveIds(nativeList(8, 4)));
	}

	@Test
	public void mixedTradesAcrossFullTurnoverNeverDuplicate()
	{
		sync(nativeList(3, 1));
		live(4);                 // RuneLite
		// 5 on mobile
		live(6);                 // RuneLite
		// 7 on mobile
		live(8);                 // RuneLite
		// Game now shows 8, 7, 6. Live 8 and 6 are in that list, 4 is older; 5 is unrecoverable.
		assertEquals(Arrays.asList(4, 3, 2, 1), archiveIds(nativeList(8, 6)));
	}

	@Test
	public void firstViewWithOnlyMobileTradesArchivesNothing()
	{
		live(1);
		live(2);
		// Lots of mobile trades since; neither live trade is visible any more, both are older.
		assertEquals(Arrays.asList(2, 1), archiveIds(nativeList(20, 11)));
	}

	// ── Timestamps ───────────────────────────────────────────────────────────

	@Test
	public void nativeRowsGetLiveTimesAndKeepThem()
	{
		sync(nativeList(3, 1));
		long t4 = live(4);
		// 5 on mobile
		long t6 = live(6);

		long[] times = sync(nativeList(6, 2)).nativeTimes;
		assertArrayEquals(new long[]{t6, 0, t4, 0, 0}, times);

		// Next view: those rows have shifted down two places and keep their times.
		live(7);
		live(8);
		times = sync(nativeList(8, 4)).nativeTimes;
		assertEquals(t6, times[2]);
		assertEquals(t4, times[4]);

		// And they keep them once archived.
		for (int id = 9; id <= 13; id++) live(id);
		GeHistoryStore.SyncResult r = sync(nativeList(13, 9));
		assertEquals(Arrays.asList(8, 7, 6, 5, 4, 3, 2, 1), ids(r.archive));
		assertEquals(t6, r.archive.get(2).timestamp);
		assertEquals(0, r.archive.get(3).timestamp);
		assertEquals(t4, r.archive.get(4).timestamp);
	}

	@Test
	public void unmatchedLiveRecordDoesNotArchiveVisibleTrades()
	{
		live(1);
		live(2);
		live(9); // newest, but not (yet) in the game's list
		// Trades 2 and 1 are still visible, so nothing should be archived.
		GeHistoryStore.SyncResult r = sync(nativeList(2, 1));
		assertTrue(r.archive.isEmpty());
		assertTrue(r.nativeTimes[0] > 0 && r.nativeTimes[1] > 0);
	}

	@Test
	public void liveRecordArrivingAfterTheRowStillGetsItsTime()
	{
		sync(nativeList(3, 1));
		// The game adds trade 4 to its list before the plugin has recorded it...
		assertEquals(0, sync(nativeList(4, 1)).nativeTimes[0]);
		// ...then the live record arrives; the next sync fills in the time.
		long t4 = live(4);
		GeHistoryStore.SyncResult r = sync(nativeList(4, 1));
		assertEquals(t4, r.nativeTimes[0]);
		assertTrue(r.archive.isEmpty());
	}

	@Test
	public void formatsAge()
	{
		assertEquals("just now", GeHistoryExtender.formatAge(20_000));
		assertEquals("30m ago", GeHistoryExtender.formatAge(30 * 60_000L));
		assertEquals("21h ago", GeHistoryExtender.formatAge(21 * 3_600_000L + 59 * 60_000L));
		assertEquals("10d ago", GeHistoryExtender.formatAge(10 * 86_400_000L));
		assertEquals("2y ago", GeHistoryExtender.formatAge(800 * 86_400_000L));
	}

	// ── Persistence / formatting ─────────────────────────────────────────────

	@Test
	public void archiveIsCappedAndPersisted()
	{
		sync(nativeList(5, 1));
		sync(nativeList(10, 6));
		assertEquals(Arrays.asList(5, 4, 3), ids(store.syncNative(nativeList(10, 6), 3).archive));

		GeHistoryStore reloaded = newStore();
		reloaded.ensureAccount(42);
		assertEquals(Arrays.asList(5, 4, 3), ids(reloaded.syncNative(nativeList(10, 6), 100).archive));

		GeHistoryStore otherAccount = newStore();
		otherAccount.ensureAccount(7);
		assertTrue(otherAccount.syncNative(nativeList(10, 6), 100).archive.isEmpty());
	}

	@Test
	public void preloadedAccountDataIsUsed()
	{
		sync(nativeList(5, 1));
		sync(nativeList(7, 3));

		GeHistoryStore fresh = newStore();
		fresh.preload(42);
		fresh.ensureAccount(42);
		assertEquals(Arrays.asList(2, 1), ids(fresh.syncNative(nativeList(7, 3), 100).archive));

		// A preload for another account is ignored rather than mixed in.
		GeHistoryStore other = newStore();
		other.preload(42);
		other.ensureAccount(7);
		assertTrue(other.syncNative(nativeList(7, 3), 100).archive.isEmpty());
	}

	@Test
	public void parsesPriceText()
	{
		assertArrayEquals(new long[]{12_345, 12_345},
				GeHistoryExtender.parsePrices("12,345 coins", 1));
		assertArrayEquals(new long[]{1_250, 125},
				GeHistoryExtender.parsePrices("1,250 coins<br><col=ff9040>= 125 each</col>", 10));
		assertArrayEquals(new long[]{1_250, 125},
				GeHistoryExtender.parsePrices("<col=ff9040>= 125 each</col>", 10));
	}

	@Test
	public void rewritesPriceTextKeepingFormatting()
	{
		GeHistoryEntry e = new GeHistoryEntry(false, 1, 4, 2_000_000, 500_000, 0);
		assertEquals("2,000,000 coins<br><col=ff9040>= 500,000 each</col>",
				GeHistoryExtender.rewritePrices("1,250 coins<br><col=ff9040>= 125 each</col>", e));
	}

	@Test
	public void tooltipForBuyComparesAgainstWikiHigh()
	{
		// 21 Oct 2026 14:32 UTC
		long time = java.time.ZonedDateTime.of(2026, 10, 21, 14, 32, 0, 0, java.time.ZoneOffset.UTC)
				.toInstant().toEpochMilli();
		GeHistoryEntry whips = new GeHistoryEntry(true, 4151, 3, 4_500_000, 1_500_000, time);
		assertEquals("Abyssal whip"
						+ "</br>Bought 3 x 1,500,000 ea = 4,500,000"
						+ "</br><col=9f9f9f>21 Oct 2026, 14:32</col>"
						+ "</br>Now: 1,450,000 <col=ff3030>(-3.3%)</col>"
						+ "</br><col=9f9f9f>Wiki buy 1,450,000 / sell 1,440,000</col>",
				GeHistoryExtender.tooltipText(whips, "Abyssal whip", 1_450_000L, 1_440_000L, java.time.ZoneOffset.UTC));
	}

	@Test
	public void tooltipForSaleUsesWikiLowAndHandlesUnknowns()
	{
		GeHistoryEntry bow = new GeHistoryEntry(false, 20997, 1, 1_000_000_000, 1_000_000_000, 0);
		assertEquals("Twisted bow"
						+ "</br>Sold 1 x 1,000,000,000 ea"
						+ "</br><col=9f9f9f>Time unknown</col>"
						+ "</br>Now: 1,100,000,000 <col=00ff00>(+10.0%)</col>"
						+ "</br><col=9f9f9f>Wiki buy - / sell 1,100,000,000</col>",
				GeHistoryExtender.tooltipText(bow, "Twisted bow", null, 1_100_000_000L, java.time.ZoneOffset.UTC));

		// No wiki price at all: just the trade.
		assertEquals("Twisted bow</br>Sold 1 x 1,000,000,000 ea</br><col=9f9f9f>Time unknown</col>",
				GeHistoryExtender.tooltipText(bow, "Twisted bow", null, null, java.time.ZoneOffset.UTC));
	}

	@Test
	public void templatePrefersParityThenSide()
	{
		List<GeHistoryEntry> rows = Arrays.asList(
				new GeHistoryEntry(true, 1, 1, 1, 1, 0),
				new GeHistoryEntry(false, 1, 1, 1, 1, 0),
				new GeHistoryEntry(false, 1, 1, 1, 1, 0),
				new GeHistoryEntry(true, 1, 1, 1, 1, 0));
		GeHistoryEntry sell = new GeHistoryEntry(false, 2, 1, 1, 1, 0);
		// Row 4 is even: rows 0 and 2 qualify, row 2 is a sell.
		assertEquals(2, GeHistoryExtender.pickTemplate(rows, 4, sell));
		// Row 5 is odd: row 1 is the odd sell.
		assertEquals(1, GeHistoryExtender.pickTemplate(rows, 5, sell));
	}
}
