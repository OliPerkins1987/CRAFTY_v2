package de.cesr.crafty.landsymm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The countries in a year of PLUM's domestic.txt must be exactly CRAFTY's (the region key's), in both directions
 * (48 PLAN, D3). PLUM's own reader skips CRAFTY countries it knows but has no agent for, such as one in
 * countries_excluded.csv, so this is where they are caught.
 */
final class PlumCountryCheck {

	private static final int MAX_LISTED = 20;

	private PlumCountryCheck() {
	}

	/** Where PLUM's countries for the year aren't the key's; empty if they are the same. */
	static List<String> problems(PlumYearOutputs rows, RegionCountryKey key) {
		Set<String> onlyPlum = new TreeSet<>(rows.countries());
		onlyPlum.removeAll(key.countries());
		Set<String> onlyCrafty = new TreeSet<>(key.countries());
		onlyCrafty.removeAll(rows.countries());
		String where = "PLUM's " + PlumYearOutputs.DOMESTIC_FILE + " for " + rows.year() + " (" + rows.folder() + ")";
		List<String> problems = new ArrayList<>();
		if (!onlyPlum.isEmpty()) {
			problems.add(where + " has countries CRAFTY's region key doesn't: " + listed(onlyPlum));
		}
		if (!onlyCrafty.isEmpty()) {
			problems.add(where + " has no rows for CRAFTY's countries " + listed(onlyCrafty)
					+ ". PLUM skips countries it has no agent for, such as those in countries_excluded.csv");
		}
		return problems;
	}

	private static String listed(Set<String> names) {
		List<String> first = names.stream().limit(MAX_LISTED).toList();
		return first + (names.size() > MAX_LISTED ? " and " + (names.size() - MAX_LISTED) + " more" : "");
	}
}
