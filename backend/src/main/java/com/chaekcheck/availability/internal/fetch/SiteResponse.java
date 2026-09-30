package com.chaekcheck.availability.internal.fetch;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

import org.springframework.http.MediaType;

/**
 * What a library site answered: status, Content-Type, Location and the raw body bytes (ADR-0015). Adapters decode
 * the bytes themselves because some sites are EUC-KR.
 * @param contentType the Content-Type header, or null
 * @param location the Location header of a redirect, or null
 * @param via how the page was fetched, for logs: {@code direct} or the fetch server id
 */
public record SiteResponse(int status, String contentType, String location, byte[] body, String via) {

	public SiteResponse {
		body = body == null ? new byte[0] : body;
	}

	public boolean isSuccess() {
		return status / 100 == 2;
	}

	/** Charset from Content-Type, or null when the header does not name one. */
	public Charset declaredCharset() {
		try {
			MediaType type = contentType == null ? null : MediaType.parseMediaType(contentType);
			return type == null ? null : type.getCharset();
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof SiteResponse that && status == that.status
				&& Objects.equals(contentType, that.contentType)
				&& Objects.equals(location, that.location) && Arrays.equals(body, that.body)
				&& Objects.equals(via, that.via);
	}

	@Override
	public int hashCode() {
		return Objects.hash(status, contentType, location, Arrays.hashCode(body), via);
	}

	@Override
	public String toString() {
		return "SiteResponse[status=" + status + ", contentType=" + contentType + ", location=" + location + ", body="
				+ body.length + " bytes, via=" + via + "]";
	}

}
