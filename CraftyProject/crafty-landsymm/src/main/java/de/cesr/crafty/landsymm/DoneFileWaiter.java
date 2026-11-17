package de.cesr.crafty.landsymm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import de.cesr.crafty.core.cli.CustomLogger;

/**
 * Waits for the other model's {@code <folder>/<year>/done} file, which it writes once that year's outputs are
 * complete.
 *
 * It polls rather than using a watch service: watch events aren't reliable on the network filesystems an HPC
 * cluster uses, and a short poll costs nothing next to a model year. It logs while it waits, and stops the run with
 * the file's path if the timeout passes.
 *
 * PLUM writes {@code <folder>/<year>/error} instead when its year fails (ModelMain.run). That stops the wait at once,
 * rather than after the whole timeout (48e).
 */
public final class DoneFileWaiter {

	public static final String DONE_FILE = "done";
	public static final String ERROR_FILE = "error";

	private static final CustomLogger LOGGER = new CustomLogger(DoneFileWaiter.class);

	private final Duration pollInterval;
	private final Duration logInterval;

	/** Polls every 2 seconds and logs every 5 minutes. */
	public DoneFileWaiter() {
		this(Duration.ofSeconds(2), Duration.ofMinutes(5));
	}

	public DoneFileWaiter(Duration pollInterval, Duration logInterval) {
		this.pollInterval = pollInterval;
		this.logInterval = logInterval;
	}

	/** The done file for a year: {@code <folder>/<year>/done}. */
	public static Path doneFile(Path folder, int year) {
		return folder.resolve(String.valueOf(year)).resolve(DONE_FILE);
	}

	/** The error file for a year: {@code <folder>/<year>/error}, which PLUM writes when its year fails. */
	public static Path errorFile(Path folder, int year) {
		return folder.resolve(String.valueOf(year)).resolve(ERROR_FILE);
	}

	/**
	 * Returns once {@code <folder>/<year>/done} exists, at once if it already does. Stops with a
	 * {@link CouplingInputException} naming the file if it hasn't appeared within the timeout, or at once if the
	 * year's error file appears first.
	 */
	public void await(Path folder, int year, Duration timeout) {
		Path done = doneFile(folder, year);
		Path error = errorFile(folder, year);
		if (Files.exists(done)) {
			return;
		}
		stopIfError(error);
		LOGGER.info("Waiting for " + done);
		long start = System.nanoTime();
		long nextLog = logInterval.toNanos();
		try {
			while (true) {
				Thread.sleep(pollInterval.toMillis());
				long waited = System.nanoTime() - start;
				if (Files.exists(done)) {
					LOGGER.info("Found " + done + " after " + Duration.ofNanos(waited).toSeconds() + " s");
					return;
				}
				stopIfError(error);
				if (waited >= timeout.toNanos()) {
					throw new CouplingInputException("No " + done + " after " + describe(timeout)
							+ " (plum_wait_timeout_minutes). The other model may have stopped: check its log");
				}
				if (waited >= nextLog) {
					LOGGER.info("Still waiting for " + done + " after " + Duration.ofNanos(waited).toMinutes() + " min");
					nextLog += logInterval.toNanos();
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CouplingInputException("Interrupted while waiting for " + done);
		}
	}

	private static void stopIfError(Path error) {
		if (Files.exists(error)) {
			throw new CouplingInputException("Found " + error + ": the other model's year failed, so there will be no "
					+ DONE_FILE + " file. Check its log");
		}
	}

	private static String describe(Duration d) {
		return d.toMinutes() > 0 ? d.toMinutes() + " min" : d.toMillis() + " ms";
	}
}
