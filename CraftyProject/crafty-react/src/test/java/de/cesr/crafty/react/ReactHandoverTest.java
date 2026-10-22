package de.cesr.crafty.react;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.react.ReactHandover.Column;
import de.cesr.crafty.react.ReactHandover.Target;
import de.cesr.crafty.react.data.CellKey;
import de.cesr.crafty.react.data.ReactConfig;
import de.cesr.crafty.react.data.ReactElement;
import de.cesr.crafty.react.data.ReactInputException;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactStartupCheck;
import de.cesr.crafty.react.data.ReactToyData;
import de.cesr.crafty.react.data.ReactYearData;
import de.cesr.crafty.react.decisions.CropDecisions;
import de.cesr.crafty.react.decisions.CropManagement;
import de.cesr.crafty.react.decisions.DecisionUnits;
import de.cesr.crafty.react.decisions.ForestryDecisions;
import de.cesr.crafty.react.decisions.ForestryManagement;
import de.cesr.crafty.react.decisions.PastureDecisions;
import de.cesr.crafty.react.decisions.PastureManagement;
import de.cesr.crafty.react.decisions.YearPrices;
import de.cesr.crafty.react.science.CropSurfaces;

/**
 * The handover on the toy project: cells (1,1) North and (1,2) South in pixel A, and (2,1) North in pixel B.
 * Pixel A straddles a border, so its two cells take the values of two different decision units.
 *
 * The tests at the end (37a) write columns straight into larger grids of cells, each with its own cell key, to
 * check the grouping and the loop by unit against a plain loop over every cell.
 */
class ReactHandoverTest {

	@TempDir
	Path dir;

	private ReactInputs inputs;
	private DecisionUnits units;
	private CropDecisions crops;
	private PastureDecisions pasture;
	private ForestryDecisions forestry;
	private final Map<String, Cell> cells = new LinkedHashMap<>();

	/** Checks the toy project and decides 2020, with a lower price in the South so that the units differ. */
	private void decide(ReactToyData.Context context) {
		decide(context, false);
	}

	/** The same with the toy forestry AFTs and files: use with {@code context.withForestry()}. */
	private void decideWithForestry(ReactToyData.Context context) {
		decide(context, true);
	}

	private void decide(ReactToyData.Context context, boolean forestryFiles) {
		ReactToyData.project(dir);
		if (forestryFiles) {
			ReactToyData.forestry(dir);
		}
		inputs = ReactInputs.create(ReactConfig.defaults(),
				context.prices((service, region, year) -> region.equals("South") ? 150 : 300).build());
		units = DecisionUnits.build(inputs.checked().cellKey(), inputs.context().regions());
		crops = CropDecisions.create(inputs, units);
		pasture = PastureDecisions.create(inputs, units);
		forestry = ForestryDecisions.create(inputs, units);
		ReactYearData data = inputs.forYear(2020);
		YearPrices prices = YearPrices.forYear(2020, crops.servicesNeedingPrices(), units, inputs.context().prices());
		if (!crops.managements().isEmpty()) {
			crops.decide(data, CropSurfaces.fit(data), prices);
		}
		YearPrices pasturePrices = YearPrices.forYear(2020, pasture.servicesNeedingPrices(), units,
				inputs.context().prices());
		pasture.decide(data, pasturePrices);
		forestry.decide(data, YearPrices.forYear(2020, forestry.servicesNeedingPrices(), units, inputs.context().prices()));
		cells.put("1,1", new Cell(1, 1));
		cells.put("1,2", new Cell(1, 2));
		cells.put("2,1", new Cell(2, 1));
	}

	/** Hands over with the context's production levels, as the updater does. */
	private ReactHandover.Counts handOver() {
		return ReactHandover.build(cells, units, inputs.context()::productionLevel).write(crops.managements().values(),
				pasture.managements().values(), forestry.managements().values());
	}

	private CropManagement crop(String label) {
		return crops.managements().get(label);
	}

	private PastureManagement pastureAft(String label) {
		return pasture.managements().get(label);
	}

