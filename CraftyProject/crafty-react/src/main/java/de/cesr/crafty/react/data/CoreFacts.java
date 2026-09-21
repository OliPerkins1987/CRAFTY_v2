package de.cesr.crafty.react.data;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.crafty.Service;
import de.cesr.crafty.core.dataLoader.ProjectLoader;
import de.cesr.crafty.core.dataLoader.afts.AFTsLoader;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;
import de.cesr.crafty.core.modelRunner.ModelRunner;
import de.cesr.crafty.core.updaters.ProductionCostUpdater;
import de.cesr.crafty.core.updaters.Timestep;

/**
 * Builds a {@link ReactRunContext} from crafty-core. This is the only class in react that reads core's
 * static state, so a change in core touches one file.
 *
 * It is called when react's step is created, which crafty-core does at the end of
 * {@code ModelRunner.start()}. By then the services, the AFT metadata, the cells and the spatial cost
 * files have all been loaded, and the scenario and years are set.
 */
public final class CoreFacts {

	/** The service core charges a stocking cost for; core hard-codes this name. */
	private static final String PASTURE_SERVICE = "Pasture";

	/** Core's name for each element's spatial cost files. */
	private static final Map<String, ReactElement> COST_TYPES = Map.of(
			ProductionCostUpdater.NFERT_COSTS, ReactElement.FERTILISER,
			ProductionCostUpdater.IRRIGATION_COSTS, ReactElement.IRRIGATION,
			ProductionCostUpdater.INTENSITY_COSTS, ReactElement.OTHER_INTENSITY,
			ProductionCostUpdater.STOCKING_COSTS, ReactElement.STOCKING);

	private CoreFacts() {
	}

	/** Everything react needs from core, as it stands now. */
	public static ReactRunContext fromCore() {
		Map<String, ReactRunContext.AftBaseline> afts = new LinkedHashMap<>();
		AFTsLoader.getAftHash().forEach((label, aft) -> afts.put(label,
				new ReactRunContext.AftBaseline(aft.getNfertRate(), aft.getOtherIntensity(), aft.isIrrigated(),
						aft.getProductivityLevel().getOrDefault(PASTURE_SERVICE, 0.0) > 0)));

		Map<String, String> cellRegions = new LinkedHashMap<>();
		CellsLoader.hashCell.forEach((cellId, cell) -> cellRegions.put(cellId, cell.getCurrentRegion()));

		return new ReactRunContext(Path.of(ConfigLoader.config.project_path), ProjectLoader.getScenario(),
				Timestep.getStartYear(), Timestep.getEndtYear(), ProjectLoader.getServiceMetadata(), afts,
				new LinkedHashSet<>(ServiceSet.getServicesList()), cellRegions,
				new LinkedHashSet<>(CellsLoader.regions.keySet()), reactiveElements(), costFilesByYear(),
				CoreFacts::price);
	}

	/** Which elements are switched on in core's config.yaml. */
	static Set<ReactElement> reactiveElements() {
		Set<ReactElement> elements = EnumSet.noneOf(ReactElement.class);
		if (ConfigLoader.isReactiveFertilizer()) {
			elements.add(ReactElement.FERTILISER);
		}
		if (ConfigLoader.isReactiveIrrigation()) {
			elements.add(ReactElement.IRRIGATION);
		}
		if (ConfigLoader.isReactiveOtherIntensity()) {
			elements.add(ReactElement.OTHER_INTENSITY);
		}
		if (ConfigLoader.isReactiveStocking()) {
			elements.add(ReactElement.STOCKING);
		}
		return elements;
	}

	/**
	 * Which elements' spatial cost files core found, for each year of the run. React writes its costs
	 * into those files, so a missing one means the costs would never reach the model.
	 */
	static Map<Integer, Set<ReactElement>> costFilesByYear() {
		Map<Integer, Set<ReactElement>> found = new LinkedHashMap<>();
		for (int year = Timestep.getStartYear(); year <= Timestep.getEndtYear(); year++) {
			Set<ReactElement> elements = EnumSet.noneOf(ReactElement.class);
			if (ModelRunner.productionCostUpdater != null) {
				ModelRunner.productionCostUpdater.getSpatialCostPaths(year).forEach((costType, path) -> {
					ReactElement element = COST_TYPES.get(costType);
					if (element != null && path != null) {
						elements.add(element);
					}
				});
			}
			found.put(year, elements);
		}
		return found;
	}

	/**
	 * What a service is worth in a region in a year: its utility weight, which is the price signal
	 * {@code Competitiveness} uses. Phases 3 and 4 use this; nothing in phase 1 does.
	 */
	static double price(String service, String region, int year) {
		var craftyRegion = CellsLoader.regions.get(region);
		if (craftyRegion == null) {
			throw new ReactInputException("No such region in the model: " + region);
		}
		Service craftyService = craftyRegion.getServicesHash().get(service);
		if (craftyService == null) {
			throw new ReactInputException("Region " + region + " has no service " + service);
		}
		Double weight = craftyService.getWeights().get(year);
		if (weight == null) {
			throw new ReactInputException("Service " + service + " has no weight for year " + year + " in region "
					+ region);
		}
		return weight;
	}
}
