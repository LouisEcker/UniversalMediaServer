/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2011 G. Zsombor
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.network;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import net.pms.io.BasicSystemUtils;
import net.pms.io.OutputParams;
import net.pms.io.ProcessWrapperImpl;
import net.pms.io.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Network speed tester class. This can be used in an asynchronous way, as it returns Future objects.
 *
 * Future<Integer> speed = SpeedStats.getInstance().getSpeedInMBits(addr);
 *
 * @see Future
 *
 * @author zsombor <gzsombor@gmail.com>
 *
 */
public class SpeedStats {
	private static SpeedStats instance = new SpeedStats();
	private static ExecutorService executor = Executors.newCachedThreadPool();

	public static SpeedStats getInstance() {
		return instance;
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(SpeedStats.class);

	private final Map<String, Future<Integer>> speedStats = new HashMap<>();

	/**
	 * Returns the estimated networks throughput for the given IP address in
	 * Mb/s from the cache as a {@link Future}. If no value is cached for
	 * {@code addr}, {@code null} is returned.
	 *
	 * @param addr the {@link InetAddress} to lookup.
	 * @param rendererName not in use.
	 * @return The {@link Future} with the estimated network throughput or
	 *         {@code null}.
	 * @deprecated Use {@link #getSpeedInMBitsStored(InetAddress)} instead.
	 */
	@SuppressWarnings("unused")
	@Deprecated
	public Future<Integer> getSpeedInMBitsStored(InetAddress addr, String rendererName) {
		return getSpeedInMBitsStored(addr);
	}

	/**
	 * Returns the estimated networks throughput for the given IP address in
	 * Mb/s from the cache as a {@link Future}. If no value is cached for
	 * {@code addr}, {@code null} is returned.
	 *
	 * @param addr the {@link InetAddress} to lookup.
	 * @return The {@link Future} with the estimated network throughput or
	 *         {@code null}.
	 */
	public Future<Integer> getSpeedInMBitsStored(InetAddress addr) {
		// only look in the store
		// if no pings are done resort to conf values
		synchronized (speedStats) {
			return speedStats.get(addr.getHostAddress());
		}
	}

	/**
	 * Return the network throughput for the given IP address in MBits. It is calculated in the background, and cached,
	 * so only a reference is given to the result, which can be retrieved by calling the get() method on it.
	 *
	 * @param addr
	 * @param rendererName
	 *
	 * @return The network throughput
	 */
	public Future<Integer> getSpeedInMBits(InetAddress addr, String rendererName) {
		synchronized (speedStats) {
			Future<Integer> value = speedStats.get(addr.getHostAddress());
			if (value != null) {
				return value;
			}
			value = executor.submit(new MeasureSpeed(addr, rendererName));
			speedStats.put(addr.getHostAddress(), value);
			return value;
		}
	}

	class MeasureSpeed implements Callable<Integer> {
		InetAddress addr;
		String rendererName;

		public MeasureSpeed(InetAddress addr, String rendererName) {
			this.addr = addr;
			this.rendererName = rendererName != null ? rendererName.replaceAll("\n", "") : "Unknown";
		}

		@Override
		public Integer call() throws Exception {
			try {
				return doCall();
			} catch (Exception e) {
				LOGGER.warn("Error measuring network throughput : " + e.getMessage(), e);
				throw e;
			}
		}

		private Integer doCall() throws Exception {
			String ip = addr.getHostAddress();
			LOGGER.info("Checking IP: {} for {}", ip, rendererName);
			// calling the canonical host name the first time is slow, so we call it in a separate thread
			String hostname = addr.getCanonicalHostName();
			synchronized (speedStats) {
				Future<Integer> otherTask = speedStats.get(hostname);
				if (otherTask != null) {
					// wait a little bit
					try {
						// probably we are waiting for ourself to finish the work...
						Integer value = otherTask.get(200, TimeUnit.MILLISECONDS);
						// if the other task already calculated the speed, we get the result,
						// unless we do it now
						if (value != null) {
							return value;
						}
					} catch (TimeoutException e) {
						LOGGER.trace("We couldn't get the value based on the canonical name");
					}
				}
			}

			if (!ip.equals(hostname)) {
				LOGGER.info("Renderer {} found on address: {} ({})", rendererName, hostname, ip);
			} else {
				LOGGER.info("Renderer {} found on address: {}", rendererName, ip);
			}

			int[] sizes = {512, 1476, 9100, 32000, 64000};
			double bps = 0;
			int cnt = 0;

			for (int i = 0; i < sizes.length; i++) {
				double p = doPing(sizes[i]);
				if (p != 0) {
					bps += p;
					cnt++;
				}
			}
			double speedInMbits1 = bps / (cnt * 1000000);
			LOGGER.info("Renderer {} has an estimated network speed of {} Mb/s", rendererName, speedInMbits1);
			int speedInMbits = (int) speedInMbits1;
			if (speedInMbits1 < 1.0) {
				speedInMbits = -1;
			}
			synchronized (speedStats) {
				CompletedFuture<Integer> result = new CompletedFuture<>(speedInMbits);
				// update the statistics with a computed future value
				speedStats.put(ip, result);
				speedStats.put(hostname, result);
			}
			return speedInMbits;
		}

		private double doPing(int size) {
			// let's get that speed
			OutputParams op = new OutputParams(null);
			op.log = true;
			op.maxBufferSize = 1;
			SystemUtils sysUtil = BasicSystemUtils.INSTANCE;
			final ProcessWrapperImpl pw = new ProcessWrapperImpl(sysUtil.getPingCommand(addr.getHostAddress(), 5, size), op, true, false);
			Runnable r = new Runnable() {
				@Override
				public void run() {
					try {
						Thread.sleep(3000);
					} catch (InterruptedException e) {
					}
					pw.stopProcess();
				}
			};

			Thread failsafe = new Thread(r, "SpeedStats Failsafe");
			failsafe.start();
			pw.runInSameThread();
			List<String> ls = pw.getOtherResults();
			double time = 0;
			int c = 0;
			String timeString;

			for (String line : ls) {
				timeString = sysUtil.parsePingLine(line);
				if (timeString == null) {
					continue;
				}
				try {
					time += Double.parseDouble(timeString);
					c++;
				} catch (NumberFormatException e) {
					// no big deal
					LOGGER.debug("Could not estimate network speed from time: \"" + timeString + "\"");
				}
			}

			if (c > 0) {
				time /= c;
				int frags = sysUtil.getPingPacketFragments(size);
				LOGGER.debug("Estimated speed from ICMP packet size {} in {} fragment(s) is {} bit/s", size, frags, ((size + 8 + (frags * 32)) * 8000 * 2) / time);
				return ((size + 8 + (frags * 32)) * 8000 * 2) / time;
			}
			return time;
		}
	}

	static class CompletedFuture<X> implements Future<X> {
		X value;

		public CompletedFuture(X value) {
			this.value = value;
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return false;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public boolean isDone() {
			return true;
		}

		@Override
		public X get() throws InterruptedException, ExecutionException {
			return value;
		}

		@Override
		public X get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
			return value;
		}
	}
}
