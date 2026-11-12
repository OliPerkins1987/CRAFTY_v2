package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.cesr.crafty.core.modelRunner.AbstractModelRunner;
import de.cesr.crafty.core.modelRunner.ModelState;

class StepInserterTest {

	/** A named stand-in for one of CRAFTY's scheduled components. */
	private record Named(String name) implements ModelState {
		@Override
		public void setup(AbstractModelRunner modelRunner) {
		}

		@Override
		public void toSchedule() {
		}

		@Override
		public void step() {
		}

		@Override
		public String toString() {
			return name;
		}
	}

	/** ModelRunner.start()'s schedule, with react's step after ProductionCost. */
	private static List<ModelState> craftySchedule() {
		List<ModelState> schedule = new ArrayList<>();
		for (String name : List.of("Flag", "Services", "Capital", "ProductionCost", "React", "Subsidy", "Afts",
				"CellBehaviour", "LandMask", "CapitalDegradation", "Supply", "Listener", "Tracker", "Regions",
				"Timestep")) {
			schedule.add(new Named(name));
		}
		return schedule;
	}

	private static final StepInserter.Anchors ANCHORS = new StepInserter.Anchors(s -> is(s, "Flag"),
			s -> is(s, "Capital"), s -> is(s, "Supply"), s -> is(s, "Regions"), s -> is(s, "Timestep"));

	private static boolean is(ModelState s, String name) {
		return s instanceof Named n && n.name().equals(name);
	}

	private static final StepInserter.Steps STEPS = new StepInserter.Steps(new Named("PlumInput"),
			new Named("TradeCapital"), new Named("Consumption"), new Named("Production"));

	private static List<String> names(List<ModelState> schedule) {
		return schedule.stream().map(Object::toString).toList();
	}

	@Test
	void sequentialWritesProductionAfterTheYearsLandUse() {
		List<ModelState> schedule = craftySchedule();

		StepInserter.insert(schedule, STEPS, "sequential", ANCHORS);

		assertEquals(List.of("PlumInput", "Flag", "Services", "Capital", "TradeCapital", "ProductionCost", "React",
				"Subsidy", "Afts", "CellBehaviour", "LandMask", "CapitalDegradation", "Supply", "Consumption",
				"Listener", "Tracker", "Regions", "Production", "Timestep"), names(schedule));
	}

	@Test
	void overlappingWritesProductionBeforeCompetition() {
		List<ModelState> schedule = craftySchedule();

		StepInserter.insert(schedule, STEPS, "overlapping", ANCHORS);

		assertEquals(List.of("PlumInput", "Flag", "Services", "Capital", "TradeCapital", "ProductionCost", "React",
				"Subsidy", "Afts", "CellBehaviour", "LandMask", "CapitalDegradation", "Supply", "Consumption",
				"Production", "Listener", "Tracker", "Regions", "Timestep"), names(schedule));
	}

	@Test
	void aMissingAnchorStopsNamingIt() {
		List<ModelState> schedule = craftySchedule();
		schedule.removeIf(s -> is(s, "Supply") || is(s, "Flag"));

		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> StepInserter.insert(schedule, STEPS, "sequential", ANCHORS));

		assertTrue(e.getMessage().contains("[FlagUpdater, SupplyUpdater]"), e.getMessage());
		assertEquals(13, schedule.size(), "nothing inserted");
	}

	@Test
	void anUnknownModeStopsBeforeAnythingIsInserted() {
		List<ModelState> schedule = craftySchedule();

		assertThrows(CouplingInputException.class, () -> StepInserter.insert(schedule, STEPS, "parallel", ANCHORS));
		assertEquals(15, schedule.size());
	}
}
