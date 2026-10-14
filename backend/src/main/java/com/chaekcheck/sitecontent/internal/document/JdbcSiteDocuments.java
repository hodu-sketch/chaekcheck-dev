package com.chaekcheck.sitecontent.internal.document;

import org.springframework.stereotype.Component;

import com.chaekcheck.sitecontent.ConsentDocument;
import com.chaekcheck.sitecontent.SiteDocuments;
import com.chaekcheck.sitecontent.internal.Today;

@Component
class JdbcSiteDocuments implements SiteDocuments {

	private final DocumentStore store;

	private final Today today;

	JdbcSiteDocuments(DocumentStore store, Today today) {
		this.store = store;
		this.today = today;
	}

	@Override
	public int currentVersion(ConsentDocument document) {
		return store.versions(document.name(), today.get())
			.stream()
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No version in effect for " + document))
			.number();
	}

}
