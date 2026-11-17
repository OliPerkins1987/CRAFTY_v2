package de.cesr.crafty.landsymm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs the models a step of a coupled run needs, and watches them until they've all exited (48e PLAN section 6.2):
 * <ul>
 * <li>a model that exits with anything but 0 stops the others at once, with their whole process trees;</li>
 * <li>PLUM's {@code <year>/error} file gives it {@code errorGrace} to exit, then it's stopped the same way;</li>
 * <li>each new done file is logged and timed in the timeline;</li>
 * <li>{@link #stopAll(String)}, from the launcher's shutdown hook, stops everything.</li>
 * </ul>
 */
final class RunSupervisor {

	private static final int LOG_LINES = 20;

	/** How a step ended. */
	record Outcome(boolean succeeded, String failure, Map<String, Integer> exitCodes) {
	}

	/** Watch for PLUM's error files in its output folder, for these years. */
	record ErrorWatch(ModelProcess model, Path folder, int firstYear, int lastYear) {
	}

	private final LauncherLog log;
	private final Duration poll;
	private final Duration errorGrace;
	private final Duration stopWait;
	private final List<ModelProcess> running = new CopyOnWriteArrayList<>();
	private volatile String stopReason;

	RunSupervisor(LauncherLog log, Duration poll, Duration errorGrace, Duration stopWait) {
		this.log = log;
		this.poll = poll;
		this.errorGrace = errorGrace;
		this.stopWait = stopWait;
	}

	/**
	 * Starts the models together and watches them until they've all exited, or one fails and the rest are stopped.
	 *
	 * @param errorWatch PLUM and its output folder, or null
	 * @param timeline   the run's timeline, or null
	 */
	Outcome run(List<ModelProcess> models, ErrorWatch errorWatch, Timeline timeline) {
		Map<String, Integer> codes = new LinkedHashMap<>();
		for (ModelProcess m : models) {
			if (stopReason != null) {
				return stopped(models, codes, stopReason);
			}
			try {
				m.start();
				running.add(m);
				log.line(m.name() + " started (process " + m.pid() + "); its output goes to " + m.log());
			} catch (IOException e) {
				return stopped(models, codes, "Couldn't start " + m.name() + ": " + e + ". Its command was "
						+ m.command());
			}
		}
		long errorSeen = -1;
		Path errorFile = null;
		try {
			while (true) {
				if (timeline != null) {
					timeline.poll(log);
				}
				boolean allExited = true;
				for (ModelProcess m : models) {
					Integer code = m.exitCode();
					if (code == null) {
						allExited = false;
						continue;
					}
					if (!codes.containsKey(m.name())) {
						codes.put(m.name(), code);
						running.remove(m);
						if (stopReason != null) {
							continue; // being stopped: the reason says why
						}
						log.line(m.name() + " exited with code " + code);
						if (code != 0) {
							return stopped(models, codes, m.name() + " stopped with exit code " + code
									+ ". The last lines of " + m.log() + ":\n" + String.join("\n", m.lastLines(LOG_LINES)));
						}
					}
				}
				if (stopReason != null) {
					return stopped(models, codes, stopReason);
				}
				if (allExited) {
					if (timeline != null) {
						timeline.poll(log);
					}
					return new Outcome(true, null, codes);
				}
				if (errorWatch != null && errorWatch.model().isAlive()) {
					if (errorFile == null) {
						errorFile = errorFile(errorWatch);
						if (errorFile != null) {
							errorSeen = System.nanoTime();
							log.line(errorWatch.model().name() + " wrote " + errorFile + "; it has "
									+ errorGrace.toSeconds() + " s to exit before it's stopped");
						}
					} else if (System.nanoTime() - errorSeen > errorGrace.toNanos()) {
						return stopped(models, codes, errorWatch.model().name() + " wrote " + errorFile
								+ " and was still running " + errorGrace.toSeconds() + " s later. The last lines of "
								+ errorWatch.model().log() + ":\n"
								+ String.join("\n", errorWatch.model().lastLines(LOG_LINES)));
					}
				}
				Thread.sleep(poll.toMillis());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return stopped(models, codes, "The launcher was interrupted");
		}
	}

	/** Stops every running model, with its process tree; for the launcher's shutdown hook. Returns their names. */
	List<String> stopAll(String reason) {
		stopReason = reason;
		List<String> stopped = new ArrayList<>();
		for (ModelProcess m : running) {
			if (m.isAlive()) {
				log.line("Stopping " + m.name() + " (process " + m.pid() + "): " + reason);
				m.stopTree(stopWait);
				stopped.add(m.name());
			}
		}
		return stopped;
	}

	private Outcome stopped(List<ModelProcess> models, Map<String, Integer> codes, String failure) {
		for (ModelProcess m : models) {
			if (m.isAlive()) {
				log.line("Stopping " + m.name() + " (process " + m.pid() + ")");
				m.stopTree(stopWait);
			}
			running.remove(m);
			Integer code = m.exitCode();
			if (code != null && !codes.containsKey(m.name())) {
				codes.put(m.name(), code);
			}
		}
		return new Outcome(false, failure, codes);
	}

	private static Path errorFile(ErrorWatch w) {
		for (int year = w.firstYear(); year <= w.lastYear(); year++) {
			Path error = DoneFileWaiter.errorFile(w.folder(), year);
			if (Files.exists(error)) {
				return error;
			}
		}
		return null;
	}

	/**
	 * When each year's done files appeared (48e PLAN section 6.2): CRAFTY's production and PLUM's year. Written to
	 * timeline.csv as they appear.
	 */
	static final class Timeline {

		private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

		private final Path production;
		private final Path plumOutput;
		private final int firstYear;
		private final int craftyLastYear;
		private final int plumLastYear;
		private final Path csv;
		private final Map<Integer, String> craftyDone = new LinkedHashMap<>();
		private final Map<Integer, String> plumDone = new LinkedHashMap<>();

		Timeline(Path production, Path plumOutput, int firstYear, int craftyLastYear, int plumLastYear, Path csv) {
			this.production = production;
			this.plumOutput = plumOutput;
			this.firstYear = firstYear;
			this.craftyLastYear = craftyLastYear;
			this.plumLastYear = plumLastYear;
			this.csv = csv;
		}

		/** Notes the done files that have appeared since the last poll. */
		void poll(LauncherLog log) {
			boolean changed = false;
			for (int year = firstYear; year <= Math.max(craftyLastYear, plumLastYear); year++) {
				if (year <= craftyLastYear && !craftyDone.containsKey(year)
						&& Files.exists(DoneFileWaiter.doneFile(production, year))) {
					craftyDone.put(year, LocalDateTime.now().format(TIME));
					log.line("CRAFTY's " + year + " production is ready for PLUM");
					changed = true;
				}
				if (year <= plumLastYear && !plumDone.containsKey(year)
						&& Files.exists(DoneFileWaiter.doneFile(plumOutput, year))) {
					plumDone.put(year, LocalDateTime.now().format(TIME));
					log.line("PLUM's " + year + " is done");
					changed = true;
				}
			}
			if (changed) {
				write();
			}
		}

		/** CRAFTY's production years with no done file. */
		List<Integer> craftyMissing() {
			return missing(production, craftyLastYear);
		}

		/** PLUM's years with no done file. */
		List<Integer> plumMissing() {
			return missing(plumOutput, plumLastYear);
		}

		private List<Integer> missing(Path folder, int lastYear) {
			List<Integer> missing = new ArrayList<>();
			for (int year = firstYear; year <= lastYear; year++) {
				if (!Files.exists(DoneFileWaiter.doneFile(folder, year))) {
					missing.add(year);
				}
			}
			return missing;
		}

		private void write() {
			StringBuilder s = new StringBuilder("Year,CRAFTY_done,PLUM_done\n");
			for (int year = firstYear; year <= Math.max(craftyLastYear, plumLastYear); year++) {
				s.append(year).append(',').append(craftyDone.getOrDefault(year, "")).append(',')
						.append(plumDone.getOrDefault(year, "")).append('\n');
			}
			try {
				Files.writeString(csv, s, StandardCharsets.UTF_8);
			} catch (IOException e) {
				// the timeline is a record only; the log has the same times
			}
		}
	}
}
