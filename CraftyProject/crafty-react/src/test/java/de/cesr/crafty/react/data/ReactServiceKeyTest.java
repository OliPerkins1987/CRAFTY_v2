package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.dataLoader.ProjectLoader;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;

class ReactServiceKeyTest {

	@TempDir
	Path dir;

	@Test
	void eachServiceHasItsLpjgNameAndType() {
		ReactServiceKey key = ReactServiceKey.load(ReactToyData.services(dir));

		assertEquals(List.of("C3cereals", "Pasture", "Hardwood", "Carbon"), List.copyOf(key.services()));
		assertEquals("CerealsC3", key.lpjgName("C3cereals"));
		assertEquals(LpjgType.CROPS, key.lpjgType("C3cereals"));
		assertEquals("Pasture_sum", key.lpjgName("Pasture"));
		assertEquals(LpjgType.PASTURE, key.lpjgType("Pasture"));
		assertEquals(LpjgType.FORESTRY, key.lpjgType("Hardwood"));
	}

	@Test
	void aBlankLpjgNameMeansTheServiceName() {
		ReactServiceKey key = ReactServiceKey.load(ReactToyData.services(dir));

		assertEquals("Hardwood", key.lpjgName("Hardwood"));
	}

	@Test
	void aBlankLpjgTypeMeansNoLpjgInput() {
		ReactServiceKey key = ReactServiceKey.load(ReactToyData.services(dir));

		assertTrue(key.contains("Carbon"));
		assertNull(key.lpjgType("Carbon"));
	}

	@Test
	void typeLabelsMatchTheSuitabilityFolderNames() {
		assertEquals("crops", LpjgType.CROPS.label());
		assertEquals("pasture", LpjgType.PASTURE.label());
		assertEquals("forestry", LpjgType.FORESTRY.label());
	}

	@Test
	void anUnknownTypeIsAnErrorThatListsTheAllowedValues() {
		// "crop" was the planned value before the user settled on "crops".
		Path file = ReactToyData.write(dir, "Services.csv", "Name,LPJG_name,LPJG_type", "C3cereals,CerealsC3,crop");

		ReactInputException e = assertThrows(ReactInputException.class, () -> ReactServiceKey.load(file));

		assertTrue(e.getMessage().contains("\"crop\"") && e.getMessage().contains("[crops, pasture, forestry]"),
				e.getMessage());
	}

	@Test
	void typesAreMatchedExactly() {
		Path file = ReactToyData.write(dir, "Services.csv", "Name,LPJG_name,LPJG_type", "C3cereals,CerealsC3,Crops");

		assertThrows(ReactInputException.class, () -> ReactServiceKey.load(file));
	}

	@Test
	void theOldLpjNameColumnIsNotEnough() {
		Path file = ReactToyData.write(dir, "Services.csv", "Name,LPJ_name,Description", "C3cereals,CerealsC3,x");

		ReactInputException e = assertThrows(ReactInputException.class, () -> ReactServiceKey.load(file));

		assertTrue(e.getMessage().contains("[LPJG_name, LPJG_type]"), e.getMessage());
	}

	@Test
	void aRepeatedOrBlankServiceIsAnError() {
		Path repeated = ReactToyData.write(dir, "a.csv", "Name,LPJG_name,LPJG_type", "Pasture,,pasture", "Pasture,,pasture");
		Path blank = ReactToyData.write(dir, "b.csv", "Name,LPJG_name,LPJG_type", ",CerealsC3,crops");

		assertThrows(ReactInputException.class, () -> ReactServiceKey.load(repeated));
		assertThrows(ReactInputException.class, () -> ReactServiceKey.load(blank));
	}

	@Test
	void askingForAnUnknownServiceIsAnError() {
		ReactServiceKey key = ReactServiceKey.load(ReactToyData.services(dir));

		assertFalse(key.contains("C4crops"));
		assertThrows(ReactInputException.class, () -> key.lpjgName("C4crops"));
	}

	@Test
	void coreStillReadsTheServiceListFromAFileWithTheLpjgColumns() throws Exception {
		Field metadata = ProjectLoader.class.getDeclaredField("serviceMetadata");
		metadata.setAccessible(true);
		Object original = metadata.get(null);
		try {
			metadata.set(null, ReactToyData.services(dir));

			ServiceSet.loadServiceList();

			assertEquals(List.of("C3cereals", "Pasture", "Hardwood", "Carbon"), ServiceSet.getServicesList());
		} finally {
			metadata.set(null, original);
		}
	}
}