	@Test
	void eachCellTakesItsUnitsSuitAndCosts() {
		decide(ReactToyData.context(dir));

		ReactHandover.Counts counts = handOver();

		for (Map.Entry<String, Cell> entry : cells.entrySet()) {
			int unit = units.unitOf(entry.getKey());
			Cell cell = entry.getValue();
			for (String label : new String[] { "IntC3C_irrig", "ExtC3C" }) {
				CropManagement m = crop(label);
				assertEquals(m.yield()[unit], cell.getCapitals().get(label + "_suit"), label + " in " + entry.getKey());
				assertEquals(m.nitrogenCost()[unit], cell.getNfertCosts().get(label));
				assertEquals(m.intensityCost()[unit], cell.getIntensityCosts().get(label));
			}
			assertEquals(crop("IntC3C_irrig").irrigationCost()[unit], cell.getIrrigationCosts().get("IntC3C_irrig"));
			assertFalse(cell.getIrrigationCosts().containsKey("ExtC3C"), "ExtC3C is rainfed");
			PastureManagement intP = pastureAft("IntP");
			assertEquals(intP.production()[unit], cell.getCapitals().get("IntP_suit"));
			assertEquals(intP.intensityCost()[unit], cell.getIntensityCosts().get("IntP"));
			assertEquals(intP.stockingCost()[unit], cell.getStockingCosts().get("IntP"));
		}
		// 3 _suit capitals; costs: IntC3C_irrig N, water, intensity; ExtC3C N, intensity; IntP intensity, stocking.
		assertEquals(new ReactHandover.Counts(3, 7, 3), counts);
	}

	@Test
	void aCellInPixelAsSouthTakesTheSouthUnit() {
		decide(ReactToyData.context(dir));
		int north = units.unitOf("1,1");
		int south = units.unitOf("1,2");
		assertNotEquals(north, south);
		double[] nitrogenCost = crop("IntC3C_irrig").nitrogenCost();
		assertNotEquals(nitrogenCost[north], nitrogenCost[south], "the South's lower price gives another N");

		handOver();

		assertEquals(nitrogenCost[north], cells.get("1,1").getNfertCosts().get("IntC3C_irrig"));
		assertEquals(nitrogenCost[south], cells.get("1,2").getNfertCosts().get("IntC3C_irrig"));
	}

	@Test
	void everythingElseStaysAsCoreLoadedIt() {
		decide(ReactToyData.context(dir).off(ReactElement.FERTILISER));
		Cell cell = cells.get("1,1");
		// As core loaded them: a non-reactive AFT's suitability and costs, another capital, and the N cost of a
		// reactive AFT with fertiliser off.
		cell.getCapitals().put("IntFodder_suit", 0.7);
		cell.getCapitals().put("react_GDP_50", 0.9);
		cell.getNfertCosts().put("IntFodder", 11.0);
		cell.getIntensityCosts().put("IntFodder", 12.0);
		cell.getIntensityCosts().put("AF", 13.0);
		cell.getNfertCosts().put("IntC3C_irrig", 14.0);

		handOver();

		assertEquals(0.7, cell.getCapitals().get("IntFodder_suit"));
		assertEquals(0.9, cell.getCapitals().get("react_GDP_50"));
		assertEquals(11.0, cell.getNfertCosts().get("IntFodder"));
		assertEquals(12.0, cell.getIntensityCosts().get("IntFodder"));
		assertEquals(13.0, cell.getIntensityCosts().get("AF"));
		assertEquals(14.0, cell.getNfertCosts().get("IntC3C_irrig"), "fertiliser is off, so core's N cost stays");
		assertEquals(crop("IntC3C_irrig").yield()[units.unitOf("1,1")], cell.getCapitals().get("IntC3C_irrig_suit"),
				"the other crop elements are on, so the crops suitability is handed over");
	}

	@Test
	void withOnlyStockingOnTheCropsSuitabilitiesAreLeftAlone() {
		decide(ReactToyData.context(dir).off(ReactElement.FERTILISER, ReactElement.IRRIGATION,
				ReactElement.OTHER_INTENSITY));
		Cell cell = cells.get("1,1");
		cell.getCapitals().put("IntC3C_irrig_suit", 0.5);
		cell.getIntensityCosts().put("IntP", 20.0);

		ReactHandover.Counts counts = handOver();

		assertEquals(0.5, cell.getCapitals().get("IntC3C_irrig_suit"), "phase 3 plan, Q6");
		assertFalse(cell.getCapitals().containsKey("ExtC3C_suit"));
		assertTrue(cell.getNfertCosts().isEmpty());
		int unit = units.unitOf("1,1");
		assertEquals(pastureAft("IntP").production()[unit], cell.getCapitals().get("IntP_suit"));
		assertEquals(pastureAft("IntP").stockingCost()[unit], cell.getStockingCosts().get("IntP"));
		assertEquals(20.0, cell.getIntensityCosts().get("IntP"), "other intensity is off, so core's cost stays");
		assertEquals(new ReactHandover.Counts(1, 1, 3), counts);
	}

