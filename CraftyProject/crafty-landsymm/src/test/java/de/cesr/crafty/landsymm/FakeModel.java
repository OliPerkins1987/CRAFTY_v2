package de.cesr.crafty.landsymm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A stand-in for CRAFTY or PLUM in the launcher's tests: a real child JVM that runs a script of steps given as
 * arguments, in order.
 * <ul>
 * <li>{@code write=<file>}: makes an empty file, and its folders;</li>
 * <li>{@code copy=<from>*<to>}: copies a file, or every file in a folder into a folder;</li>
 * <li>{@code wait=<file>}: waits for a file, for up to a minute, then exits with 3;</li>
 * <li>{@code sleep=<ms>};</li>
 * <li>{@code grandchild=<pid file>}: starts another FakeModel that hangs, and writes its process id to the file;</li>
 * <li>{@code hang}: sleeps until it's stopped;</li>
 * <li>{@code exit=<code>}.</li>
 * </ul>
 * With no exit step it exits with 0.
 */
public final class FakeModel {

	private FakeModel() {
	}

	/** The command that runs FakeModel with these steps, on the tests' java and class path. */
	static List<String> command(String... steps) {
		List<String> c = new ArrayList<>(List.of(ModelProcess.javaExecutable().toString(), "-cp",
				System.getProperty("java.class.path"), FakeModel.class.getName()));
		c.addAll(List.of(steps));
		return c;
	}

	public static void main(String[] args) throws Exception {
		for (String step : args) {
			int eq = step.indexOf('=');
			String name = eq < 0 ? step : step.substring(0, eq);
			String arg = eq < 0 ? "" : step.substring(eq + 1);
			switch (name) {
			case "write" -> {
				Path file = Paths.get(arg);
				Files.createDirectories(file.getParent());
				Files.writeString(file, "");
			}
			case "copy" -> {
				String[] fromTo = arg.split("\\*", 2);
				copy(Paths.get(fromTo[0]), Paths.get(fromTo[1]));
			}
			case "wait" -> {
				long end = System.nanoTime() + 60_000_000_000L;
				while (!Files.exists(Paths.get(arg))) {
					if (System.nanoTime() > end) {
						System.out.println("FakeModel: gave up waiting for " + arg);
						System.exit(3);
					}
					Thread.sleep(50);
				}
			}
			case "sleep" -> Thread.sleep(Long.parseLong(arg));
			case "grandchild" -> {
				Process child = new ProcessBuilder(command("hang")).inheritIO().start();
				Files.writeString(Paths.get(arg), String.valueOf(child.pid()));
			}
			case "hang" -> Thread.sleep(Long.MAX_VALUE);
			case "exit" -> System.exit(Integer.parseInt(arg));
			default -> {
				System.out.println("FakeModel: unknown step " + step);
				System.exit(4);
			}
			}
		}
		System.exit(0);
	}

	private static void copy(Path from, Path to) throws Exception {
		if (Files.isDirectory(from)) {
			Files.createDirectories(to);
			try (Stream<Path> files = Files.list(from)) {
				for (Path f : files.toList()) {
					Files.copy(f, to.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} else {
			Files.createDirectories(to.getParent());
			Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
