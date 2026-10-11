package com.chaekcheck.popular.internal.fetch;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** An answer of the site. Redirects are not followed, so a 302 arrives as it is. */
public record KpipaResponse(int status, String contentType, byte[] body) {

	/** The body as text, in the charset of the Content-Type or UTF-8 (the site's pages are UTF-8). */
	public String text() {
		Charset charset = StandardCharsets.UTF_8;
		if (contentType != null) {
			for (String part : contentType.split(";")) {
				String p = part.strip().toLowerCase(Locale.ROOT);
				if (p.startsWith("charset=")) {
					try {
						charset = Charset.forName(p.substring("charset=".length()).replace("\"", ""));
					}
					catch (IllegalArgumentException ex) {
						// unknown charset: UTF-8
					}
				}
			}
		}
		return new String(body, charset);
	}

}