	@Test
	void eachCellTakesItsUnitsForestrySuitAndRotationCost() {
		decideWithForestry(ReactToyData.context(dir).withForestry());

		ReactHandover.Counts counts = handOver();

		for (Map.Entry<String, Cell> entry : cells.entrySet()) {
			int unit = units.unitOf(entry.getKey());
			Cell cell = entry.getValue();
			for (String label : new String[] { "IntBF", "ExtBF" }) {
				ForestryManagement m = forestry.managements().get(label);
				assertEquals(m.yield()[unit], cell.getCapitals().get(label + "_suit"), label + " in " + entry.getKey());
				assertEquals(m.cost()[unit], cell.getIntensityCosts().get(label), label + " in " + entry.getKey());
			}
		}
		// As in eachCellTakesItsUnitsSuitAndCosts, and a _suit and an intensity cost for each forestry AFT.
		assertEquals(new ReactHandover.Counts(5, 9, 3), counts);
	}

	@Test
	void withOtherIntensityOffTheForestryIntensityCostIsStillHandedOver() {
		decideWithForestry(ReactToyData.context(dir).withForestry().off(ReactElement.OTHER_INTENSITY));
		Cell cell = cells.get("1,1");
		cell.getIntensityCosts().put("IntC3C_irrig", 20.0);
		cell.getIntensityCosts().put("IntP", 21.0);
		cell.getIntensityCosts().put("IntBF", 22.0);

		handOver();

		assertEquals(20.0, cell.getIntensityCosts().get("IntC3C_irrig"), "other intensity is off, so core's cost stays");
		assertEquals(21.0, cell.getIntensityCosts().get("IntP"));
		assertEquals(forestry.managements().get("IntBF").cost()[units.unitOf("1,1")], cell.getIntensityCosts().get("IntBF"),
				"forestry has its own switch");
	}

	@Test
	void aNonReactiveForestryAftAndForestryAftsWithForestryOffKeepCoresValues() {
		decideWithForestry(ReactToyData.context(dir).withForestry());
		Cell cell = cells.get("1,1");
		cell.getCapitals().put("AF_suit", 0.6);
		cell.getIntensityCosts().put("AF", 13.0);

		handOver();

		assertEquals(0.6, cell.getCapitals().get("AF_suit"), "AF names Hardwood but is not reactive");
		assertEquals(13.0, cell.getIntensityCosts().get("AF"));

		decideWithForestry(ReactToyData.context(dir).withForestry().off(ReactElement.FORESTRY));
		cell = cells.get("1,1");
		cell.getCapitals().put("IntBF_suit", 0.4);
		cell.getIntensityCosts().put("IntBF", 30.0);

		ReactHandover.Counts counts = handOver();

		assertTrue(forestry.managements().isEmpty(), "phase 3 plan, Q6");
		assertEquals(0.4, cell.getCapitals().get("IntBF_suit"));
		assertEquals(30.0, cell.getIntensityCosts().get("IntBF"));
		assertEquals(new ReactHandover.Counts(3, 7, 3), counts, "crops and pasture only");
	}

	@Test
	void aCellTheKeyDoesNotListIsRefused() {
		decide(ReactToyData.context(dir));
		cells.put("9,9", new Cell(9, 9));

		ReactInputException e = assertThrows(ReactInputException.class,
				() -> ReactHandover.build(cells, units, inputs.context()::productionLevel));

		assertTrue(e.getMessage().contains("9,9"), e.getMessage());
	}

	// ---- costs scaled by the production level (35a) ----

