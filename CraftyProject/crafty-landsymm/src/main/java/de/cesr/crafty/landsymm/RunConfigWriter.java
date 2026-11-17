package de.cesr.crafty.landsymm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a run's copy of a config file (48e PLAN section 3): the base file's text is kept line for line, each line
 * that sets a key the launcher sets is commented out with {@link #MARKER}, and the launcher's keys go in one block at
 * the end. So a diff of the copy against the base shows exactly what the launcher changed, and the base file itself
 * is never touched.
 *
 * A key the launcher sets has to be a one-line value in the base file. A YAML list or block can't be commented out
 * line by line safely, so it's reported as a problem.
 */
final class RunConfigWriter {

	/** Starts each base line the launcher replaced. */
	static final String MARKER = "# [launcher] ";

	private static final Pattern YAML_TOP_KEY = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*:(.*)$");
	private static final Pattern PROPERTIES_KEY = Pattern.compile("^\\s*([^\\s#!=:][^\\s=:]*)\\s*(?:[=:\\s]|$).*$");

	private RunConfigWriter() {
	}

	/**
	 * A run's copy of a config file.
	 *
	 * @param text     the copy's text
	 * @param replaced the base file's lines that were commented out, as they were
	 * @param problems why the copy can't be made; empty if it was
	 */
	record Copy(String text, List<String> replaced, List<String> problems) {
	}

	/**
	 * A YAML copy. Top-level lines setting a key in {@code values}, or one of its other names in {@code otherNames}
	 * (old name to key), are commented out; {@code values} go at the end, each value already written as YAML.
	 */
	static Copy yaml(String base, Map<String, String> values, Map<String, String> otherNames, List<String> header,
			String what) {
		String nl = base.contains("\r\n") ? "\r\n" : "\n";
		String[] lines = base.split("\r?\n", -1);
		List<String> out = new ArrayList<>();
		List<String> replaced = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		header.forEach(h -> out.add("# " + h));
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			Matcher m = YAML_TOP_KEY.matcher(line);
			if (m.matches()) {
				String key = m.group(1);
				String target = values.containsKey(key) ? key : otherNames.get(key);
				if (target != null) {
					String value = stripYamlComment(m.group(2)).strip();
					if (!isOneLineYamlValue(value)) {
						problems.add(what + " sets " + key + " over more than one line (line " + (i + 1)
								+ "). The launcher sets " + target + ", so write it on one line, or leave it out");
					} else {
						out.add(MARKER + line);
						replaced.add(line);
						continue;
					}
				}
			}
			out.add(line);
		}
		closeBlock(out);
		out.add("# ---- Set by LandSymmLauncher ----");
		values.forEach((key, value) -> out.add(key + ": " + value));
		out.add("");
		return new Copy(String.join(nl, out), replaced, problems);
	}

	/**
	 * A Java properties copy, read the way PLUM reads it ({@code java.util.Properties}). Lines setting a key in
	 * {@code values} are commented out; {@code values} go at the end, escaped for a properties file.
	 */
	static Copy properties(String base, Map<String, String> values, List<String> header, String what) {
		String nl = base.contains("\r\n") ? "\r\n" : "\n";
		String[] lines = base.split("\r?\n", -1);
		List<String> out = new ArrayList<>();
		List<String> replaced = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		header.forEach(h -> out.add("# " + h));
		boolean continued = false; // this line carries on the line before, which ended with an odd number of \
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			boolean comment = !continued && isPropertiesComment(line); // only a logical line's start can be one
			boolean continues = !comment && endsWithOddBackslashes(line);
			if (!continued && !comment) {
				Matcher m = PROPERTIES_KEY.matcher(line);
				if (m.matches() && values.containsKey(m.group(1))) {
					if (continues) {
						problems.add(what + " sets " + m.group(1) + " over more than one line (line " + (i + 1)
								+ ", ending with \\). The launcher sets it, so write it on one line, or leave it out");
					} else {
						out.add(MARKER + line);
						replaced.add(line);
						continued = false;
						continue;
					}
				}
			}
			out.add(line);
			continued = continues;
		}
		closeBlock(out);
		out.add("# ---- Set by LandSymmLauncher ----");
		values.forEach((key, value) -> out.add(key + "=" + propertiesValue(value)));
		out.add("");
		return new Copy(String.join(nl, out), replaced, problems);
	}

	/** A text value for YAML: double-quoted, with \ and " escaped. */
	static String yamlString(String value) {
		return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}

	/** A value for YAML: true/false, a number, a flow list of plain words, or a quoted string. */
	static String yamlValue(Object value) {
		if (value instanceof Boolean || value instanceof Number) {
			return String.valueOf(value);
		}
		if (value instanceof List<?> list) {
			List<String> items = new ArrayList<>();
			list.forEach(item -> items.add(String.valueOf(item)));
			return "[" + String.join(", ", items) + "]";
		}
		return yamlString(String.valueOf(value));
	}

	/** A path as the launcher writes it in configs: absolute, with forward slashes, which Java reads everywhere. */
	static String slashes(Path path) {
		return path.toAbsolutePath().normalize().toString().replace('\\', '/');
	}

	/** A value for a properties file: \ escaped, a leading space kept, and anything outside printable ASCII as a unicode escape. */
	static String propertiesValue(String value) {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '\\') {
				s.append("\\\\");
			} else if (i == 0 && c == ' ') {
				s.append("\\ ");
			} else if (c < 0x20 || c > 0x7e) {
				s.append(String.format("\\u%04x", (int) c));
			} else {
				s.append(c);
			}
		}
		return s.toString();
	}

	/** Leaves one blank line between the base file's text and the launcher's block. */
	private static void closeBlock(List<String> out) {
		while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
			out.remove(out.size() - 1);
		}
		out.add("");
	}

	/** A YAML value with its trailing comment removed: a # after a space, outside quotes. */
	static String stripYamlComment(String value) {
		boolean single = false;
		boolean dbl = false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '\'' && !dbl) {
				single = !single;
			} else if (c == '"' && !single) {
				dbl = !dbl;
			} else if (c == '#' && !single && !dbl && (i == 0 || Character.isWhitespace(value.charAt(i - 1)))) {
				return value.substring(0, i);
			}
		}
		return value;
	}

	/** Whether a top-level key's value (comment removed) is complete on its line. */
	static boolean isOneLineYamlValue(String value) {
		if (value.isEmpty() || value.startsWith("|") || value.startsWith(">")) {
			return false; // a block, a list or a block scalar on the following lines
		}
		if (value.startsWith("[") || value.startsWith("{")) {
			int depth = 0;
			boolean single = false;
			boolean dbl = false;
			for (char c : value.toCharArray()) {
				if (c == '\'' && !dbl) {
					single = !single;
				} else if (c == '"' && !single) {
					dbl = !dbl;
				} else if (!single && !dbl && (c == '[' || c == '{')) {
					depth++;
				} else if (!single && !dbl && (c == ']' || c == '}')) {
					depth--;
				}
			}
			return depth == 0;
		}
		return true;
	}

	private static boolean isPropertiesComment(String line) {
		String s = line.stripLeading();
		return s.startsWith("#") || s.startsWith("!");
	}

	private static boolean endsWithOddBackslashes(String line) {
		int n = 0;
		for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
			n++;
		}
		return n % 2 == 1;
	}
}
