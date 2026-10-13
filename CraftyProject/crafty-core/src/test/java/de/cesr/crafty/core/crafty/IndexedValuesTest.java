package de.cesr.crafty.core.crafty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * IndexedValues must answer as the ConcurrentHashMap it replaces did: a name never put is missing (null, the
 * default, not contained), and a value put (0 and NaN included) is present.
 */
class IndexedValuesTest {

	@Test
	void aNameNeverPutIsMissing() {
		IndexedValues values = new IndexedValues(new IndexedValues.Index());

		assertNull(values.get("capA"));
		assertEquals(0.0, values.getOrDefault("capA", 0.0), 0.0);
		assertTrue(Double.isNaN(values.getOrDefault("capA", Double.NaN)));
		assertFalse(values.containsKey("capA"));
		assertTrue(values.isEmpty());
		assertEquals(Set.of(), values.keySet());
		assertEquals("{}", values.toString());
	}

	@Test
	void aNameAnotherCellPutIsStillMissingHere() {
		IndexedValues.Index index = new IndexedValues.Index();
		IndexedValues first = new IndexedValues(index);
		IndexedValues second = new IndexedValues(index);
		first.put("capA", 1.0);
		first.put("capB", 2.0);
		second.put("capB", 5.0); // its array now has room for capA too, but capA was never put here

		assertNull(second.get("capA"));
		assertFalse(second.containsKey("capA"));
		assertEquals(0.0, second.getOrDefault("capA", 0.0), 0.0);
		assertEquals(5.0, second.get("capB"));
		assertEquals(2.0, first.get("capB"));
		assertEquals(1.0, first.get("capA"));
	}

	@Test
	void zeroAndNaNThatArePutArePresent() {
		IndexedValues values = new IndexedValues(new IndexedValues.Index());
		values.put("zero", 0.0);
		values.put("nan", Double.NaN);

		assertEquals(0.0, values.get("zero"));
		assertTrue(values.containsKey("zero"));
		assertEquals(0.0, values.getOrDefault("zero", 7.0), 0.0);

		assertTrue(Double.isNaN(values.get("nan")));
		assertTrue(values.containsKey("nan"));
		assertTrue(Double.isNaN(values.getOrDefault("nan", 0.0)), "a NaN that was put is not the default");
		assertFalse(values.isEmpty());
	}

	@Test
	void putReplacesTheValue() {
		IndexedValues values = new IndexedValues(new IndexedValues.Index());
		values.put("capA", 1.0);
		values.put("capA", 4.0);

		assertEquals(4.0, values.get("capA"));
		assertEquals(Set.of("capA"), values.keySet());
	}

	@Test
	void aReadDoesNotGiveTheNameAPosition() {
		IndexedValues.Index index = new IndexedValues.Index();
		IndexedValues values = new IndexedValues(index);

		values.get("capA");
		values.getOrDefault("capA", 0.0);
		values.containsKey("capA");

		assertEquals(0, index.size());
		assertEquals(-1, index.positionOf("capA"));
	}

	@Test
	void eachNameKeepsThePositionItWasFirstPutAt() {
		IndexedValues.Index index = new IndexedValues.Index();
		IndexedValues first = new IndexedValues(index);
		IndexedValues second = new IndexedValues(index);
		first.put("capB", 1.0);
		second.put("capA", 2.0);
		first.put("capA", 3.0);

		assertEquals(0, index.positionOf("capB"));
		assertEquals(1, index.positionOf("capA"));
		assertEquals(2, index.size());
		assertEquals("capA", index.nameAt(1));
	}

	@Test
	void aNameNewToTheIndexCanBePutIntoACellThatAlreadyHasValues() {
		IndexedValues.Index index = new IndexedValues.Index();
		IndexedValues early = new IndexedValues(index);
		early.put("capA", 1.0); // room for one name only
		IndexedValues other = new IndexedValues(index);
		for (int i = 0; i < 100; i++) {
			other.put("n" + i, i);
		}

		early.put("n99", 9.0);

		assertEquals(1.0, early.get("capA"), "kept when the values grew");
		assertEquals(9.0, early.get("n99"));
		assertNull(early.get("n50"));
		assertEquals(List.of("capA", "n99"), new ArrayList<>(early.keySet()));
	}

