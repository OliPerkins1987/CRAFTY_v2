package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceCommodityMapTest {

	@TempDir
	Path tempDir;

	/** The global set-up's Services.csv, copied on 12 Nov 2026. */
	static Path globalServices() throws URISyntaxException {
		return Paths.get(ServiceCommodityMapTest.class.getResource("/services/Services.csv").toURI());
	}

	private Path globalServicesWith(String find, String replace) throws IOException, URISyntaxException {
		String text = Files.readString(globalServices(), StandardCharsets.UTF_8);
		assertTrue(text.contains(find), "fixture has: " + find);
		Path file = tempDir.resolve("Services.csv");
		Files.writeString(file, text.replace(find, replace), StandardCharsets.UTF_8);
		return file;
	}

	@Test
	void theGlobalServicesFileGivesThirteenMarketLinks() throws Exception {
		ServiceCommodityMap map = ServiceCommodityMap.read(globalServices());

		assertEquals(13, map.links().size());
		assertNull(map.link("Biodiversity"), "non-market goods aren't coupled");
		assertEquals(List.of("C3sugarcane", "C3sugarbeet"),
				map.servicesFor("sugar").stream().map(ServiceCommodityMap.Link::service).toList());
		assertEquals(List.of("Timber", "Fuelwood"),
				map.servicesFor("wood").stream().map(ServiceCommodityMap.Link::service).toList());
		ServiceCommodityMap.Link pasture = map.link("Pasture");
		assertEquals("pasture", pasture.commodity());
		assertEquals("ruminants", pasture.priceName(), "grass is priced on ruminants");
		assertEquals(0.000001, pasture.quantityFactor());
		assertEquals(1000, pasture.priceFactor());
		assertTrue(map.servicesFor("energycrops").isEmpty(), "no Miscanthus yet, which is allowed");
	}

	@Test
	void aMissingPlumColumnStopsTheRead() throws Exception {
		Path file = tempDir.resolve("Services.csv");
		Files.writeString(file, "Name,Market_good,PLUM_name\nC3cereals,Yes,wheat\n");

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> ServiceCommodityMap.read(file));

		assertTrue(e.getMessage().contains("PLUM_quantity_factor") && e.getMessage().contains("PLUM_price_name"),
				e.getMessage());
	}

	@Test
	void anUnknownCommodityOrPriceNameStopsTheRead() throws Exception {
		CouplingInputException commodity = assertThrows(CouplingInputException.class,
				() -> ServiceCommodityMap.read(globalServicesWith("for food,Yes,Yes,CerealsC3,crops,wheat,",
						"for food,Yes,Yes,CerealsC3,crops,barley,")));
		assertTrue(commodity.getMessage().contains("\"barley\""), commodity.getMessage());

		CouplingInputException price = assertThrows(CouplingInputException.class,
				() -> ServiceCommodityMap.read(globalServicesWith(",1000,ruminants", ",1000,pasture")));
		assertTrue(price.getMessage().contains("PLUM_price_name \"pasture\""), price.getMessage());
	}

	@Test
	void aFactorThatIsNotAPositiveNumberStopsTheRead() throws Exception {
		for (String bad : List.of("0", "-1", "", "abc")) {
			CouplingInputException e = assertThrows(CouplingInputException.class,
					() -> ServiceCommodityMap.read(globalServicesWith("C3 rice,Yes,Yes,Rice,crops,rice,0.000001,1000",
							"C3 rice,Yes,Yes,Rice,crops,rice," + bad + ",1000")));
			assertTrue(e.getMessage().contains("C3rice") && e.getMessage().contains("PLUM_quantity_factor"),
					e.getMessage());
		}
	}

	@Test
	void aNonMarketServiceWithPlumValuesStopsTheRead() throws Exception {
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> ServiceCommodityMap.read(globalServicesWith("Recreation,Recreation potential,No,No,,,,,,",
						"Recreation,Recreation potential,No,No,,,wood,,,")));

		assertTrue(e.getMessage().contains("Recreation") && e.getMessage().contains("not a market good"),
				e.getMessage());
	}

	@Test
	void aCommodityWithNoMarketServiceStopsTheReadExceptEnergyCrops() throws Exception {
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> ServiceCommodityMap.read(globalServicesWith(
						"for food,Yes,Yes,Pulses,crops,pulses,0.000001,1000,pulses", "for food,Yes,No,Pulses,crops,,,,")));

		assertTrue(e.getMessage().contains("no market service has PLUM_name pulses"), e.getMessage());
		assertFalse(e.getMessage().contains("energycrops"), "energy crops may have no service");
	}

	@Test
	void aMarketGoodThatIsNeitherYesNorNoStopsTheRead() throws Exception {
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> ServiceCommodityMap.read(globalServicesWith("for food,Yes,Yes,Pulses,", "for food,Yes,Maybe,Pulses,")));

		assertTrue(e.getMessage().contains("Market_good must be Yes or No"), e.getMessage());
	}

	@Test
	void everyProblemIsReportedTogether() throws Exception {
		String text = Files.readString(globalServices(), StandardCharsets.UTF_8)
				.replace("crops,rice,0.000001,1000,rice", "crops,rice,0,1000,rice")
				.replace("crops,maize,0.000001,1000,maize", "crops,corn,0.000001,1000,maize");
		Path file = tempDir.resolve("Services.csv");
		Files.writeString(file, text);

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> ServiceCommodityMap.read(file));

		assertEquals(3, e.getProblems().size(), "rice's factor, corn, and maize now has no service: " + e.getProblems());
	}
}
