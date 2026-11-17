package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelProcessTest {

	@TempDir
	Path tempDir;

	@Test
	void itsOutputGoesToItsLogAndItsExitCodeIsKept() throws Exception {
		ModelProcess p = new ModelProcess("PLUM", FakeModel.command("unknown-step"), tempDir.resolve("plum"),
				tempDir.resolve("stdout.txt"), null);

		assertNull(p.exitCode(), "not started");
		p.start();
		while (p.isAlive()) {
			Thread.sleep(20);
		}

		assertEquals(4, p.exitCode());
		assertFalse(p.stoppedByLauncher());
		assertEquals(List.of("FakeModel: unknown step unknown-step"), p.lastLines(5));
	}

	@Test
	void stoppingItStopsWhatItStartedToo() throws Exception {
		Path pidFile = tempDir.resolve("grandchild.pid");
		Path folder = Files.createDirectories(tempDir.resolve("plum"));
		ModelProcess p = new ModelProcess("PLUM", FakeModel.command("grandchild=" + pidFile, "hang"), folder,
				folder.resolve("log.txt"), null);
		p.start();
		long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) {
			assertTrue(System.nanoTime() < end, "the grandchild didn't start");
			Thread.sleep(20);
		}
		long grandchild = Long.parseLong(Files.readString(pidFile).strip());

		p.stopTree(Duration.ofSeconds(10));

		assertFalse(p.isAlive());
		assertTrue(p.stoppedByLauncher());
		Optional<ProcessHandle> h = ProcessHandle.of(grandchild);
		assertTrue(h.isEmpty() || !h.get().isAlive(), "on Windows, PLUM's gams.exe would otherwise keep running");
		deleteWhenReleased(folder); // Windows lets go of a stopped process's files a moment after it has gone
	}

	/** Deletes a folder, retrying for a few seconds while files in it are still held. */
	private static void deleteWhenReleased(Path folder) throws Exception {
		long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (true) {
			try (var files = Files.walk(folder)) {
				for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(file);
				}
				return;
			} catch (java.io.IOException e) {
				if (System.nanoTime() > end) {
					throw e;
				}
				Thread.sleep(200);
			}
		}
	}

	@Test
	void plumGetsGamsFirstOnItsPath() throws Exception {
		Path gams = tempDir.resolve("gams");
		Path script = tempDir.resolve("path.txt");
		// a child JVM that writes the PATH it was given to a file
		List<String> printPath = List.of(ModelProcess.javaExecutable().toString(), "-cp",
				System.getProperty("java.class.path"), PrintPath.class.getName(), script.toString());
		ModelProcess p = new ModelProcess("PLUM", printPath, tempDir, tempDir.resolve("log.txt"), gams);

		p.start();
		while (p.isAlive()) {
			Thread.sleep(20);
		}

		assertEquals(0, p.exitCode(), String.join("\n", p.lastLines(20)));
		assertTrue(Files.readString(script).startsWith(gams + File.pathSeparator), Files.readString(script));
	}

	@Test
	void theyRunOnTheLaunchersJava() {
		Path java = ModelProcess.javaExecutable();

		assertTrue(Files.isRegularFile(java), java.toString());
		assertTrue(java.startsWith(Path.of(System.getProperty("java.home"))));
	}

	/** Writes its PATH to the file named by its argument. */
	public static final class PrintPath {
		public static void main(String[] args) throws Exception {
			String path = System.getenv().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("PATH"))
					.map(java.util.Map.Entry::getValue).findFirst().orElse("");
			Files.writeString(Path.of(args[0]), path);
		}
	}
}
