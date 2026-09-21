package de.cesr.crafty.react.data;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * What react needs to know from crafty-core, gathered in one place.
 *
 * React's startup checks take this record instead of reading core's static state themselves, so they
 * can be tested with a record built by hand. Patch 27c fills it from core (AFTsLoader, ServiceSet,
 * CellsLoader, Timestep, ConfigLoader, ProductionCostUpdater) when react starts.
 *
 * @param projectPath     the project folder ({@code project_path})
 * @param scenario        the run's scenario
 * @param firstYear       the first model year
 * @param lastYear        the last model year
 * @param servicesFile    core's {@code Services.csv}
 * @param afts            every AFT core knows (masks included), by label, with its baselines
 * @param services        core's service names
 * @param cellIds         every CRAFTY cell, as {@code "x,y"}
 * @param reactive        the elements switched on in config.yaml
 * @param costFilesByYear for each year, the elements whose spatial cost file core found
 */
public record ReactRunContext(Path projectPath, String scenario, int firstYear, int lastYear, Path servicesFile,
		Map<String, AftBaseline> afts, Set<String> services, Set<String> cellIds, Set<ReactElement> reactive,
		Map<Integer, Set<ReactElement>> costFilesByYear) {

	/**
	 * An AFT's baselines, from core's {@code AFTsMetaData.csv}: what it does when nothing is reactive.
	 *
	 * @param nfertRate       {@code Nfert_rate}, kg N/ha: the N baseline
	 * @param otherIntensity  {@code Other_intensity} (core uses 1.0 when the cell is blank): the crops
	 *                        other-intensity baseline, or the pasture husbandry when other intensity is
	 *                        not reactive
	 * @param irrigated       {@code Irrigated = 1}
	 * @param producesPasture whether the AFT produces Pasture, which is core's test for charging it a
	 *                        stocking cost
	 */
	public record AftBaseline(double nfertRate, double otherIntensity, boolean irrigated, boolean producesPasture) {
	}

	/** Whether an element is switched on. */
	public boolean isReactive(ReactElement element) {
		return reactive.contains(element);
	}

	/** Whether any element is switched on. */
	public boolean anyReactive() {
		return !reactive.isEmpty();
	}
}
