package com.chaekcheck.collection.internal.importer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Step ④'s split of one same-text key by subtitle (cycle 12, user decision 2026-10-14): two different subtitles are
 * different books when one library holds both as separate items ("영단어 무한 지배자 : 고등편 / 중등편"), and the same
 * book otherwise ("수면의 뇌과학 - 치매, 암, …" in one library, "- …: 쓸모 많은 뇌과학 11" in another). Every way of
 * grouping calls {@link #of} with the key's groups in the order of their first unit, so the result is the same.
 * <ol>
 * <li>Subtitle classes: a subtitle joins the first earlier class whose first subtitle it contains or is contained in
 * (a shortened subtitle is the same subtitle).</li>
 * <li>Merges: a class joins the first earlier merge none of whose classes shares a library with it.</li>
 * <li>With one merge nothing is split. With two or more, the groups without a subtitle stay apart: which merge they
 * belong to is not sure.</li>
 * </ol>
 */
final class SubtitleParts {

	/** Part of a group that is not split. */
	static final int WHOLE = 0;

	/** Part of the groups without a subtitle when the key is split. */
	static final int APART = -1;

	private SubtitleParts() {
	}

	/**
	 * @param subtitles per group its subtitle ({@link SameTextKey.Key#subtitle()}), empty for none
	 * @param libraries per group the service libraries holding it
	 * @return per group {@link #WHOLE}, {@link #APART} or its merge number from 1
	 */
	static int[] of(List<String> subtitles, List<long[]> libraries) {
		int n = subtitles.size();
		int[] classOf = new int[n];
		List<String> firsts = new ArrayList<>();
		List<Set<Long>> classLibraries = new ArrayList<>();
		for (int g = 0; g < n; g++) {
			String subtitle = subtitles.get(g);
			if (subtitle.isEmpty()) {
				classOf[g] = -1;
				continue;
			}
			int found = -1;
			for (int c = 0; c < firsts.size() && found < 0; c++) {
				String first = firsts.get(c);
				if (subtitle.contains(first) || first.contains(subtitle)) {
					found = c;
				}
			}
			if (found < 0) {
				found = firsts.size();
				firsts.add(subtitle);
				classLibraries.add(new HashSet<>());
			}
			classOf[g] = found;
			for (long library : libraries.get(g)) {
				classLibraries.get(found).add(library);
			}
		}
		int[] parts = new int[n];
		if (firsts.size() < 2) {
			return parts;
		}
		// merges of classes, in the order of the classes
		int[] mergeOf = new int[firsts.size()];
		List<List<Integer>> merges = new ArrayList<>();
		for (int c = 0; c < firsts.size(); c++) {
			int found = -1;
			for (int m = 0; m < merges.size() && found < 0; m++) {
				boolean apart = false;
				for (int other : merges.get(m)) {
					if (!disjoint(classLibraries.get(c), classLibraries.get(other))) {
						apart = true;
						break;
					}
				}
				if (!apart) {
					found = m;
				}
			}
			if (found < 0) {
				found = merges.size();
				merges.add(new ArrayList<>());
			}
			merges.get(found).add(c);
			mergeOf[c] = found;
		}
		if (merges.size() < 2) {
			return parts;
		}
		for (int g = 0; g < n; g++) {
			parts[g] = classOf[g] < 0 ? APART : mergeOf[classOf[g]] + 1;
		}
		return parts;
	}

	private static boolean disjoint(Set<Long> a, Set<Long> b) {
		Set<Long> small = a.size() <= b.size() ? a : b;
		Set<Long> large = small == a ? b : a;
		for (long library : small) {
			if (large.contains(library)) {
				return false;
			}
		}
		return true;
	}

}
