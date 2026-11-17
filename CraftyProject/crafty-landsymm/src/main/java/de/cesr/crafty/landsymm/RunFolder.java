package de.cesr.crafty.landsymm;

import java.nio.file.Path;

/**
 * The files and folders of one coupled run (48e PLAN section 2). Each run has its own folder, which the launcher
 * makes and never re-uses.
 *
 * @param root {@code <output_root>/<run_name>}
 */
record RunFolder(Path root) {

	Path master() {
		return root.resolve("master.yaml");
	}

	Path record() {
		return root.resolve(RunRecord.FILE);
	}

	Path launcherLog() {
		return root.resolve("launcher.log");
	}

	Path timeline() {
		return root.resolve("timeline.csv");
	}

	Path configDifferences() {
		return root.resolve("config_vs_calibration.txt");
	}

	/** CRAFTY → PLUM: {@code <year>/production.csv} and {@code done} (PLUM's CRAFTY_PRODUCTION_DIR). */
	Path production() {
		return root.resolve("production");
	}

	Path crafty() {
		return root.resolve("crafty");
	}

	Path craftyConfig() {
		return crafty().resolve("config.yaml");
	}

	/** CRAFTY's output_path; its outputs go in {@code <scenario>/<run_name>/} inside it. */
	Path craftyOutput() {
		return crafty().resolve("output");
	}

	Path craftyLog() {
		return crafty().resolve("crafty.log");
	}

	Path plum() {
		return root.resolve("plum");
	}

	Path plumConfig() {
		return plum().resolve("config.properties");
	}

	/** PLUM's OUTPUT_DIR. */
	Path plumOutput() {
		return plum().resolve("output");
	}

	Path plumLog() {
		return plum().resolve("stdout.txt");
	}

	/** Where the jar CRAFTY runs from goes: one copy per build, shared by the runs in the output root (48e L2). */
	Path jars() {
		return root.getParent().resolve("jars");
	}
}
