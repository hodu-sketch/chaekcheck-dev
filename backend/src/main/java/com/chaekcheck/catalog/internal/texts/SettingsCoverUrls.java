package com.chaekcheck.catalog.internal.texts;

import java.util.regex.Pattern;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.CoverUrls;

/** {@link CoverUrls} from {@code chaekcheck.covers.base-url}. */
@Component
@EnableConfigurationProperties(CoverSettings.class)
class SettingsCoverUrls implements CoverUrls {

	private static final Pattern KEY = Pattern.compile("[0-9a-f]{40}");

	private final String baseUrl;

	SettingsCoverUrls(CoverSettings settings) {
		this.baseUrl = settings.baseUrl();
	}

	@Override
	public String of(String fileKey) {
		if (baseUrl.isEmpty() || fileKey == null || !KEY.matcher(fileKey).matches()) {
			return null;
		}
		return baseUrl + "/covers/" + fileKey + ".jpg";
	}

}
