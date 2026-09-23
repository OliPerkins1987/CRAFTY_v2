package de.cesr.crafty.react.decisions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.react.data.CellKey;
import de.cesr.crafty.react.data.ReactInputException;
import de.cesr.crafty.react.data.ReactRunContext;
import de.cesr.crafty.react.data.ReactToyData;

/**
 * The toy key gives three units: pixel A in the North, pixel A in the South, and pixel B in the North.
 */
class YearPricesTest {

	@TempDir
	Path dir;

	private CellKey key;
	private DecisionUnits units;

	@BeforeEach
	void setUp() {
		key = CellKey.load(ReactToyData.cellKey(dir));
		units = DecisionUnits.build(key, Set.of("North", "South"));
	}

	@Test
	void eachUnitGetsThePriceOfItsRegion() {
		ReactRunContext context = ReactToyData.context(dir)
				.prices((service, region, year) -> region.equals("North") ? 120 : 80).build();

		YearPrices prices = YearPrices.forYear(2021, List.of("C3cereals"), units, context.prices());

		assertArrayEquals(new double[] { 120, 80, 120 }, prices.price("C3cereals"), 0,
				"Pixel A is priced in both regions; pixel B in the North");
		assertEquals(2021, prices.year());
	}

	@Test
	void theServiceAndTheYearArePassedOn() {
		YearPrices prices = YearPrices.forYear(2021, List.of("C3cereals", "Pasture"), units,
				(service, region, year) -> service.equals("Pasture") ? year + 0.5 : year);

		assertArrayEquals(new double[] { 2021, 2021, 2021 }, prices.price("C3cereals"), 0);
		assertArrayEquals(new double[] { 2021.5, 2021.5, 2021.5 }, prices.price("Pasture"), 0);
		assertEquals(List.of("C3cereals", "Pasture"), List.copyOf(prices.services()));
	}

	@Test
	void eachRegionIsLookedUpOncePerService() {
		List<String> asked = new ArrayList<>();

		YearPrices.forYear(2020, List.of("C3cereals", "Pasture", "C3cereals"), units, (service, region, year) -> {
			asked.add(service + " " + region);
			return 100;
		});

		assertEquals(List.of("C3cereals North", "C3cereals South", "Pasture North", "Pasture South"), asked,
				"Three units but two regions, and a service named twice is looked up once");
	}

	@Test
	void onlyTheServicesAskedForAreLookedUp() {
		YearPrices prices = YearPrices.forYear(2020, List.of("C3cereals"), units, (service, region, year) -> {
			if (!service.equals("C3cereals")) {
				throw new ReactInputException("no weight for " + service);
			}
			return 100;
		});

		ReactInputException e = assertThrows(ReactInputException.class, () -> prices.price("Pasture"));
		assertTrue(e.getMessage().contains("Pasture") && e.getMessage().contains("2020"), e.getMessage());
	}

	@Test
	void noServicesMeansNothingIsLookedUp() {
		YearPrices prices = YearPrices.forYear(2020, List.of(), units, (service, region, year) -> {
			throw new AssertionError("looked up " + service);
		});

		assertTrue(prices.services().isEmpty());
	}

	@Test
	void aMissingWeightNamesTheServiceRegionAndYear() {
		ReactInputException e = assertThrows(ReactInputException.class,
				() -> YearPrices.forYear(2021, List.of("C3cereals"), units, (service, region, year) -> {
					if (region.equals("South")) {
						throw new ReactInputException("no weight");
					}
					return 100;
				}));

		String message = e.getMessage();
		assertTrue(message.contains("C3cereals") && message.contains("region South") && message.contains("2021")
				&& message.contains("no weight"), message);
	}

	@Test
	void aWeightThatIsNotANumberIsAnError() {
		for (double weight : new double[] { Double.NaN, Double.POSITIVE_INFINITY }) {
			ReactInputException e = assertThrows(ReactInputException.class,
					() -> YearPrices.forYear(2020, List.of("C3cereals"), units, (service, region, year) -> weight));

			String message = e.getMessage();
			assertTrue(message.contains("C3cereals") && message.contains("region North") && message.contains("2020")
					&& message.contains(String.valueOf(weight)), message);
		}
	}

	@Test
	void inASingleRegionModelEveryUnitGetsThatRegionsPrice() {
		DecisionUnits single = DecisionUnits.build(key, Set.of("World"));

		YearPrices prices = YearPrices.forYear(2020, List.of("C3cereals"), single,
				(service, region, year) -> region.equals("World") ? 150 : Double.NaN);

		assertArrayEquals(new double[] { 150, 150 }, prices.price("C3cereals"), 0);
	}
}
