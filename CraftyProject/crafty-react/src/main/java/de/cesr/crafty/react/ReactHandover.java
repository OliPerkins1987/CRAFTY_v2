package de.cesr.crafty.react;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.crafty.IndexedValues;
import de.cesr.crafty.react.data.AftReactParameters;
import de.cesr.crafty.react.data.ReactInputException;
import de.cesr.crafty.react.data.ReactRunContext;
import de.cesr.crafty.react.data.ReactStartupCheck;
import de.cesr.crafty.react.decisions.CropManagement;
import de.cesr.crafty.react.decisions.DecisionUnits;
import de.cesr.crafty.react.decisions.ForestryManagement;
import de.cesr.crafty.react.decisions.PastureManagement;

/**
 * Hands react's decisions to the model: each cell takes its decision unit's values (its pixel, in its own
 * region), straight into the cell's capitals and cost maps (phase 5 plan, §3.1).
 * <ul>
 * <li>Crops AFT: the yield as its {@code <AFT>_suit} capital, and each cost that was worked out: N, water
 * (irrigated AFTs), other intensity.</li>
 * <li>Pasture AFT: the production as its {@code <AFT>_suit} capital, and each cost that was worked out:
 * husbandry (as the intensity cost), stocking.</li>
 * <li>Forestry AFT: the yield as its {@code <AFT>_suit} capital, and the rotation cost as its intensity cost.
 * Forestry has one switch, so both are always worked out, whether or not other intensity is on.</li>
 * </ul>
 * Only the reactive AFTs' entries, and only the costs of elements that are on, are written. Everything else
 * stays as core loaded it that year. A cost is worked out exactly when its element is on (see
 * {@link CropManagement}, {@link PastureManagement} and {@link ForestryManagement}), so that is what is checked
 * here. An AFT belongs to one land use, so no two stages write the same AFT's cost.
 *
 * <b>Costs are scaled by the production level (35a).</b> React works out its costs per ha of the land an AFT
 * uses for its service, and the model's production of that service is the AFT's production level times its
 * {@code _suit}. So each cost is multiplied by the AFT's production level for its service, as the model holds
 * it at the handover: an AFT at 0.75 pays 0.75 of the cost. The {@code _suit} is not scaled: a yield per ha is
 * the same however much of its land the AFT uses for the service. React's decisions are not affected, because
 * the revenue and the cost of that land scale together.
 *
 * Each cell's unit is worked out once, when react starts. The cells don't change during a run.
 */
final class ReactHandover {

	/**
	 * How much was handed over: the {@code _suit} capitals and cost columns (one per AFT each), the cells, and
	 * the AFTs whose costs were scaled by a production level other than 1, with that level, in the order handed
	 * over.
	 */
	record Counts(int suitCapitals, int costColumns, int cells, Map<String, Double> scaled) {

		/** With no AFT's costs scaled: every production level was 1. */
		Counts(int suitCapitals, int costColumns, int cells) {
			this(suitCapitals, costColumns, cells, Map.of());
		}
	}

	private final Cell[] cells;
	private final int[] unitOfCell;
	private final ReactRunContext.ProductionLevelSource levels;

	private ReactHandover(Cell[] cells, int[] unitOfCell, ReactRunContext.ProductionLevelSource levels) {
		this.cells = cells;
		this.unitOfCell = unitOfCell;
		this.levels = levels;
	}

	/**
	 * The handover for the model's cells.
	 *
	 * @param cells  the model's cells, by id ({@code "x,y"})
	 * @param levels each AFT's production level for its service, asked for at every handover
	 * @throws ReactInputException if the cell key does not list a cell (the startup checks make sure it does)
	 */
	static ReactHandover build(Map<String, Cell> cells, DecisionUnits units,
			ReactRunContext.ProductionLevelSource levels) {
		List<Cell> cellList = new ArrayList<>(cells.size());
		List<Integer> unitList = new ArrayList<>(cells.size());
		for (Map.Entry<String, Cell> entry : cells.entrySet()) {
			int unit = units.unitOf(entry.getKey());
			if (unit < 0) {
				throw new ReactInputException("CRAFTY-react cannot hand its values to cell " + entry.getKey()
						+ ": the cell key does not list it");
			}
			cellList.add(entry.getValue());
			unitList.add(unit);
		}
		return new ReactHandover(cellList.toArray(new Cell[0]), unitList.stream().mapToInt(Integer::intValue).toArray(),
				levels);
	}

	/** Writes the year's values into every cell. */
	Counts write(Collection<CropManagement> crops, Collection<PastureManagement> pasture,
			Collection<ForestryManagement> forestry) {
		int suitCapitals = 0;
		int costColumns = 0;
		Map<String, Double> scaled = new LinkedHashMap<>();
		for (CropManagement m : crops) {
			put(m.label() + ReactStartupCheck.SUIT, m.yield(), Cell::getCapitals);
			suitCapitals++;
			if (m.hasNitrogenCost()) {
				putCost(m.aft(), m.nitrogenCost(), Cell::getNfertCosts, scaled);
				costColumns++;
			}
			if (m.hasIrrigationCost()) {
				putCost(m.aft(), m.irrigationCost(), Cell::getIrrigationCosts, scaled);
				costColumns++;
			}
			if (m.hasIntensityCost()) {
				putCost(m.aft(), m.intensityCost(), Cell::getIntensityCosts, scaled);
				costColumns++;
			}
		}
		for (PastureManagement m : pasture) {
			put(m.label() + ReactStartupCheck.SUIT, m.production(), Cell::getCapitals);
			suitCapitals++;
			if (m.hasIntensityCost()) {
				putCost(m.aft(), m.intensityCost(), Cell::getIntensityCosts, scaled);
				costColumns++;
			}
			if (m.decidesStocking()) {
				putCost(m.aft(), m.stockingCost(), Cell::getStockingCosts, scaled);
				costColumns++;
			}
		}
		for (ForestryManagement m : forestry) {
			put(m.label() + ReactStartupCheck.SUIT, m.yield(), Cell::getCapitals);
			suitCapitals++;
			putCost(m.aft(), m.cost(), Cell::getIntensityCosts, scaled);
			costColumns++;
		}
		return new Counts(suitCapitals, costColumns, cells.length, scaled);
	}

	/** Puts one entry into one of every cell's capitals or costs: the value of the cell's unit. */
	private void put(String key, double[] byUnit, Function<Cell, IndexedValues> map) {
		for (int i = 0; i < cells.length; i++) {
			map.apply(cells[i]).put(key, byUnit[unitOfCell[i]]);
		}
	}

	/**
	 * Puts one of an AFT's costs into every cell: the value of the cell's unit times the AFT's production level
	 * for its service, as the model holds it now. A level other than 1 is noted in {@code scaled}.
	 */
	private void putCost(AftReactParameters aft, double[] byUnit, Function<Cell, IndexedValues> map,
			Map<String, Double> scaled) {
		double level = levels.level(aft.label(), aft.service());
		if (level != 1) {
			scaled.put(aft.label(), level);
		}
		for (int i = 0; i < cells.length; i++) {
			map.apply(cells[i]).put(aft.label(), byUnit[unitOfCell[i]] * level);
		}
	}
}
