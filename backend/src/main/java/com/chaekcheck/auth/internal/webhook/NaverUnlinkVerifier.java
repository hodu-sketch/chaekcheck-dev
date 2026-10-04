package com.chaekcheck.auth.internal.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Checks a Naver "연결끊기 Callback" and reads the user's id from it, as Naver's guide section 4.4 describes
 * (plan research.md 3절, all [확정]):
 * <ul>
 * <li>key: the first 16 bytes of MD5(client secret), for both the signature and the encryption</li>
 * <li>signature: HmacSHA256 over {@code clientId=<v>&encryptUniqueId=<v>&timestamp=<v>}, URL-safe Base64</li>
 * <li>{@code encryptUniqueId}: Base64 of (16-byte IV + AES-128/CBC/PKCS5Padding ciphertext)</li>
 * </ul>
 * Refused (empty): another client id, a wrong signature, or a {@code timestamp} more than 5 minutes from now [확정]
 * (설계 검토 10). Comparisons take the same time whatever the input ({@link MessageDigest#isEqual}, C19).
 */
final class NaverUnlinkVerifier {

	static final Duration MAX_CLOCK_DIFFERENCE = Duration.ofMinutes(5);

	private final byte[] clientId;

	private final byte[] key;

	NaverUnlinkVerifier(String clientId, String clientSecret) {
		this.clientId = clientId.getBytes(StandardCharsets.UTF_8);
		this.key = Arrays.copyOf(md5(clientSecret), 16);
	}

	/** The Naver user id ({@code response.id} at login) when the request is genuine. */
	Optional<String> verify(String clientId, String encryptUniqueId, String timestamp, String signature, Instant now) {
		if (clientId == null || encryptUniqueId == null || timestamp == null || signature == null) {
			return Optional.empty();
		}
		if (!MessageDigest.isEqual(this.clientId, clientId.getBytes(StandardCharsets.UTF_8))) {
			return Optional.empty();
		}
		long seconds;
		try {
			seconds = Long.parseLong(timestamp);
		}
		catch (NumberFormatException ex) {
			return Optional.empty();
		}
		if (Duration.between(Instant.ofEpochSecond(seconds), now).abs().compareTo(MAX_CLOCK_DIFFERENCE) > 0) {
			return Optional.empty();
		}
		byte[] expected = urlSafe(hmac("clientId=" + clientId + "&encryptUniqueId=" + encryptUniqueId + "&timestamp="
				+ timestamp)).getBytes(StandardCharsets.US_ASCII);
		if (!MessageDigest.isEqual(expected, normalized(signature).getBytes(StandardCharsets.US_ASCII))) {
			return Optional.empty();
		}
		return decrypt(encryptUniqueId);
	}

	private Optional<String> decrypt(String encryptUniqueId) {
		try {
			byte[] data = Base64.getUrlDecoder().decode(normalized(encryptUniqueId));
			if (data.length <= 16) {
				return Optional.empty();
			}
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
					new IvParameterSpec(Arrays.copyOfRange(data, 0, 16)));
			String id = new String(cipher.doFinal(data, 16, data.length - 16), StandardCharsets.UTF_8);
			return id.isBlank() ? Optional.empty() : Optional.of(id);
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	private byte[] hmac(String message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 is part of every Java runtime", ex);
		}
	}

	private static byte[] md5(String value) {
		try {
			return MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("MD5 is part of every Java runtime", ex);
		}
	}

	private static String urlSafe(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * URL-safe Base64 without padding, whatever form came in: the guide's sample uses URL-safe Base64, and a form
	 * value may arrive with standard characters or padding.
	 */
	private static String normalized(String base64) {
		return base64.trim().replace('+', '-').replace('/', '_').replace("=", "");
	}

}
