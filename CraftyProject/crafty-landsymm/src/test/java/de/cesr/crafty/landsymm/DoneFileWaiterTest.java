package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoneFileWaiterTest {

	@TempDir
	Path tempDir;

	private final DoneFileWaiter waiter = new DoneFileWaiter(Duration.ofMillis(50), Duration.ofMillis(200));

	@Test
	void theDoneFileIsYearSlashDone() {
		assertEquals(tempDir.resolve("1991").resolve("done"), DoneFileWaiter.doneFile(tempDir, 1991));
	}

	@Test
	void returnsAtOnceWhenTheFileIsAlreadyThere() throws IOException {
		Path done = DoneFileWaiter.doneFile(tempDir, 1991);
		Files.createDirectories(done.getParent());
		Files.createFile(done);

		long start = System.nanoTime();
		waiter.await(tempDir, 1991, Duration.ofMillis(10));

		assertTrue(System.nanoTime() - start < Duration.ofMillis(500).toNanos());
	}

	@Test
	void returnsWhenAnotherProcessWritesTheFile() throws Exception {
		Path done = DoneFileWaiter.doneFile(tempDir, 1992);
		Thread writer = new Thread(() -> {
			try {
				Thread.sleep(300);
				Files.createDirectories(done.getParent()); // the year folder doesn't exist yet
				Files.createFile(done);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		writer.start();

		waiter.await(tempDir, 1992, Duration.ofSeconds(10));

		assertTrue(Files.exists(done));
		writer.join();
	}

	@Test
	void stopsAfterTheTimeoutNamingTheFile() {
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> waiter.await(tempDir, 1993, Duration.ofMillis(300)));

		assertTrue(e.getMessage().contains(DoneFileWaiter.doneFile(tempDir, 1993).toString()), e.getMessage());
		assertTrue(e.getMessage().contains("plum_wait_timeout_minutes"), e.getMessage());
	}

	@Test
	void plumsErrorFileStopsTheWaitAtOnce() throws Exception {
		Path error = DoneFileWaiter.errorFile(tempDir, 1994);
		Thread writer = new Thread(() -> {
			try {
				Thread.sleep(300);
				Files.createDirectories(error.getParent());
				Files.createFile(error);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		writer.start();

		long start = System.nanoTime();
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> waiter.await(tempDir, 1994, Duration.ofSeconds(20)));

		assertTrue(System.nanoTime() - start < Duration.ofSeconds(10).toNanos(), "not the whole timeout");
		assertTrue(e.getMessage().contains(error.toString()), "the error file, not a timeout: " + e.getMessage());
		writer.join();
	}

	@Test
	void anErrorFileAlreadyThereStopsItTooButADoneFileWins() throws IOException {
		Path error = DoneFileWaiter.errorFile(tempDir, 1995);
		Files.createDirectories(error.getParent());
		Files.createFile(error);

		long start = System.nanoTime();
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> waiter.await(tempDir, 1995, Duration.ofSeconds(20)));

		assertTrue(System.nanoTime() - start < Duration.ofSeconds(10).toNanos(), "not the whole timeout");
		assertTrue(e.getMessage().contains(error.toString()), "the error file, not a timeout: " + e.getMessage());
		Files.createFile(DoneFileWaiter.doneFile(tempDir, 1995));
		waiter.await(tempDir, 1995, Duration.ofMillis(10)); // the year's outputs were complete before the error
	}
}
