package com.chaekcheck.sitecontent.internal.web;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.sitecontent.internal.Today;
import com.chaekcheck.sitecontent.internal.VersionDate;
import com.chaekcheck.sitecontent.internal.document.DocumentStore;
import com.chaekcheck.sitecontent.internal.web.SitecontentErrors.NotFoundException;
import com.chaekcheck.sitecontent.internal.web.SitecontentResponses.ConsentItem;
import com.chaekcheck.sitecontent.internal.web.SitecontentResponses.Document;
import com.chaekcheck.sitecontent.internal.web.SitecontentResponses.DocumentVersion;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/documents/{documentKey}} (plan cycle 16 design-delta "API"). No login: the terms stay readable
 * when the login feature is off, only the web route {@code /terms} is hidden (this module does not know auth).
 */
@RestController
class DocumentController {

	private static final TypeReference<List<ConsentItem>> CONSENT_ITEMS = new TypeReference<>() {
	};

	private final DocumentStore store;

	private final Today today;

	private final JsonMapper json;

	DocumentController(DocumentStore store, Today today, JsonMapper json) {
		this.store = store;
		this.today = today;
		this.json = json;
	}

	@GetMapping("/api/v1/documents/{documentKey}")
	Document document(@PathVariable String documentKey, @RequestParam(required = false) String version) {
		Key key = Key.of(documentKey);
		Integer requested = SitecontentParams.positive("version", version, null);
		LocalDate day = today.get();
		List<VersionDate> versions = store.versions(key.documentKey, day);
		if (versions.isEmpty()) {
			throw NotFoundException.version();
		}
		int current = versions.getFirst().number();
		int shown = requested == null ? current : requested;
		DocumentStore.Version row = store.version(key.documentKey, shown, day)
			.orElseThrow(NotFoundException::version);
		List<ConsentItem> consentItems = row.consentItemsJson() == null ? null
				: json.readValue(row.consentItemsJson(), CONSENT_ITEMS);
		return new Document(key.path,
				new DocumentVersion(row.number(), row.effectiveOn(), row.number() == current, row.body(),
						row.changeSummary(), consentItems),
				SitecontentResponses.versionInfos(versions));
	}

	/** The four keys [확정]: the address form and the {@code document_key} value. */
	enum Key {

		ABOUT("about"), TERMS_OF_SERVICE("terms-of-service"), PRIVACY_POLICY("privacy-policy"),
		PRIVACY_COLLECTION("privacy-collection");

		final String path;

		final String documentKey;

		Key(String path) {
			this.path = path;
			this.documentKey = name();
		}

		static Key of(String path) {
			return Arrays.stream(values())
				.filter(key -> key.path.equals(path))
				.findFirst()
				.orElseThrow(NotFoundException::document);
		}

	}

}
