package de.cesr.crafty.react;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.react.data.ReactConfig;
import de.cesr.crafty.react.data.ReactElement;
import de.cesr.crafty.react.data.ReactInputException;
import de.cesr.crafty.react.data.ReactInputs;
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
 */
class ReactHandoverTest {

	@TempDir
	Path dir;

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
		ReactInputs inputs = ReactInputs.create(ReactConfig.defaults(),
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

	private ReactHandover.Counts handOver() {
		return ReactHandover.build(cells, units).write(crops.managements().values(), pasture.managements().values(),
				forestry.managements().values());
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

		ReactInputException e = assertThrows(ReactInputException.class, () -> ReactHandover.build(cells, units));

		assertTrue(e.getMessage().contains("9,9"), e.getMessage());
	}
}
