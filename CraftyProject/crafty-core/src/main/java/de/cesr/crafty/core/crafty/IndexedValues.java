package de.cesr.crafty.core.crafty;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * One cell's values for a set of names: its capitals, or one production cost by AFT label.
 *
 * The values are kept in a {@code double[]} rather than a map: 8 bytes a value instead of about 56 (a map entry
 * plus a boxed {@code Double}), which on a global grid is most of the heap. Where each name sits in the array is
 * decided by an {@link Index} that every cell shares, so a cell stores only its numbers.
 *
 * It answers the same calls as the {@code ConcurrentHashMap<String, Double>} it replaces, with the same results. A
 * name that was never put is missing, whatever the array holds at its position: {@link #get} gives null,
 * {@link #getOrDefault} the default and {@link #containsKey} false. A value that was put, including 0 and NaN, is
 * present. So a capital the cell never got still counts as 0 in production, is written as "null" in the cell CSV
 * output and as NaN in PNG maps, while a NaN that CRAFTY-react hands over stays NaN.
 *
 * Several threads may fill different cells at once (the CSV loaders do, one row, so one cell, per thread), but two
 * threads must not write to the same cell's values at the same time.
 */
public final class IndexedValues {

	/**
	 * Gives each name a fixed position in the values of every cell that shares it. It only grows: a name takes the
	 * next position the first time it is put into any cell.
	 */
	public static final class Index {
		private final ConcurrentHashMap<String, Integer> positions = new ConcurrentHashMap<>();
		private volatile String[] names = new String[0];

		/** The name's position, or -1 if it has never been put into any cell. */
		int positionOf(String name) {
			Integer position = positions.get(name);
			return position == null ? -1 : position;
		}

		/** The name's position, giving it the next one if it is new. */
		int register(String name) {
			Integer position = positions.get(name);
			return position != null ? position : registerNew(name);
		}

		private synchronized int registerNew(String name) {
			Integer position = positions.get(name);
			if (position != null) {
				return position;
			}
			int next = names.length;
			String[] grown = Arrays.copyOf(names, next + 1);
			grown[next] = name;
			// The name is published before its position, so whoever finds the position also finds the name.
			names = grown;
			positions.put(name, next);
			return next;
		}

		/** How many names have a position. */
		int size() {
			return names.length;
		}

		String nameAt(int position) {
			return names[position];
		}
	}

	private final Index index;
	// The values by position; null until the first put.
	private double[] values;
	// Bit (position % 64) of word (position / 64) is set once that position has been put.
	private long[] present;

	public IndexedValues(Index index) {
		this.index = index;
	}

	/** The value put for the name, or null if none was (as {@code Map.get}). */
	public Double get(String name) {
		int position = index.positionOf(name);
		return isPresent(position) ? Double.valueOf(values[position]) : null;
	}

	/**
	 * The value put for the name, or {@code ifMissing} if none was (as {@code Map.getOrDefault}). It returns a plain
	 * {@code double}, so production's inner loop creates no objects.
	 */
	public double getOrDefault(String name, double ifMissing) {
		int position = index.positionOf(name);
		return isPresent(position) ? values[position] : ifMissing;
	}

	/** Whether a value was put for the name (as {@code Map.containsKey}). */
	public boolean containsKey(String name) {
		return isPresent(index.positionOf(name));
	}

	/** Sets the name's value, replacing any earlier one. */
	public void put(String name, double value) {
		int position = index.register(name);
		if (values == null || position >= values.length) {
			grow(position);
		}
		values[position] = value;
		present[position / 64] |= 1L << (position % 64);
	}

	/** Puts every entry of the map. */
	public void putAll(Map<String, Double> map) {
		map.forEach(this::put);
	}

	/** Whether no value was put at all. */
	public boolean isEmpty() {
		if (present != null) {
			for (long word : present) {
				if (word != 0) {
					return false;
				}
			}
		}
		return true;
	}

	/** The names that have a value, in position order. A copy: changing it changes nothing here. */
	public Set<String> keySet() {
		return toMap().keySet();
	}

	/** The names and values, in position order. A copy: changing it changes nothing here. */
	public Set<Map.Entry<String, Double>> entrySet() {
		return toMap().entrySet();
	}

	/** Passes each name that has a value, with that value, in position order. */
	public void forEach(BiConsumer<String, Double> action) {
		if (values == null) {
			return;
		}
		for (int position = 0; position < values.length; position++) {
			if (isPresent(position)) {
				action.accept(index.nameAt(position), values[position]);
			}
		}
	}

	/** A copy of the names and values, in position order. */
	public Map<String, Double> toMap() {
		Map<String, Double> map = new LinkedHashMap<>();
		forEach(map::put);
		return map;
	}

	/** As a map prints: {@code {name=value, ...}}. */
	@Override
	public String toString() {
		return toMap().toString();
	}

	private boolean isPresent(int position) {
		return position >= 0 && values != null && position < values.length
				&& (present[position / 64] & (1L << (position % 64))) != 0;
	}

	/** Makes room for the position, and for every other name the index knows so far. */
	private void grow(int position) {
		int length = Math.max(index.size(), position + 1);
		values = values == null ? new double[length] : Arrays.copyOf(values, length);
		int words = (length + 63) / 64;
		present = present == null ? new long[words] : Arrays.copyOf(present, words);
	}
}
