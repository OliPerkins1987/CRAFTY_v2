package de.cesr.crafty.react;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.react.data.ReactInputException;
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
 * Each cell's unit is worked out once, when react starts. The cells don't change during a run.
 */
final class ReactHandover {

	/** How much was handed over: the {@code _suit} capitals and cost columns (one per AFT each), and the cells. */
	record Counts(int suitCapitals, int costColumns, int cells) {
	}

	private final Cell[] cells;
	private final int[] unitOfCell;

	private ReactHandover(Cell[] cells, int[] unitOfCell) {
		this.cells = cells;
		this.unitOfCell = unitOfCell;
	}

	/**
	 * The handover for the model's cells.
	 *
	 * @param cells the model's cells, by id ({@code "x,y"})
	 * @throws ReactInputException if the cell key does not list a cell (the startup checks make sure it does)
	 */
	static ReactHandover build(Map<String, Cell> cells, DecisionUnits units) {
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
		return new ReactHandover(cellList.toArray(new Cell[0]), unitList.stream().mapToInt(Integer::intValue).toArray());
	}

	/** Writes the year's values into every cell. */
	Counts write(Collection<CropManagement> crops, Collection<PastureManagement> pasture,
			Collection<ForestryManagement> forestry) {
		int suitCapitals = 0;
		int costColumns = 0;
		for (CropManagement m : crops) {
			put(m.label() + ReactStartupCheck.SUIT, m.yield(), Cell::getCapitals);
			suitCapitals++;
			if (m.hasNitrogenCost()) {
				put(m.label(), m.nitrogenCost(), Cell::getNfertCosts);
				costColumns++;
			}
			if (m.hasIrrigationCost()) {
				put(m.label(), m.irrigationCost(), Cell::getIrrigationCosts);
				costColumns++;
			}
			if (m.hasIntensityCost()) {
				put(m.label(), m.intensityCost(), Cell::getIntensityCosts);
				costColumns++;
			}
		}
		for (PastureManagement m : pasture) {
			put(m.label() + ReactStartupCheck.SUIT, m.production(), Cell::getCapitals);
			suitCapitals++;
			if (m.hasIntensityCost()) {
				put(m.label(), m.intensityCost(), Cell::getIntensityCosts);
				costColumns++;
			}
			if (m.decidesStocking()) {
				put(m.label(), m.stockingCost(), Cell::getStockingCosts);
				costColumns++;
			}
		}
		for (ForestryManagement m : forestry) {
			put(m.label() + ReactStartupCheck.SUIT, m.yield(), Cell::getCapitals);
			suitCapitals++;
			put(m.label(), m.cost(), Cell::getIntensityCosts);
			costColumns++;
		}
		return new Counts(suitCapitals, costColumns, cells.length);
	}

	/** Puts one entry into one of every cell's maps: the value of the cell's unit. */
	private void put(String key, double[] byUnit, Function<Cell, Map<String, Double>> map) {
		for (int i = 0; i < cells.length; i++) {
			map.apply(cells[i]).put(key, byUnit[unitOfCell[i]]);
		}
	}
}
