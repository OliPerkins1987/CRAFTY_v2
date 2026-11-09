package de.cesr.crafty.react.data;

/**
 * How a reactive crops AFT sets its nitrogen, from {@code react_N_type}:
 * <ul>
 * <li>{@code Prospect}: responds to price through prospect theory. {@code react_N_par} is the AFT's
 * inertia: how much better, per kg N, a change in net return must be before the AFT acts on it. It is
 * subtracted from the marginal return before the value function, so 0 means "act on any gain";</li>
 * <li>{@code Capital}: follows a capital, rising in a straight line from {@code react_N_min} (0 when blank)
 * to {@code Nfert_rate}:
 * {@code N = react_N_min + (Nfert_rate − react_N_min) × min(1, capital × react_N_par)}.</li>
 * </ul>
 * Case is ignored when reading.
 */
public enum NitrogenMode {
	PROSPECT("Prospect"), CAPITAL("Capital");

	private final String label;

	NitrogenMode(String label) {
		this.label = label;
	}

	/** The mode with this label, ignoring case, or null if there is none. */
	public static NitrogenMode fromLabel(String text) {
		for (NitrogenMode mode : values()) {
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
