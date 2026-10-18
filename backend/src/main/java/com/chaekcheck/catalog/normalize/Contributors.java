package com.chaekcheck.catalog.normalize;

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
 * <li>Marks are removed first ({@link #stripMarks}, ADR-0113).</li>
 * <li>A role word with the next name stuck to it ("루이스 캐럴 저이소연 역") is split by a narrow rule, up to a third
 * person, unless a role word is already written on its own before it (ADR-0113).</li>
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

	/**
	 * Marks removed from author texts (R1-1, R1-2, cycle 19 retro decision #5): angle brackets in every width
	 * ({@code < > ＜ ＞ ﹤ ﹥}, all {@code < >} after NFKC), the marks of {@code 〈 〉 「 」 《 》} and square brackets
	 * {@code [ ] ［ ］}, keeping the text inside ("KBS [슈퍼차이나] 제작팀" → "KBS 슈퍼차이나 제작팀"). A role word in
	 * square brackets is handled first by {@link #BRACKET_ROLE}. Rule: docs/maintenance/rules-index.md "저자 기호 떼기".
	 */
	private static final Pattern MARKS = Pattern.compile("[<>＜＞﹤﹥〈〉〈〉「」｢｣《》\\[\\]［］]");

	/**
	 * A role word in square brackets ("존 버거 [지음]", "금장태, 고광직[공저]", "[ 지음 ]"; cycle 19 retro decision #5).
	 * {@link #stripMarks} turns it into the role word after a space, and when more text follows ("정도전[저]정병철[편]")
	 * a comma as well, so {@link #parse} reads it like a role word written after the names.
	 */
	private static final Pattern BRACKET_ROLE = Pattern
		.compile("[\\[［]\\s*(" + ROLE_ALTERNATION + ")\\s*[\\]］]");

	/** Characters after a bracketed role word that already separate the next person. */
	private static final String SEPARATORS = ",;/";

	/**
	 * A lone role word closing the text after a bracketed role word ("김삼웅 [편] 저", "K. 만하임 [저] 임석진 [역] 저": the
	 * site's field-wide "저" repeated after roles written per person; 193 of the 231 bracketed-role texts of the
	 * 2026-10-12 copy). {@link #stripMarks} drops it.
	 */
	private static final Pattern TRAILING_LONE_ROLE = Pattern.compile("^(?:" + ROLE_ALTERNATION + ")\\s*$");

	private static final Pattern SPACES = Pattern.compile("\\s{2,}");

	/**
	 * Narrow rule for a role word with the next name stuck to it (R1-3): names, a space, an author word, a Hangul name
	 * of 2 to 4 letters, a space, a translator or illustrator word; then maybe once more a name of 2 to 4 letters and
	 * a third role word. "편", "그림" and "역" are not first words (family name 편, 그림 형제). Rule:
	 * docs/maintenance/rules-index.md "붙은 역할 낱말".
	 */
	private static final Pattern ATTACHED_ROLES = Pattern
		.compile("^(.+?)\\s+(저자|저|지은이|지음|글)([가-힣]{2,4})\\s+(옮긴이|옮김|번역|역자|역|그림)"
				+ "(?:([가-힣]{2,4})\\s+(감수|편집|엮음|편|기획|해설|그림|사진|옮김|역))?$");

	/**
	 * Guard of the narrow rule: a role word written on its own before the stuck word ("… 지음, 다니엘라 저메인 역")
	 * means the stuck letters belong to a name. Rule: docs/maintenance/rules-index.md "붙은 역할 낱말".
	 */
	private static final Pattern SEPARATE_ROLE = Pattern
		.compile("(?<![가-힣A-Za-z])(?:" + ROLE_ALTERNATION + ")(?![가-힣A-Za-z])");

	/** People after the first {@code ;} (translators and so on in library records: "A 지음 ;B 옮김"). */
	private static final Pattern AFTER_SEMICOLON = Pattern.compile(";.*$");

	/** A role label in front of the names: "지은이: 오서", "공저: A,B". */
	private static final Pattern LEADING_ROLE_LABEL = Pattern.compile("^[가-힣]{1,4}\\s*:\\s*");

	/**
	 * Work words after an author's name in library records that are not role words of the collected data
	 * ("애너 퀸들런 소설"); removed only for {@link #firstNameExact}.
	 */
	private static final Pattern TRAILING_WORK_WORD = Pattern
		.compile("\\s+(?:장편소설|소설집|소설|시집|산문집|산문|에세이)$");

	private Contributors() {
	}

	/**
	 * Author text without the marks of {@link #MARKS} (ADR-0113, cycle 19 retro decision #5); runs of spaces left
	 * behind become one. A role word in square brackets becomes the role word after a space ("존 버거 [지음]" → "존 버거
	 * 지음"), followed by a comma when more text follows ("정도전[저]정병철[편]" → "정도전 저, 정병철 편"), and a lone
	 * role word closing the text after it is dropped ("김삼웅 [편] 저" → "김삼웅 편"); other brackets lose only their
	 * marks ("KBS [슈퍼차이나] 제작팀" → "KBS 슈퍼차이나 제작팀"). Text without marks comes back unchanged. Null stays
	 * null.
	 */
	public static String stripMarks(String raw) {
		if (raw == null) {
			return null;
		}
		if (!MARKS.matcher(raw).find()) {
			return raw;
		}
		String text = MARKS.matcher(unbracketRoles(raw)).replaceAll("");
		return SPACES.matcher(text).replaceAll(" ").strip();
	}

	/**
	 * Each {@link #BRACKET_ROLE} replaced by its role word; a comma is added when more text follows, and a
	 * {@link #TRAILING_LONE_ROLE} after the last one is dropped.
	 */
	private static String unbracketRoles(String raw) {
		Matcher role = BRACKET_ROLE.matcher(raw);
		StringBuilder out = new StringBuilder();
		boolean cutTail = false;
		while (role.find()) {
			int next = role.end();
			while (next < raw.length() && Character.isWhitespace(raw.charAt(next))) {
				next++;
			}
			String rest = raw.substring(next);
			cutTail = TRAILING_LONE_ROLE.matcher(rest).matches();
			boolean more = !cutTail && !rest.isEmpty() && SEPARATORS.indexOf(rest.charAt(0)) < 0;
			role.appendReplacement(out, Matcher.quoteReplacement(" " + role.group(1) + (more ? ", " : "")));
		}
		if (!cutTail) {
			role.appendTail(out);
		}
		return out.toString();
	}

	/**
	 * Author text for the book's author field (R1-5): {@link #stripMarks}, then a space after each stuck role word that
	 * {@link #parse} splits ("밥 프록터 저이재경 역조성희 감수" → "밥 프록터 저 이재경 역 조성희 감수"). Texts the guard
	 * keeps and texts without stuck role words stay as they are.
	 */
	public static String displayText(String raw) {
		String text = stripMarks(raw);
		if (text == null || text.isBlank()) {
			return text;
		}
		String nfkc = CatalogText.nfkc(text);
		List<Attached> found = attached(nfkc);
		if (found.isEmpty() || nfkc.length() != text.length()) {
			// offsets come from the NFKC text; a text NFKC changes in length is left unspaced (rare, safe side)
			return text;
		}
		StringBuilder out = new StringBuilder(text);
		for (int i = found.size() - 1; i >= 0; i--) {
			Attached a = found.get(i);
			if (a.match().group(5) != null) {
				out.insert(a.start() + a.match().start(5), ' ');
			}
			out.insert(a.start() + a.match().start(3), ' ');
		}
		return out.toString();
	}

	/**
	 * First person of an author text from a library record (ADR-0090), as {@link CatalogText#nameExact}: the part
	 * before the first {@code ;}, a leading role label ("지은이:") and a trailing work word ("소설") removed, then the
	 * first name {@link #parse} finds. Null when there is none.
	 */
	public static String firstNameExact(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String text = AFTER_SEMICOLON.matcher(CatalogText.nfkc(raw)).replaceFirst("").strip();
		text = LEADING_ROLE_LABEL.matcher(text).replaceFirst("");
		text = TRAILING_WORK_WORD.matcher(text).replaceFirst("");
		List<Contributor> people = parse(text);
		if (people.isEmpty()) {
			return null;
		}
		String exact = people.getFirst().nameExact();
		return exact.isEmpty() ? null : exact;
	}

	public static List<Contributor> parse(String raw) {
		List<Contributor> people = new ArrayList<>();
		String text = raw == null ? null : stripMarks(CatalogText.nfkc(raw));
		if (text == null || text.isBlank()) {
			return people;
		}
		int position = 0;
		for (List<Item> part : parts(text)) {
			// a part with Hangul may list several people, each with its own role ("A 저자, B 번역") or one role
			// after the last ("A, B 공저"); a name without a role takes the role of the next one that has it
			List<String> names = new ArrayList<>();
			List<List<Role>> roles = new ArrayList<>();
			for (Item item : part) {
				String candidate = item.candidate();
				Matcher stuck = stuck(text, item);
				if (stuck != null) {
					names.add(stuck.group(1).strip());
					roles.add(ROLE_WORDS.get(stuck.group(2)));
					names.add(stuck.group(3));
					roles.add(ROLE_WORDS.get(stuck.group(4)));
					if (stuck.group(5) != null) {
						names.add(stuck.group(5));
						roles.add(ROLE_WORDS.get(stuck.group(6)));
					}
					continue;
				}
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

	/**
	 * The items of a text as {@link #parse} reads them: parts split on {@code /}, a part with Hangul split on commas
	 * too; each item stripped and without a trailing {@code 외}, with its offset in the text.
	 */
	private static List<List<Item>> parts(String text) {
		List<List<Item>> parts = new ArrayList<>();
		int partStart = 0;
		for (String part : text.split("/", -1)) {
			List<Item> items = new ArrayList<>();
			String whole = OTHERS.matcher(part.strip()).replaceFirst("").strip();
			if (HANGUL.matcher(whole).find()) {
				int itemStart = partStart;
				for (String piece : part.split(",", -1)) {
					items.add(item(piece, itemStart));
					itemStart += piece.length() + 1;
				}
			}
			else {
				items.add(item(part, partStart));
			}
			parts.add(items);
			partStart += part.length() + 1;
		}
		return parts;
	}

	private static Item item(String piece, int start) {
		String stripped = piece.strip();
		String candidate = OTHERS.matcher(stripped).replaceFirst("").strip();
		return new Item(candidate, start + (stripped.isEmpty() ? 0 : piece.indexOf(stripped)));
	}

	/** The narrow-rule match of an item (R1-3); null when it does not match or the guard keeps it. */
	private static Matcher stuck(String text, Item item) {
		Matcher stuck = ATTACHED_ROLES.matcher(item.candidate());
		if (!stuck.matches()) {
			return null;
		}
		String before = text.substring(0, item.start() + stuck.start(2));
		return SEPARATE_ROLE.matcher(before).find() ? null : stuck;
	}

	/** Items of {@code text} the narrow rule splits. */
	private static List<Attached> attached(String text) {
		List<Attached> found = new ArrayList<>();
		for (List<Item> part : parts(text)) {
			for (Item item : part) {
				Matcher stuck = stuck(text, item);
				if (stuck != null) {
					found.add(new Attached(item.start(), stuck));
				}
			}
		}
		return found;
	}

	/** One item of a part: the text to read and where it starts in the whole text. */
	private record Item(String candidate, int start) {
	}

	private record Attached(int start, Matcher match) {
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
