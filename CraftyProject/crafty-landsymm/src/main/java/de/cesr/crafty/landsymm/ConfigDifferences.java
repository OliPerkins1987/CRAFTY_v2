package de.cesr.crafty.landsymm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * A scenario run's config_vs_calibration.txt (48e PLAN section 3.2): the settings in its CRAFTY and PLUM configs that
 * differ from its calibration run's, apart from the keys the launcher sets. It only records them: a scenario may
 * differ on purpose.
 */
final class ConfigDifferences {

	private ConfigDifferences() {
	}

	/** One setting that differs; null where it isn't set. */
	record Difference(String key, String calibration, String thisRun) {
	}

	/** The top-level config.yaml settings that differ, in key order. */
	static List<Difference> crafty(Map<String, Object> calibration, Map<String, Object> thisRun,
			Collection<String> ignored) {
		Set<String> keys = new TreeSet<>(calibration.keySet());
		keys.addAll(thisRun.keySet());
		keys.removeAll(ignored);
		List<Difference> out = new ArrayList<>();
		for (String key : keys) {
			String a = calibration.containsKey(key) ? String.valueOf(calibration.get(key)) : null;
			String b = thisRun.containsKey(key) ? String.valueOf(thisRun.get(key)) : null;
			if (!Objects.equals(a, b)) {
				out.add(new Difference(key, a, b));
			}
		}
		return out;
	}

	/** The config.properties settings that differ, in key order. */
	static List<Difference> plum(Properties calibration, Properties thisRun, Collection<String> ignored) {
		Set<String> keys = new TreeSet<>(calibration.stringPropertyNames());
		keys.addAll(thisRun.stringPropertyNames());
		keys.removeAll(ignored);
		List<Difference> out = new ArrayList<>();
		for (String key : keys) {
			String a = calibration.getProperty(key);
			String b = thisRun.getProperty(key);
			if (!Objects.equals(a, b)) {
				out.add(new Difference(key, a, b));
			}
		}
		return out;
	}

	/** The file's text. */
	static String text(Path calibrationRun, List<Difference> crafty, List<Difference> plum) {
		StringBuilder s = new StringBuilder();
		s.append("# Settings in this run's configs that differ from its calibration run's (").append(calibrationRun)
				.append("),\n# apart from the keys the launcher sets. A record only: a scenario may differ on purpose.\n");
		section(s, "CRAFTY (crafty/config.yaml)", crafty);
		section(s, "PLUM (plum/config.properties)", plum);
		return s.toString();
	}

	private static void section(StringBuilder s, String title, List<Difference> differences) {
		s.append('\n').append(title).append(": ").append(differences.size()).append(" difference(s)\n");
		for (Difference d : differences) {
			s.append("  ").append(d.key()).append(": ").append(shown(d.calibration())).append(" -> ")
					.append(shown(d.thisRun())).append('\n');
		}
	}

	private static String shown(String value) {
		return value == null ? "(not set)" : value;
	}
}
