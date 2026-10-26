package com.flippingmasterminds;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Per-account archive of GE trades that have dropped off the in-game history list.
 *
 * On each sync the native list is compared with the previous snapshot and rows that
 * fell off the bottom are archived. Live-recorded trades fill the gap when the native
 * list turned over completely between syncs.
 *
 * Client thread only; disk writes go to {@code ioExecutor}.
 */
@Slf4j
class GeHistoryStore
{
	/** Cap on live records kept between syncs. */
	private static final int MAX_LIVE = 2_000;

	private final File directory;
	private final Gson gson;
	private final Executor ioExecutor;

	private volatile long accountHash = -1;
	private AccountData data;
	/** Account data read ahead of time on the IO thread. */
	private volatile Preloaded preloaded;

	private static class Preloaded
	{
		final long hash;
		final AccountData data;

		Preloaded(long hash, AccountData data)
		{
			this.hash = hash;
			this.data = data;
		}
	}

	GeHistoryStore(File directory, Gson gson, Executor ioExecutor)
	{
		this.directory  = directory;
		this.gson       = gson;
		this.ioExecutor = ioExecutor;
	}

	/** Read an account's history on the IO thread so {@link #ensureAccount} doesn't touch the disk. */
	void preload(long hash)
	{
		if (hash == -1 || hash == accountHash) return;
		File file = fileFor(hash);
		try
		{
			ioExecutor.execute(() ->
			{
				AccountData loaded = load(file);
				// Skip if the client thread already loaded this account itself.
				if (accountHash != hash) preloaded = new Preloaded(hash, loaded);
			});
		}
		catch (RejectedExecutionException ignored)
		{
		}
	}

	/** Switch to the given account, using preloaded data or reading it from disk if needed. */
	boolean ensureAccount(long hash)
	{
		if (hash == -1) return false;
		if (hash == accountHash && data != null) return true;

		Preloaded p = preloaded;
		preloaded = null;
		accountHash = hash;
		data = p != null && p.hash == hash ? p.data : load(fileFor(hash));
		return true;
	}

	/** Record a trade the player just collected from the GE. */
	void recordLive(GeHistoryEntry entry)
	{
		if (data == null) return;
		data.live.add(0, entry);
		while (data.live.size() > MAX_LIVE) data.live.remove(data.live.size() - 1);
		save();
	}

	static class SyncResult
	{
		/** When each native row happened (same order as the native list), 0 if unknown. */
		final long[] nativeTimes;
		/** Archived trades older than the oldest native row, newest first. */
		final List<GeHistoryEntry> archive;

		SyncResult(long[] nativeTimes, List<GeHistoryEntry> archive)
		{
			this.nativeTimes = nativeTimes;
			this.archive     = archive;
		}
	}

	/**
	 * Merge a snapshot of the in-game list (newest first) into the archive. Live records
	 * are matched to native rows by content, not position, since trades made outside
	 * RuneLite only appear in the native list.
	 */
	SyncResult syncNative(List<GeHistoryEntry> nativeRows, int maxArchive)
	{
		int n = nativeRows.size();
		if (data == null) return new SyncResult(new long[n], new ArrayList<>());
		if (n == 0)
		{
			if (trim(maxArchive)) save();
			return new SyncResult(new long[0], new ArrayList<>(data.archive));
		}

		List<GeHistoryEntry> prev = data.lastNative;

		// Trades collected since the last sync, newest first (the list is cleared after each sync).
		List<GeHistoryEntry> live = new ArrayList<>(data.live);

		long[] times = new long[n];
		List<GeHistoryEntry> archived = new ArrayList<>();

		// At least live.size() rows are new; trades made outside RuneLite only add to that.
		int shift = prev == null ? -1 : findShift(nativeRows, prev, live.size());
		if (shift >= 0)
		{
			// nativeRows = <shift new trades> + prev[0 .. n-shift-1]; anything after that fell off.
			int keep = n - shift;
			if (keep < prev.size())
			{
				archived.addAll(prev.subList(keep, prev.size()));
			}
			for (int i = shift; i < n && i - shift < prev.size(); i++)
			{
				times[i] = prev.get(i - shift).timestamp;
			}
			// Search the whole list, not just the new rows: the game can add a trade to its
			// list a moment before the plugin records it, leaving that row without a time.
			matchLive(nativeRows, n, live, times);
		}
		else
		{
			// No overlap (first sync or full turnover): live trades older than every matched
			// one are no longer in the game's list, so they go to the archive.
			int seen = matchLive(nativeRows, n, live, times);
			archived.addAll(live.subList(seen, live.size()));
			if (prev != null)
			{
				archived.addAll(prev);
			}
		}

		if (!archived.isEmpty())
		{
			archived.addAll(data.archive);
			data.archive = archived;
		}
		List<GeHistoryEntry> snapshot = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
		{
			snapshot.add(nativeRows.get(i).withTime(times[i]));
		}
		data.lastNative = snapshot;
		// All live trades are now either in the snapshot or archived.
		data.live.clear();

		trim(maxArchive);
		save();
		return new SyncResult(times, new ArrayList<>(data.archive));
	}

