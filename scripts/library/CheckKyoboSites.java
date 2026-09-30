import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks KYOBO e-library sites (regular and subscription) the way the service calls them: same JDK, same TLS
 * setting (TLS_RSA_* allowed; JDK roots plus the missing intermediates in
 * backend/src/main/resources/tls/library-intermediates, like LibrarySiteTls), connect 5s / read 5s, same User-Agent, redirects followed by hand (at most 3) to learn
 * the final origin. One request at a time, 1.1s apart.
 *
 * Usage (run from the repository root with the service JDK 25):
 *   java scripts/library/CheckKyoboSites.java <input.tsv> [search|holdings]
 * input.tsv: "name<TAB>url" per line (url = site origin as listed; http is fine, redirects are followed).
 * search   (default): title search "사랑", recordCount=10 -> is the site alive and parseable?
 * holdings           : blank search over every field -> total count = ranking value (library.holdings_count).
 * Output (stdout, TSV): name, listed url, KYOBO|SUB, state, count, final origin, redirect hops
 *   state: OK | HTTP_<code> | MOVED (final path is not the search page) | OTHER (page without the result list)
 *          | ERROR (exception text in the count column)
 * See docs/library-maintenance.md.
 */
public class CheckKyoboSites {

	static final Pattern TOTAL = Pattern.compile("총\\s*([\\d,]+)\\s*개");

	static final String USER_AGENT = "chaekcheck/0.0.1"; // keep equal to chaekcheck.availability.user-agent

	public static void main(String[] args) throws Exception {
		String current = Security.getProperty("jdk.tls.disabledAlgorithms");
		Security.setProperty("jdk.tls.disabledAlgorithms", Arrays.stream(current.split(","))
			.map(String::strip)
			.filter(entry -> !entry.equals("TLS_RSA_*"))
			.collect(Collectors.joining(", ")));
		boolean holdings = args.length > 1 && args[1].equals("holdings");
		HttpClient client = HttpClient.newBuilder()
			.sslContext(libraryTls())
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
			if (line.isBlank()) {
				continue;
			}
			String[] cols = line.split("\t");
			String name = cols[0];
			String origin = cols[1].strip().replaceFirst("^http://https://", "https://").replaceAll("/+$", "");
			boolean subscription = URI.create(origin).getHost().endsWith("dkyobobook.co.kr");
			String path = (subscription ? "" : "/elibrary-front") + "/search/searchList.ink";
			String query = holdings ? "?schClst=all&schDvsn=000&schTxt=%20&recordCount=10&pageIndex=1"
					: "?schClst=ctts&schDvsn=000&schTxt=%EC%82%AC%EB%9E%91&recordCount=10&pageIndex=1";
			String url = origin + path + query;
			List<String> hops = new ArrayList<>();
			String result;
			try {
				HttpResponse<byte[]> response = null;
				for (int hop = 0; hop < 4; hop++) {
					response = client.send(HttpRequest.newBuilder(URI.create(url))
						.timeout(Duration.ofSeconds(5))
						.header("User-Agent", USER_AGENT)
						.GET()
						.build(), HttpResponse.BodyHandlers.ofByteArray());
					Thread.sleep(1100);
					if (response.statusCode() / 100 != 3) {
						break;
					}
					url = URI.create(url).resolve(response.headers().firstValue("Location").orElse("")).toString();
					hops.add(response.statusCode() + ">" + url);
				}
				URI finalUri = URI.create(url);
				String finalOrigin = finalUri.getScheme() + "://" + finalUri.getRawAuthority();
				String body = new String(response.body(), StandardCharsets.UTF_8);
				Matcher total = TOTAL.matcher(body);
				String state;
				if (response.statusCode() != 200) {
					state = "HTTP_" + response.statusCode() + "\t";
				}
				else if (!finalUri.getPath().endsWith(path)) {
					state = "MOVED\t";
				}
				else if (body.contains("book_resultTxt") && total.find()) {
					state = "OK\t" + total.group(1).replace(",", "");
				}
				else if (body.contains("book_resultTxt") && body.contains("검색 결과가 없습니다")) {
					state = "OK\t0";
				}
				else {
					state = "OTHER\t";
				}
				result = state + "\t" + finalOrigin + "\t" + String.join(" ", hops);
			}
			catch (Exception ex) {
				result = "ERROR\t" + ex.getClass().getSimpleName() + ": "
						+ String.valueOf(ex.getMessage()).replace('\t', ' ').replace('\n', ' ') + "\t\t";
				Thread.sleep(1100);
			}
			System.out.println(name + "\t" + origin + "\t" + (subscription ? "SUB" : "KYOBO") + "\t" + result);
			System.out.flush();
		}
	}

	/** Same trust as the service (LibrarySiteTls): JDK roots only, missing intermediates as chain material. */
	static SSLContext libraryTls() throws Exception {
		TrustManagerFactory jdk = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		jdk.init((KeyStore) null);
		Set<TrustAnchor> anchors = new HashSet<>();
		for (X509Certificate root : ((X509TrustManager) jdk.getTrustManagers()[0]).getAcceptedIssuers()) {
			anchors.add(new TrustAnchor(root, null));
		}
		CertificateFactory factory = CertificateFactory.getInstance("X.509");
		List<X509Certificate> intermediates = new ArrayList<>();
		try (var files = Files.list(Path.of("backend/src/main/resources/tls/library-intermediates"))) {
			for (Path file : files.filter(p -> p.toString().endsWith(".pem")).toList()) {
				try (InputStream in = Files.newInputStream(file)) {
					intermediates.add((X509Certificate) factory.generateCertificate(in));
				}
			}
		}
		PKIXBuilderParameters params = new PKIXBuilderParameters(anchors, new X509CertSelector());
		params.setRevocationEnabled(false);
		params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(intermediates)));
		TrustManagerFactory pkix = TrustManagerFactory.getInstance("PKIX");
		pkix.init(new CertPathTrustManagerParameters(params));
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(null, pkix.getTrustManagers(), null);
		return context;
	}

}
