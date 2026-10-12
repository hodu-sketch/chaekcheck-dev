package com.chaekcheck.collection.internal.libraryinsert;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A rules folder for tests: every file with its header, rows added per test (columns: rules/README.md). */
final class RulesFolder {

	private final Map<String, List<String>> files = new LinkedHashMap<>();

	private String surveyedOn = "2026-10-05";

	RulesFolder() {
		header("demo_or_closed.tsv", "collection_id\tname\tdecided_on\tsource");
		header("same_site.tsv", "collection_id\tname\tlibrary_id\tdecided_on\tnote");
		header("same_host_kept.tsv", "collection_id\tname\tdecided_on\tnote");
		header("gone_known.tsv", "collection_id\tname\tdecided_on\tchecked_on\tstate");
		header("order_no_allowed.tsv", "collection_id\torder_no\tdecided_on\tnote");
		header("library_kinds.tsv", "collection_id\tname\tkind\tsure\tnote");
		header("kyobo_probe.tsv", "site_url\tname\tcategory\tmodel\tlending_model\tchecked_at");
		header("final_origins.tsv", "listed_base_url\tbase_url\tchecked_on\tservice_id");
		header("name_overrides.tsv", "library_id\told_name\tnew_name\tchanged_on\tsource_url\tchecked_on");
		header("bookcube_volume.tsv", "collection_id\tname\tbase_url\ttotal_item\ttotal_volume\terror\tchecked_on");
		header("public_join.tsv", "collection_id\tname\tis_public\teligibility\tbasis\tscope\tvisit_required\t"
				+ "join_method\textra_condition\tjoin_caution\tquote\tsource_url\tchecked\tmemo\tdecision\trules\t"
				+ "eligibility_summary");
	}

	private void header(String file, String header) {
		files.put(file, new ArrayList<>(List.of(header)));
	}

	RulesFolder row(String file, String... cells) {
		files.get(file).add(String.join("\t", cells));
		return this;
	}

	RulesFolder kind(long collectionId, String kind) {
		return row("library_kinds.tsv", String.valueOf(collectionId), "", kind, "", "");
	}

	/** The listed address is the final one (a new library needs a row). */
	RulesFolder origin(String listed, String finalUrl) {
		return row("final_origins.tsv", listed, finalUrl, "2026-10-12", "");
	}

	RulesFolder origin(String url) {
		return origin(url, url);
	}

	RulesFolder publicJoin(long collectionId, String eligibility, String rules, String summary) {
		return row("public_join.tsv", String.valueOf(collectionId), "", "true", eligibility, "", "", "false",
				"홈페이지 회원가입", "", "", "", "https://join.test/" + collectionId, "", "", "", rules, summary);
	}

	RulesFolder surveyedOn(String date) {
		this.surveyedOn = date;
		return this;
	}

	Path write(Path dir) {
		try {
			Files.createDirectories(dir);
			for (Map.Entry<String, List<String>> file : files.entrySet()) {
				Files.writeString(dir.resolve(file.getKey()), String.join("\n", file.getValue()) + "\n",
						StandardCharsets.UTF_8);
			}
			Files.writeString(dir.resolve("join_surveyed_on.txt"), surveyedOn + "\n", StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return dir;
	}

	RuleSet read(Path dir) {
		return RuleSet.read(write(dir));
	}

}
