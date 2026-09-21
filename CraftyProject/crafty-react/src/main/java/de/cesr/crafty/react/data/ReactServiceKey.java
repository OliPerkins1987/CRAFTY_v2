package de.cesr.crafty.react.data;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Where each service's data lives in the LPJ-GUESS inputs, from {@code csv/Services.csv}:
 * <ul>
 * <li>{@code LPJG_name}: the name used in the LPJ-GUESS column headers (for example {@code C3cereals} →
 * {@code CerealsC3}). Blank means the service name itself.</li>
 * <li>{@code LPJG_type}: {@code crops}, {@code pasture} or {@code forestry}; blank for services with no
 * LPJ-GUESS input.</li>
 * </ul>
 * Core reads the same file for the service list and ignores these two columns.
 */
public final class ReactServiceKey {

	public static final String NAME = "Name";
	public static final String LPJG_NAME = "LPJG_name";
	public static final String LPJG_TYPE = "LPJG_type";

	private record Entry(String lpjgName, LpjgType type) {
	}

	private final Path file;
	private final Map<String, Entry> entries;

	private ReactServiceKey(Path file, Map<String, Entry> entries) {
		this.file = file;
		this.entries = entries;
	}

	public static ReactServiceKey load(Path file) {
		ReactCsv.Table table = ReactCsv.readAll(file);
		table.requireColumns(NAME, LPJG_NAME, LPJG_TYPE);

		Map<String, Entry> entries = new LinkedHashMap<>();
		for (int row = 0; row < table.rowCount(); row++) {
			String where = file + ", line " + table.lineNumber(row) + ": ";
			String service = table.get(row, NAME);
			if (service.isEmpty()) {
				throw new ReactInputException(where + NAME + " is blank");
			}
			String lpjgName = table.get(row, LPJG_NAME);
			String typeText = table.get(row, LPJG_TYPE);
			LpjgType type = null;
			if (!typeText.isEmpty()) {
				type = LpjgType.fromLabel(typeText);
				if (type == null) {
					throw new ReactInputException(where + LPJG_TYPE + " of " + service + " is \"" + typeText
							+ "\"; it must be one of " + Arrays.toString(LpjgType.values()) + " or blank");
				}
			}
			Entry entry = new Entry(lpjgName.isEmpty() ? service : lpjgName, type);
			if (entries.put(service, entry) != null) {
				throw new ReactInputException(where + "service " + service + " appears more than once");
			}
		}
		return new ReactServiceKey(file, Collections.unmodifiableMap(entries));
	}

	public Path file() {
		return file;
	}

	public Set<String> services() {
		return entries.keySet();
	}

	public boolean contains(String service) {
		return entries.containsKey(service);
	}

	/** The service's name in LPJ-GUESS column headers. */
	public String lpjgName(String service) {
		return entry(service).lpjgName();
	}

	/** The service's LPJ-GUESS input type, or null if it has none. */
	public LpjgType lpjgType(String service) {
		return entry(service).type();
	}

	private Entry entry(String service) {
		Entry entry = entries.get(service);
		if (entry == null) {
			throw new ReactInputException(file + " has no service " + service);
		}
		return entry;
	}
}
