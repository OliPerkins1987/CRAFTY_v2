package de.cesr.crafty.landsymm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;
import de.cesr.crafty.core.utils.general.DeterministicAggregation;

/**
 * Each region's supply of each market service, summed from its cells exactly as core sums it
 * (RegionalModelRunner.computeRegionsSupply: {@code Cell.currentSupply}, over the cells in DeterministicAggregation's
 * order), with the gross production beside it. Supply is core's own rule: production x cell area, less the owner's
 * self-consumption of market goods. Gross leaves the self-consumption in, and is only reported.
 *
 * The pass can first recalculate every cell's productivity ({@code Cell.calculateCurrentProductivity}) for the
 * current owners, which in sequential mode is the land use after this year's giving up and competition. It doesn't
 * use {@code RegionalModelRunner.regionalSupply()} for that: as well as summing, that adds a year to every cell's
 * owner-life counter, which SupplyUpdater has already done this year and competition reads. The pass also leaves
 * core's regional supply map alone (ServicesUpdater reads it at the start of the next year). It does overwrite each
 * cell's stored production with the current owner's, the values next year's SupplyUpdater recomputes before
 * anything reads them.
 */
final class SupplyPass {

	/** The relative tolerance of the drift check. The coupler's sum and core's are the same sum in the same order. */
	static final double DRIFT_TOLERANCE = 1e-9;

	private static final int MAX_LISTED = 20;

	/** One region's supply (what PLUM is sent, before the unit factor) and gross production, by market service. */
	record RegionSupply(Map<String, Double> supply, Map<String, Double> gross) {
	}

	private SupplyPass() {
	}

	/**
	 * Sums each region's supply and gross production of the market services.
	 *
	 * @param cellsByRegion  each region's cells
	 * @param marketServices the services to sum, which must all be in CRAFTY's service list
	 * @param recalculate    whether to recalculate every cell's productivity first
	 * @param useCellArea    CRAFTY's use_cell_area, for the gross production
	 * @return by region, in name order
	 */
	static Map<String, RegionSupply> sum(Map<String, ? extends Collection<Cell>> cellsByRegion,
			List<String> marketServices, boolean recalculate, boolean useCellArea) {
		List<String> craftyServices = ServiceSet.getServicesList();
		int n = marketServices.size();
		int[] index = new int[n];
		List<String> unknown = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			index[i] = craftyServices.indexOf(marketServices.get(i));
			if (index[i] < 0) {
				unknown.add(marketServices.get(i));
			}
		}
		if (!unknown.isEmpty()) {
			throw new CouplingInputException("Market service(s) " + unknown + " from Services.csv aren't in CRAFTY's "
					+ "service list " + craftyServices);
		}

		Map<String, RegionSupply> result = new TreeMap<>();
		new TreeMap<>(cellsByRegion).forEach((region, cells) -> {
			if (recalculate) {
				cells.parallelStream().forEach(Cell::calculateCurrentProductivity);
			}
			double[] supply = new double[n];
			double[] gross = new double[n];
			for (Cell c : DeterministicAggregation.cellsInStableOrder(cells)) {
				double area = useCellArea ? c.getArea() : 1.0;
				double[] production = c.getCurrentProd();
				for (int i = 0; i < n; i++) {
					supply[i] += c.currentSupply(index[i]);
					gross[i] += production[index[i]] * area;
				}
			}
			Map<String, Double> supplyByService = new LinkedHashMap<>();
			Map<String, Double> grossByService = new LinkedHashMap<>();
			for (int i = 0; i < n; i++) {
				supplyByService.put(marketServices.get(i), supply[i]);
				grossByService.put(marketServices.get(i), gross[i]);
			}
			result.put(region, new RegionSupply(supplyByService, grossByService));
		});
		return result;
	}

	/** Just the supply of each region, as {@link ProductionFile#aggregate} takes it. */
	static Map<String, Map<String, Double>> supplyOnly(Map<String, RegionSupply> byRegion) {
		Map<String, Map<String, Double>> supply = new TreeMap<>();
		byRegion.forEach((region, s) -> supply.put(region, s.supply()));
		return supply;
	}

	/**
	 * Where the pass's supply isn't core's own regional supply, for a year in which nothing has changed since core
	 * summed it. Any difference means the coupler's copy of core's supply rule has drifted from core's. Reads core's
	 * map without changing it.
	 *
	 * @return the problems, empty if every region and service matches
	 */
	static List<String> driftProblems(Map<String, RegionSupply> pass, Map<String, Map<String, Double>> coreSupply) {
		List<String> pairs = new ArrayList<>();
		pass.forEach((region, s) -> {
			Map<String, Double> core = coreSupply.get(region);
			s.supply().forEach((service, value) -> {
				Double coreValue = core == null ? null : core.get(service);
				if (coreValue == null || !same(value, coreValue)) {
					pairs.add(region + ", " + service + ": coupler " + value + ", CRAFTY " + coreValue);
				}
			});
		});
		if (pairs.isEmpty()) {
			return List.of();
		}
		List<String> problems = new ArrayList<>();
		problems.add("The coupler's supply sum differs from CRAFTY's own in " + pairs.size() + " region x service "
				+ "pair(s), so the coupler's copy of core's supply rule has drifted from core's:");
		pairs.stream().limit(MAX_LISTED).forEach(problems::add);
		if (pairs.size() > MAX_LISTED) {
			problems.add("... and " + (pairs.size() - MAX_LISTED) + " more");
		}
		return problems;
	}

	static boolean same(double a, double b) {
		return a == b || Math.abs(a - b) <= DRIFT_TOLERANCE * Math.max(Math.abs(a), Math.abs(b));
	}
}
