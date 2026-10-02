package de.cesr.crafty.react.data;

/**
 * How a reactive forestry AFT sets its rotation length, from {@code react_R_type}. The rotation starts at
 * the AFT's {@code Other_intensity}, which for a forestry AFT holds its initial rotation in years.
 * <ul>
 * <li>{@code Prospect}: follows profit, one grid step a year, with pasture's stocking rule: a shorter
 * rotation must beat today's profit by the threshold {@code react_R_par} (and by $1/ha), a longer one only
 * has to beat it;</li>
 * <li>{@code Capital}: follows a capital ({@code react_R_capital}), the grid rotation nearest to
 * {@code initial rotation − 1000 × react_R_par × capital} years, worked out afresh each year.</li>
 * </ul>
 * Case is ignored when reading.
 */
public enum RotationMode {
	PROSPECT("Prospect"), CAPITAL("Capital");

	private final String label;

	RotationMode(String label) {
		this.label = label;
	}

	/** The mode with this label, ignoring case, or null if there is none. */
	public static RotationMode fromLabel(String text) {
		for (RotationMode mode : values()) {
			if (mode.label.equalsIgnoreCase(text)) {
				return mode;
			}
		}
		return null;
	}

	@Override
	public String toString() {
		return label;
	}
}