	@Test
	void moreThan64NamesEachKeepTheirOwnValue() {
		IndexedValues.Index index = new IndexedValues.Index();
		IndexedValues all = new IndexedValues(index);
		for (int i = 0; i < 130; i++) {
			all.put("n" + i, i * 0.5);
		}
		IndexedValues one = new IndexedValues(index);
		one.put("n100", 1.0);

		for (int i = 0; i < 130; i++) {
			assertEquals(i * 0.5, all.get("n" + i), "n" + i);
		}
		assertEquals(130, all.keySet().size());
		assertEquals(Set.of("n100"), one.keySet());
		assertFalse(one.containsKey("n36"), "n36 is bit 36 of the first word; n100 is bit 36 of the second");
		assertFalse(one.containsKey("n64"));
		assertFalse(one.containsKey("n128"));
	}

	@Test
	void listingsShowOnlyWhatWasPut_inPositionOrder() {
		IndexedValues.Index index = new IndexedValues.Index();
		IndexedValues other = new IndexedValues(index);
		other.put("a", 0.0);
		other.put("b", 0.0);
		other.put("c", 0.0);
		IndexedValues values = new IndexedValues(index);
		values.put("c", 3.0);
		values.put("a", 1.0);

		assertEquals(List.of("a", "c"), new ArrayList<>(values.keySet()));
		Map<String, Double> seen = new LinkedHashMap<>();
		values.forEach(seen::put);
		assertEquals(List.of("a", "c"), new ArrayList<>(seen.keySet()));
		assertEquals(Map.of("a", 1.0, "c", 3.0), seen);
		assertEquals(2, values.entrySet().size());
		assertEquals(Map.of("a", 1.0, "c", 3.0), values.toMap());
		assertEquals("{a=1.0, c=3.0}", values.toString());
	}

	@Test
	void changingAListingChangesNothing() {
		IndexedValues values = new IndexedValues(new IndexedValues.Index());
		values.put("capA", 1.0);

		values.keySet().clear();
		values.toMap().put("capB", 2.0);

		assertEquals(Set.of("capA"), values.keySet());
	}

	@Test
	void putAllPutsEveryEntry() {
		Map<String, Double> map = new LinkedHashMap<>();
		map.put("capA", 1.0);
		map.put("capB", Double.NaN);
		IndexedValues values = new IndexedValues(new IndexedValues.Index());

		values.putAll(map);

		assertEquals(map.keySet(), values.keySet());
		assertEquals(1.0, values.get("capA"));
		assertTrue(Double.isNaN(values.get("capB")));
	}

	@Test
	void namesGivenPositionsFromManyThreadsAtOnceEachGetOne() {
		IndexedValues.Index index = new IndexedValues.Index();

		IntStream.range(0, 8000).parallel().forEach(i -> index.register("n" + (i % 500)));

		assertEquals(500, index.size());
		Set<Integer> positions = new HashSet<>();
		for (int j = 0; j < 500; j++) {
			int position = index.positionOf("n" + j);
			assertEquals("n" + j, index.nameAt(position));
			positions.add(position);
		}
		assertEquals(500, positions.size());
	}

	@Test
	void cellsFilledInParallelKeepTheirOwnValues() {
		// As the CSV loaders do: many threads, one cell each, while the shared index is still growing.
		IndexedValues.Index index = new IndexedValues.Index();
		List<IndexedValues> cells = new ArrayList<>();
		for (int i = 0; i < 2000; i++) {
			cells.add(new IndexedValues(index));
		}

		IntStream.range(0, 2000).parallel().forEach(i -> {
			for (int k = 0; k < 100; k++) {
				int j = (i + k) % 100; // each cell puts the names in a different order
				cells.get(i).put("cap" + j, i * 1000 + j);
			}
		});

		AtomicInteger wrong = new AtomicInteger();
		for (int i = 0; i < 2000; i++) {
			for (int j = 0; j < 100; j++) {
				if (cells.get(i).getOrDefault("cap" + j, -1) != i * 1000 + j) {
					wrong.incrementAndGet();
				}
			}
		}
		assertEquals(0, wrong.get());
		assertEquals(100, index.size());
	}
}
