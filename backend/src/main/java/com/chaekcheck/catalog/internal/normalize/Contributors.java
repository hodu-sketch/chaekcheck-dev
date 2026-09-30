package com.chaekcheck.catalog.internal.normalize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a collected author text into people and roles (research search-and-normalization 4.1 [안]).
 * <ul>
 * <li>People are split on {@code /}; a part with Hangul is split on commas too. {@code Richardson, James D.} stays one
 * person.</li>
 * <li>A role word after the names (after a space, or in brackets) applies to every name of that part. No role word
 * means UNKNOWN.</li>
 * <li>A bracket after a name that is not a role is an alias ({@code 김도형(별별역사)}).</li>
 * <li>{@code 외} (more people) is not a person.</li>
 * <li>글그림 gives two rows for the same person: AUTHOR and ILLUSTRATOR.</li>
 * </ul>
 */
public final class Contributors {

	public enum Role {

		AUTHOR, TRANSLATOR, ILLUSTRATOR, EDITOR, OTHER, UNKNOWN

	}

	/**
	 * One person with one role.
	 * @param alias bracket text after the name; may be null
	 * @param nameNorm name and alias normalized, for word search
	 * @param nameExact name without spaces and periods, for exact match
	 * @param position order in the original text, from 0 (both rows of 글그림 share it)
	 */
	public record Contributor(String name, String alias, String nameNorm, String nameExact, Role role, int position) {
	}

	private static final Map<String, List<Role>> ROLE_WORDS = Map.ofEntries(Map.entry("저", List.of(Role.AUTHOR)),
			Map.entry("글", List.of(Role.AUTHOR)), Map.entry("지음", List.of(Role.AUTHOR)),
			Map.entry("공저", List.of(Role.AUTHOR)), Map.entry("저자", List.of(Role.AUTHOR)),
			Map.entry("등저", List.of(Role.AUTHOR)), Map.entry("지은이", List.of(Role.AUTHOR)),
			Map.entry("역", List.of(Role.TRANSLATOR)), Map.entry("옮김", List.of(Role.TRANSLATOR)),
			Map.entry("공역", List.of(Role.TRANSLATOR)), Map.entry("옮긴이", List.of(Role.TRANSLATOR)),
			// seen in the collected data (2026-09-30 sample): "김현미 번역", "sayne 역자", "김은정 공편"
			Map.entry("역자", List.of(Role.TRANSLATOR)), Map.entry("번역", List.of(Role.TRANSLATOR)),
			Map.entry("공편", List.of(Role.EDITOR)), Map.entry("편자", List.of(Role.EDITOR)),
			Map.entry("편집", List.of(Role.EDITOR)),
			Map.entry("그림", List.of(Role.ILLUSTRATOR)),
			Map.entry("글그림", List.of(Role.AUTHOR, Role.ILLUSTRATOR)),
			Map.entry("글·그림", List.of(Role.AUTHOR, Role.ILLUSTRATOR)), Map.entry("편", List.of(Role.EDITOR)),
			Map.entry("엮음", List.of(Role.EDITOR)), Map.entry("편저", List.of(Role.EDITOR)),
			Map.entry("편역", List.of(Role.EDITOR)), Map.entry("엮은이", List.of(Role.EDITOR)),
			Map.entry("감수", List.of(Role.OTHER)), Map.entry("기획", List.of(Role.OTHER)),
			Map.entry("사진", List.of(Role.OTHER)), Map.entry("원작", List.of(Role.OTHER)),
			Map.entry("해설", List.of(Role.OTHER)), Map.entry("낭독", List.of(Role.OTHER)));

	private static final String ROLE_ALTERNATION = ROLE_WORDS.keySet()
		.stream()
		.sorted((a, b) -> b.length() - a.length())
		.map(Pattern::quote)
		.reduce((a, b) -> a + "|" + b)
		.orElseThrow();

	/** Role word at the end: after whitespace, or in brackets right after the names. */
	private static final Pattern TRAILING_ROLE = Pattern
		.compile("^(.*?)(?:\\s+\\(?(" + ROLE_ALTERNATION + ")\\)?|\\((" + ROLE_ALTERNATION + ")\\))$");

	private static final Pattern ALIAS = Pattern.compile("^(.*?)\\s*\\(([^()]*)\\)$");

	/** "외", "외 2명", "외2인" at the end of a name. */
	private static final Pattern OTHERS = Pattern.compile("\\s*외(?:\\s*\\d+\\s*(?:명|인))?$");

	private static final Pattern HANGUL = Pattern.compile("[가-힣]");

	private Contributors() {
	}

	public static List<Contributor> parse(String raw) {
		List<Contributor> people = new ArrayList<>();
		if (raw == null || raw.isBlank()) {
			return people;
		}
		int position = 0;
		for (String part : CatalogText.nfkc(raw).split("/")) {
			String text = OTHERS.matcher(part.strip()).replaceFirst("").strip();
			// a part with Hangul may list several people, each with its own role ("A 저자, B 번역") or one role
			// after the last ("A, B 공저"); a name without a role takes the role of the next one that has it
			String[] items = HANGUL.matcher(text).find() ? text.split(",") : new String[] { text };
			List<String> names = new ArrayList<>();
			List<List<Role>> roles = new ArrayList<>();
			for (String item : items) {
				String candidate = OTHERS.matcher(item.strip()).replaceFirst("").strip();
				Matcher role = TRAILING_ROLE.matcher(candidate);
				if (role.matches()) {
					String word = role.group(2) != null ? role.group(2) : role.group(3);
					names.add(role.group(1).strip());
					roles.add(ROLE_WORDS.get(word));
				}
				else {
					names.add(candidate);
					roles.add(null);
				}
			}
			List<Role> next = List.of(Role.UNKNOWN);
			for (int i = roles.size() - 1; i >= 0; i--) {
				if (roles.get(i) == null) {
					roles.set(i, next);
				}
				else {
					next = roles.get(i);
				}
			}
			for (int i = 0; i < names.size(); i++) {
				Name person = name(names.get(i));
				if (person == null) {
					continue;
				}
				for (Role r : roles.get(i)) {
					people.add(person.as(r, position));
				}
				position++;
			}
		}
		return people;
	}

	private static Name name(String candidate) {
		String name = OTHERS.matcher(candidate.strip()).replaceFirst("").strip();
		String alias = null;
		Matcher bracket = ALIAS.matcher(name);
		if (bracket.matches() && !bracket.group(1).isBlank()) {
			name = bracket.group(1).strip();
			alias = bracket.group(2).strip();
			if (alias.isEmpty()) {
				alias = null;
			}
		}
		if (name.isEmpty() || name.equals("외")) {
			return null;
		}
		return new Name(name, alias);
	}

	/** A name before its role is known. */
	private record Name(String name, String alias) {

		Contributor as(Role role, int position) {
			String nameNorm = CatalogText.norm(name) + (alias == null ? "" : CatalogText.norm(alias));
			return new Contributor(name, alias, nameNorm, CatalogText.nameExact(name), role, position);
		}

	}

}
