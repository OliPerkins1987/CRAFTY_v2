package de.cesr.crafty.react.data;

/**
 * Which kind of LPJ-GUESS input serves a service, from the {@code LPJG_type} column of
 * {@code csv/Services.csv}. The label is also the name of the service's suitability folder, and it
 * decides whether an AFT gets the crop or the pasture update.
 */
public enum LpjgType {
	CROPS("crops"), PASTURE("pasture"), FORESTRY("forestry");

	private final String label;

	LpjgType(String label) {
		this.label = label;
	}

	/** The value written in Services.csv, and the suitability folder name. */
	public String label() {
		return label;
	}

	/** The type with this exact label, or null if there is none. */
	public static LpjgType fromLabel(String label) {
		for (LpjgType type : values()) {
			if (type.label.equals(label)) {
				return type;
			}
		}
		return null;
	}

	@Override
	public String toString() {
		return label;
	}
}
