package de.cesr.crafty.landsymm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which PLUM country each CRAFTY region is, from the GIS file's {@code Region_Code} and {@code PLUM_country} columns.
 * The two must match one to one: CRAFTY's regions are PLUM's country groups.
 *
 * The GIS file has one row per cell (2.2 million for the global set-up), so it is read one line at a time and only
 * the distinct pairs are kept. Rows with no Region_Code (cells outside every region) are skipped. Every problem is
 * reported together.
 */
public final class RegionCountryKey {

	static final String REGION_COLUMN = "Region_Code";
	static final String COUNTRY_COLUMN = "PLUM_country";
	private static final int MAX_LISTED = 20;

	private final Path file;
	private final Map<String, String> countryByRegion;
	private final Map<String, String> regionByCountry;

	private RegionCountryKey(Path file, Map<String, String> countryByRegion, Map<String, String> regionByCountry) {
		this.file = file;
		this.countryByRegion = Collections.unmodifiableMap(countryByRegion);
		this.regionByCountry = Collections.unmodifiableMap(regionByCountry);
	}

	/** Reads the GIS file, stopping with every problem found. */
	public static RegionCountryKey read(Path gisFile) {
		Map<String, Set<String>> countriesByRegion = new TreeMap<>();
		Set<String> blankCountry = new TreeSet<>();
		CsvReader.forEachRow(gisFile, List.of(REGION_COLUMN, COUNTRY_COLUMN), (line, values) -> {
			String region = values[0];
			if (region.isEmpty()) {
				return;
			}
			String country = values[1];
			if (country.isEmpty()) {
				blankCountry.add(region);
				return;
			}
			countriesByRegion.computeIfAbsent(region, r -> new TreeSet<>()).add(country);
		});

		List<String> problems = new ArrayList<>();
		String name = gisFile.getFileName().toString();
		if (!blankCountry.isEmpty()) {
			problems.add(name + ": region(s) with a blank " + COUNTRY_COLUMN + " on some cells: " + listed(blankCountry));
		}
		Set<String> unquotable = new TreeSet<>();
		countriesByRegion.values().forEach(countries -> countries.stream()
				.filter(c -> c.indexOf(',') >= 0 || c.indexOf('"') >= 0).forEach(unquotable::add));
		if (!unquotable.isEmpty()) {
			problems.add(name + ": " + COUNTRY_COLUMN + " name(s) with a comma or a double quote, which production.csv "
					+ "can't carry (it is unquoted, and PLUM splits on commas): " + listed(unquotable));
		}
		Map<String, String> countryByRegion = new TreeMap<>();
		Map<String, Set<String>> regionsByCountry = new TreeMap<>();
		countriesByRegion.forEach((region, countries) -> {
			if (countries.size() > 1) {
				problems.add(name + ": region " + region + " has more than one " + COUNTRY_COLUMN + ": " + countries);
			}
			String country = countries.iterator().next();
			countryByRegion.put(region, country);
			regionsByCountry.computeIfAbsent(country, c -> new TreeSet<>()).add(region);
		});
		Map<String, String> regionByCountry = new TreeMap<>();
		regionsByCountry.forEach((country, regions) -> {
			if (regions.size() > 1) {
				problems.add(name + ": PLUM country " + country + " is more than one region: " + regions);
			}
			regionByCountry.put(country, regions.iterator().next());
		});
		if (countryByRegion.isEmpty() && problems.isEmpty()) {
			problems.add(name + ": no cell has a " + REGION_COLUMN);
		}

		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}
		return new RegionCountryKey(gisFile, countryByRegion, regionByCountry);
	}

	private static String listed(Set<String> names) {
		List<String> first = names.stream().limit(MAX_LISTED).toList();
		return first + (names.size() > MAX_LISTED ? " and " + (names.size() - MAX_LISTED) + " more" : "");
	}

	/** The GIS file the key was read from. */
	public Path file() {
		return file;
	}

	/** The region's PLUM country, or null if the region isn't in the key. */
	public String country(String region) {
		return countryByRegion.get(region);
	}

	/** The PLUM country's region, or null if no region is that country. */
	public String region(String country) {
		return regionByCountry.get(country);
	}

	/** Every region, in name order. */
	public Set<String> regions() {
		return countryByRegion.keySet();
	}

	/** Every PLUM country, in name order. */
	public Set<String> countries() {
		return regionByCountry.keySet();
	}
}