	/**
	 * Match live records (newest first) to the first {@code limit} native rows in order,
	 * skipping rows that already have a time or no live record, and fill in their times.
	 * A live record with no matching row is skipped rather than stopping the walk.
	 *
	 * @return how many live records lead up to and include the last match; those after it
	 *         are older than anything matched in the list
	 */
	static int matchLive(List<GeHistoryEntry> nativeRows, int limit,
						 List<GeHistoryEntry> live, long[] times)
	{
		int next = 0;
		int lastMatched = -1;
		for (int j = 0; j < live.size(); j++)
		{
			GeHistoryEntry rec = live.get(j);
			for (int k = next; k < limit; k++)
			{
				if (times[k] == 0 && rec.sameItemAndSide(nativeRows.get(k)))
				{
					times[k] = rec.timestamp;
					next = k + 1;
					lastMatched = j;
					break;
				}
			}
		}
		return lastMatched + 1;
	}

	/**
	 * Find how many new trades were pushed onto the top of the list since {@code prev}
	 * was captured. Prefers the smallest shift of at least {@code minShift}; returns -1
	 * if the two lists do not overlap at all.
	 */
	static int findShift(List<GeHistoryEntry> current, List<GeHistoryEntry> prev, int minShift)
	{
		if (prev.isEmpty()) return -1;

		for (int k = Math.max(0, minShift); k < current.size(); k++)
		{
			if (overlaps(current, prev, k)) return k;
		}
		// Fall back to any overlap.
		for (int k = 0; k < Math.min(minShift, current.size()); k++)
		{
			if (overlaps(current, prev, k)) return k;
		}
		return -1;
	}

	private static boolean overlaps(List<GeHistoryEntry> current, List<GeHistoryEntry> prev, int shift)
	{
		int len = Math.min(current.size() - shift, prev.size());
		if (len <= 0) return false;
		for (int i = 0; i < len; i++)
		{
			if (!current.get(shift + i).sameTrade(prev.get(i))) return false;
		}
		return true;
	}

	/** @return true if the archive was cut down to {@code maxArchive} */
	private boolean trim(int maxArchive)
	{
		if (data.archive.size() <= maxArchive) return false;
		data.archive = new ArrayList<>(data.archive.subList(0, Math.max(0, maxArchive)));
		return true;
	}

	// ── Persistence ──────────────────────────────────────────────────────────

	private File fileFor(long hash)
	{
		return new File(directory, "ge-history-" + hash + ".json");
	}

	private AccountData load(File file)
	{
		if (file.exists())
		{
			try
			{
				String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
				AccountData loaded = gson.fromJson(json, AccountData.class);
				if (loaded != null)
				{
					loaded.fillDefaults();
					return loaded;
				}
			}
			catch (Exception e)
			{
				log.warn("Could not read GE history file {}", file, e);
			}
		}
		AccountData fresh = new AccountData();
		fresh.fillDefaults();
		return fresh;
	}

	private void save()
	{
		if (data == null) return;
		// Copy the lists so the JSON can be built on the IO thread, off the game thread.
		AccountData copy = new AccountData();
		copy.archive    = new ArrayList<>(data.archive);
		copy.lastNative = data.lastNative == null ? null : new ArrayList<>(data.lastNative);
		copy.live       = new ArrayList<>(data.live);
		File file = fileFor(accountHash);
		try
		{
			ioExecutor.execute(() -> write(file, gson.toJson(copy)));
		}
		catch (RejectedExecutionException ignored)
		{
		}
	}

	private void write(File file, String json)
	{
		try
		{
			if (!directory.exists() && !directory.mkdirs())
			{
				log.warn("Could not create {}", directory);
				return;
			}
			// Unique temp file so overlapping writers (e.g. a quick plugin restart) never share one.
			java.nio.file.Path tmp = Files.createTempFile(directory.toPath(), file.getName(), ".tmp");
			try
			{
				Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
				Files.move(tmp, file.toPath(),
						StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			finally
			{
				Files.deleteIfExists(tmp);
			}
		}
		catch (IOException e)
		{
			log.warn("Could not write GE history file {}", file, e);
		}
	}

	private static class AccountData
	{
		List<GeHistoryEntry> archive;
		List<GeHistoryEntry> lastNative;
		List<GeHistoryEntry> live;

		void fillDefaults()
		{
			if (archive == null) archive = new ArrayList<>();
			if (live == null) live = new ArrayList<>();
		}
	}
}
