package de.cesr.crafty.react;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <b>How the values are written (37a).</b> A global run hands over hundreds of entries to millions of cells,
 * so the order matters. The cells are grouped by unit once, when react starts. Each handover then goes unit by
 * unit: a unit's values are gathered once, then written into each of its cells, one cell at a time, so each
 * cell's values are visited once rather than once per entry. The values written are the same as going entry by
 * entry over every cell.
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

	/** Where in a cell an entry goes: its capitals, or one of its four production costs. */
	enum Target {
		CAPITALS, NFERT, IRRIGATION, INTENSITY, STOCKING;

		IndexedValues of(Cell cell) {
			return switch (this) {
			case CAPITALS -> cell.getCapitals();
			case NFERT -> cell.getNfertCosts();
			case IRRIGATION -> cell.getIrrigationCosts();
			case INTENSITY -> cell.getIntensityCosts();
			case STOCKING -> cell.getStockingCosts();
			};
		}
	}

	private static final Target[] TARGETS = Target.values();

	/**
	 * One entry handed to every cell: its name (a capital, or the AFT a cost is for), where it goes, its value
	 * for each unit, and the factor each value is multiplied by (the production level for a cost, 1 for a
	 * {@code _suit}).
	 */
	record Column(String name, Target target, double[] byUnit, double factor) {
	}

	/**
	 * The model's cells, grouped by decision unit: the cells of unit u are {@code cellsByUnit[firstCellOfUnit[u]]}
	 * up to, but not including, {@code cellsByUnit[firstCellOfUnit[u + 1]]}.
	 */
	private final Cell[] cellsByUnit;
	private final int[] firstCellOfUnit;
	private final ReactRunContext.ProductionLevelSource levels;

	private ReactHandover(Cell[] cellsByUnit, int[] firstCellOfUnit, ReactRunContext.ProductionLevelSource levels) {
		this.cellsByUnit = cellsByUnit;
		this.firstCellOfUnit = firstCellOfUnit;
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
		Cell[] cellArray = new Cell[cells.size()];
		int[] unitOfCell = new int[cells.size()];
		int i = 0;
		for (Map.Entry<String, Cell> entry : cells.entrySet()) {
			int unit = units.unitOf(entry.getKey());
			if (unit < 0) {
				throw new ReactInputException("CRAFTY-react cannot hand its values to cell " + entry.getKey()
						+ ": the cell key does not list it");
			}
			cellArray[i] = entry.getValue();
			unitOfCell[i] = unit;
			i++;
		}
		// Group the cells by unit (a counting sort): count each unit's cells, turn the counts into where each
		// unit starts, then place each cell. Within a unit the cells keep the order they came in.
		int unitCount = units.size();
		int[] firstCellOfUnit = new int[unitCount + 1];
		for (int unit : unitOfCell) {
			firstCellOfUnit[unit + 1]++;
		}
		for (int unit = 0; unit < unitCount; unit++) {
			firstCellOfUnit[unit + 1] += firstCellOfUnit[unit];
		}
		Cell[] cellsByUnit = new Cell[cellArray.length];
		int[] next = Arrays.copyOf(firstCellOfUnit, unitCount);
		for (int cell = 0; cell < cellArray.length; cell++) {
			cellsByUnit[next[unitOfCell[cell]]++] = cellArray[cell];
		}
		return new ReactHandover(cellsByUnit, firstCellOfUnit, levels);
	}

	/** Writes the year's values into every cell. */
	Counts write(Collection<CropManagement> crops, Collection<PastureManagement> pasture,
			Collection<ForestryManagement> forestry) {
		List<Column> columns = new ArrayList<>();
		int suitCapitals = 0;
		int costColumns = 0;
		Map<String, Double> scaled = new LinkedHashMap<>();
		for (CropManagement m : crops) {
			columns.add(suit(m.label(), m.yield()));
			suitCapitals++;
			if (m.hasNitrogenCost()) {
				columns.add(cost(m.aft(), m.nitrogenCost(), Target.NFERT, scaled));
				costColumns++;
			}
			if (m.hasIrrigationCost()) {
				columns.add(cost(m.aft(), m.irrigationCost(), Target.IRRIGATION, scaled));
				costColumns++;
			}
			if (m.hasIntensityCost()) {
				columns.add(cost(m.aft(), m.intensityCost(), Target.INTENSITY, scaled));
				costColumns++;
			}
		}
		for (PastureManagement m : pasture) {
			columns.add(suit(m.label(), m.production()));
			suitCapitals++;
			if (m.hasIntensityCost()) {
				columns.add(cost(m.aft(), m.intensityCost(), Target.INTENSITY, scaled));
				costColumns++;
			}
			if (m.decidesStocking()) {
				columns.add(cost(m.aft(), m.stockingCost(), Target.STOCKING, scaled));
				costColumns++;
			}
		}
		for (ForestryManagement m : forestry) {
			columns.add(suit(m.label(), m.yield()));
			suitCapitals++;
			columns.add(cost(m.aft(), m.cost(), Target.INTENSITY, scaled));
			costColumns++;
		}
		writeColumns(columns);
		return new Counts(suitCapitals, costColumns, cellsByUnit.length, scaled);
	}

	/** An AFT's {@code _suit} capital: the value of the cell's unit, not scaled. */
	private static Column suit(String label, double[] byUnit) {
		return new Column(label + ReactStartupCheck.SUIT, Target.CAPITALS, byUnit, 1);
	}

	/**
	 * One of an AFT's costs: the value of the cell's unit times the AFT's production level for its service, as
	 * the model holds it now. A level other than 1 is noted in {@code scaled}.
	 */
	private Column cost(AftReactParameters aft, double[] byUnit, Target target, Map<String, Double> scaled) {
		double level = levels.level(aft.label(), aft.service());
		if (level != 1) {
			scaled.put(aft.label(), level);
		}
		return new Column(aft.label(), target, byUnit, level);
	}

	/**
	 * Puts each column into every cell: the value of the cell's unit times the column's factor, unit by unit.
	 *
	 * Each cell is written in column order, so a name new to the model takes its place in the cells' shared index
	 * (see {@link IndexedValues}) in column order, as it did when the handover went column by column. In the first
	 * year the first cell makes room for the new names one at a time; every cell after it makes room for all of
	 * them at once.
	 */
	void writeColumns(List<Column> columns) {
		if (columns.isEmpty()) {
			return;
		}
		Prepared prepared = new Prepared(columns);
		double[] values = new double[columns.size()];
		IndexedValues[] maps = new IndexedValues[TARGETS.length];
		for (int unit = 0; unit < firstCellOfUnit.length - 1; unit++) {
			int from = firstCellOfUnit[unit];
			int to = firstCellOfUnit[unit + 1];
			if (from == to) {
				continue;
			}
			prepared.gather(unit, values);
			for (int cell = from; cell < to; cell++) {
				prepared.write(cellsByUnit[cell], values, maps);
			}
		}
	}

	/** The columns laid out as plain arrays, for the loop over cells. */
	private static final class Prepared {
		private final String[] names;
		private final int[] targets;
		private final double[][] byUnit;
		private final double[] factors;

		Prepared(List<Column> columns) {
			int n = columns.size();
			names = new String[n];
			targets = new int[n];
			byUnit = new double[n][];
			factors = new double[n];
			for (int k = 0; k < n; k++) {
				Column column = columns.get(k);
				names[k] = column.name();
				targets[k] = column.target().ordinal();
				byUnit[k] = column.byUnit();
				factors[k] = column.factor();
			}
		}

		/** Fills {@code values} with a unit's value of every column, times the column's factor. */
		void gather(int unit, double[] values) {
			for (int k = 0; k < names.length; k++) {
				values[k] = byUnit[k][unit] * factors[k];
			}
		}

		/**
		 * Puts the values into one cell, in column order. {@code maps} is room for the cell's five sets of values,
		 * so that each is looked up once per cell.
		 */
		void write(Cell cell, double[] values, IndexedValues[] maps) {
			for (Target target : TARGETS) {
				maps[target.ordinal()] = target.of(cell);
			}
			for (int k = 0; k < names.length; k++) {
				maps[targets[k]].put(names[k], values[k]);
			}
		}
	}
}