	@Test
	void eachCostIsScaledByTheAftsProductionLevelForItsServiceAndTheSuitIsNot() {
		// Asked for the AFT's own service: any other service would give 1 here.
		decide(ReactToyData.context(dir).productionLevels((aft, service) -> aft.equals("IntC3C_irrig")
				&& service.equals("C3cereals") ? 0.5 : aft.equals("IntP") && service.equals("Pasture") ? 0.25 : 1.0));

		ReactHandover.Counts counts = handOver();

		for (Map.Entry<String, Cell> entry : cells.entrySet()) {
			int unit = units.unitOf(entry.getKey());
			Cell cell = entry.getValue();
			CropManagement intC3C = crop("IntC3C_irrig");
			assertEquals(intC3C.yield()[unit], cell.getCapitals().get("IntC3C_irrig_suit"), "the yield is not scaled");
			assertEquals(intC3C.nitrogenCost()[unit] * 0.5, cell.getNfertCosts().get("IntC3C_irrig"));
			assertEquals(intC3C.irrigationCost()[unit] * 0.5, cell.getIrrigationCosts().get("IntC3C_irrig"));
			assertEquals(intC3C.intensityCost()[unit] * 0.5, cell.getIntensityCosts().get("IntC3C_irrig"));
			assertEquals(crop("ExtC3C").nitrogenCost()[unit], cell.getNfertCosts().get("ExtC3C"), "ExtC3C is at 1");
			PastureManagement intP = pastureAft("IntP");
			assertEquals(intP.production()[unit], cell.getCapitals().get("IntP_suit"), "the production is not scaled");
			assertEquals(intP.intensityCost()[unit] * 0.25, cell.getIntensityCosts().get("IntP"));
			assertEquals(intP.stockingCost()[unit] * 0.25, cell.getStockingCosts().get("IntP"));
		}
		assertEquals(new ReactHandover.Counts(3, 7, 3, Map.of("IntC3C_irrig", 0.5, "IntP", 0.25)), counts);
	}

	@Test
	void aForestryAftsRotationCostIsScaledAndItsYieldIsNot() {
		decideWithForestry(ReactToyData.context(dir).withForestry()
				.productionLevels((aft, service) -> aft.equals("IntBF") && service.equals("Hardwood") ? 0.75 : 1.0));

		ReactHandover.Counts counts = handOver();

		ForestryManagement intBF = forestry.managements().get("IntBF");
		ForestryManagement extBF = forestry.managements().get("ExtBF");
		for (Map.Entry<String, Cell> entry : cells.entrySet()) {
			int unit = units.unitOf(entry.getKey());
			Cell cell = entry.getValue();
			assertEquals(intBF.yield()[unit], cell.getCapitals().get("IntBF_suit"));
			assertEquals(intBF.cost()[unit] * 0.75, cell.getIntensityCosts().get("IntBF"));
			assertEquals(extBF.cost()[unit], cell.getIntensityCosts().get("ExtBF"));
		}
		assertEquals(Map.of("IntBF", 0.75), counts.scaled());
	}

	@Test
	void withStockingOffThePastureHusbandryCostIsStillScaled() {
		// The Pasture level is then core's stocking rate; the cost is scaled all the same (35a plan, Q2).
		decide(ReactToyData.context(dir).off(ReactElement.STOCKING)
				.productionLevels((aft, service) -> aft.equals("IntP") ? 1.5 : 1.0));

		handOver();

		int unit = units.unitOf("1,1");
		Cell cell = cells.get("1,1");
		assertEquals(pastureAft("IntP").intensityCost()[unit] * 1.5, cell.getIntensityCosts().get("IntP"));
		assertFalse(cell.getStockingCosts().containsKey("IntP"), "stocking is core's");
	}

	@Test
	void atLevelZeroTheCostsAreZero() {
		decide(ReactToyData.context(dir).productionLevels((aft, service) -> aft.equals("IntP") ? 0 : 1.0));

		handOver();

		Cell cell = cells.get("1,1");
		assertEquals(0.0, cell.getIntensityCosts().get("IntP"));
		assertEquals(0.0, cell.getStockingCosts().get("IntP"));
		assertEquals(pastureAft("IntP").production()[units.unitOf("1,1")], cell.getCapitals().get("IntP_suit"));
	}

	@Test
	void theLevelIsAskedForAtEachHandover() {
		// Core changes it when it loads a year-specific production file.
		double[] level = { 1.0 };
		decide(ReactToyData.context(dir).productionLevels((aft, service) -> aft.equals("ExtC3C") ? level[0] : 1.0));
		ReactHandover handover = ReactHandover.build(cells, units, inputs.context()::productionLevel);
		double nitrogenCost = crop("ExtC3C").nitrogenCost()[units.unitOf("1,1")];

		ReactHandover.Counts atOne = handover.write(crops.managements().values(), pasture.managements().values(),
				forestry.managements().values());
		assertEquals(nitrogenCost, cells.get("1,1").getNfertCosts().get("ExtC3C"));
		assertEquals(Map.of(), atOne.scaled());

		level[0] = 2.0;
		ReactHandover.Counts atTwo = handover.write(crops.managements().values(), pasture.managements().values(),
				forestry.managements().values());
		assertEquals(nitrogenCost * 2, cells.get("1,1").getNfertCosts().get("ExtC3C"));
		assertEquals(Map.of("ExtC3C", 2.0), atTwo.scaled());
	}

	// ---- writing by unit (37a) ----

	/**
	 * A cell key for a grid of {@code width} x {@code height} cells, {@code side} x {@code side} cells to a 0.5°
	 * pixel, listed row by row. Cells in rows below {@code southFrom} are North and the rest South, so a row of
	 * pixels can straddle the border.
	 */
	private CellKey gridKey(String folder, int width, int height, int side, int southFrom) {
		List<String> lines = new ArrayList<>();
		lines.add("X,Y,LPJ_cell_x,LPJ_cell_y,region");
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				lines.add(x + "," + y + "," + (x / side) * 0.5 + "," + (y / side) * 0.5 + ","
						+ (y < southFrom ? "North" : "South"));
			}
		}
		return CellKey.load(ReactToyData.write(dir, folder + "/cell_key.csv", lines.toArray(new String[0])));
	}

	/** The model's cells, as core holds them (a hash map): the key's cells that {@code inModel} accepts. */
	private static Map<String, Cell> modelCells(CellKey key, Predicate<String> inModel) {
		Map<String, Cell> model = new HashMap<>();
		for (int pixel = 0; pixel < key.grid().size(); pixel++) {
			for (String id : key.cellsIn(pixel)) {
				if (inModel.test(id)) {
					String[] xy = id.split(",");
					model.put(id, new Cell(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
				}
			}
		}
		return model;
	}

	/** A value for each unit that no other unit or column has. */
	private static double[] byUnit(int units, int column) {
		double[] values = new double[units];
		for (int unit = 0; unit < units; unit++) {
			values[unit] = column * 100_000.0 + unit + 0.5;
		}
		return values;
	}

	private static ReactHandover handoverFor(Map<String, Cell> model, DecisionUnits units) {
		return ReactHandover.build(model, units, (aft, service) -> 1.0);
	}

	@Test
	void aLargeGridMatchesAPlainLoopOverEveryCell() {
		// 60 x 60 cells, 4 x 4 to a pixel: 15 x 15 pixels. Rows 0-29 are North and 30-59 South, so the pixels of
		// rows 28-31 straddle the border and have two units each.
		CellKey key = gridKey("grid", 60, 60, 4, 30);
		DecisionUnits gridUnits = DecisionUnits.build(key, Set.of("North", "South"));
		assertEquals(225 + 15, gridUnits.size());
		Map<String, Cell> model = modelCells(key, id -> true);
		// Three columns into each of the cell's five sets of values, one of each three scaled.
		List<Column> columns = new ArrayList<>();
		for (Target target : Target.values()) {
			for (int c = 0; c < 3; c++) {
				columns.add(new Column("Grid" + target + c, target, byUnit(gridUnits.size(), columns.size()),
						c == 1 ? 0.5 : 1));
			}
		}

		handoverFor(model, gridUnits).writeColumns(columns);

		// The plain loop: every column, every cell, the value of the cell's own unit.
		for (Map.Entry<String, Cell> entry : model.entrySet()) {
			int unit = gridUnits.unitOf(entry.getKey());
			for (Column column : columns) {
				assertEquals(column.byUnit()[unit] * column.factor(),
						column.target().of(entry.getValue()).get(column.name()), column.name() + " in " + entry.getKey());
			}
		}
	}

	@Test
	void everyCellOfAUnitWithManyCellsTakesItsUnitsValues() {
		// One pixel of 8 x 8 cells, all North: one unit with 64 cells.
		CellKey key = gridKey("onePixel", 8, 8, 8, 8);
		DecisionUnits oneUnit = DecisionUnits.build(key, Set.of("North", "South"));
		assertEquals(1, oneUnit.size());
		Map<String, Cell> model = modelCells(key, id -> true);
		Column suit = new Column("ManyCells_suit", Target.CAPITALS, new double[] { 0.8 }, 1);
		Column nitrogen = new Column("ManyCells", Target.NFERT, new double[] { 40 }, 0.5);

		handoverFor(model, oneUnit).writeColumns(List.of(suit, nitrogen));

		assertEquals(64, model.size());
		for (Map.Entry<String, Cell> entry : model.entrySet()) {
			assertEquals(0.8, entry.getValue().getCapitals().get("ManyCells_suit"), entry.getKey());
			assertEquals(20.0, entry.getValue().getNfertCosts().get("ManyCells"), entry.getKey());
		}
	}

	@Test
	void aPixelSplitAcrossTwoRegionsGivesEachCellItsOwnRegionsValues() {
		// One pixel whose cells the key lists North, South, North, South, ...: two units, their cells interleaved.
		List<String> lines = new ArrayList<>(List.of("X,Y,LPJ_cell_x,LPJ_cell_y,region"));
		for (int x = 0; x < 10; x++) {
			lines.add(x + ",0,10.25,50.25," + (x % 2 == 0 ? "North" : "South"));
		}
		CellKey key = CellKey.load(ReactToyData.write(dir, "split/cell_key.csv", lines.toArray(new String[0])));
		DecisionUnits splitUnits = DecisionUnits.build(key, Set.of("North", "South"));
		assertEquals(2, splitUnits.size());
		Map<String, Cell> model = modelCells(key, id -> true);
		double[] byRegionUnit = new double[2];
		byRegionUnit[splitUnits.unitOf("0,0")] = 10;
		byRegionUnit[splitUnits.unitOf("1,0")] = 20;

		handoverFor(model, splitUnits).writeColumns(List.of(new Column("Split", Target.INTENSITY, byRegionUnit, 1)));

		for (int x = 0; x < 10; x++) {
			assertEquals(x % 2 == 0 ? 10.0 : 20.0, model.get(x + ",0").getIntensityCosts().get("Split"), "cell " + x);
		}
	}

	@Test
	void unitsWithoutModelCellsAreSkipped() {
		// The key covers three pixels in a row, but the model has cells in the middle one only, so the first and
		// last units have no cells to write.
		CellKey key = gridKey("partial", 12, 4, 4, 4);
		DecisionUnits threeUnits = DecisionUnits.build(key, Set.of("North", "South"));
		assertEquals(3, threeUnits.size());
		Map<String, Cell> model = modelCells(key, id -> {
			int x = Integer.parseInt(id.split(",")[0]);
			return x >= 4 && x < 8;
		});
		assertEquals(16, model.size());

		handoverFor(model, threeUnits).writeColumns(
				List.of(new Column("Partial", Target.STOCKING, new double[] { 1, 2, 3 }, 1)));

		for (Cell cell : model.values()) {
			assertEquals(2.0, cell.getStockingCosts().get("Partial"));
		}
	}

	@Test
	void namesNewToTheModelTakeTheirPlacesInColumnOrder() {
		// Names no other test uses, so they are new to the cells' shared index, listed in reverse alphabetical
		// order so that column order is not also the natural order.
		CellKey key = gridKey("order", 8, 8, 4, 4);
		DecisionUnits orderUnits = DecisionUnits.build(key, Set.of("North", "South"));
		Map<String, Cell> model = modelCells(key, id -> true);
		List<String> names = new ArrayList<>();
		List<Column> columns = new ArrayList<>();
		for (char c = 'z'; c >= 'a'; c--) {
			String name = "OrderTest_" + c;
			names.add(name);
			columns.add(new Column(name + ReactStartupCheck.SUIT, Target.CAPITALS, new double[orderUnits.size()], 1));
			columns.add(new Column(name, Target.INTENSITY, new double[orderUnits.size()], 1));
		}

		handoverFor(model, orderUnits).writeColumns(columns);

		List<String> suits = names.stream().map(name -> name + ReactStartupCheck.SUIT).toList();
		for (Map.Entry<String, Cell> entry : model.entrySet()) {
			assertEquals(suits, entry.getValue().getCapitals().keySet().stream()
					.filter(name -> name.startsWith("OrderTest_")).toList(), entry.getKey());
			assertEquals(names, entry.getValue().getIntensityCosts().keySet().stream()
					.filter(name -> name.startsWith("OrderTest_")).toList(), entry.getKey());
		}
	}
}
