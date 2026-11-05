import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * Learns the final address of each library's search page the way the service calls it (docs/libraries/library-maintenance.md
 * 4-7 step 1): same JDK and TLS as CheckKyoboSites, connect 5s / read 5s, same User-Agent, redirects followed by hand
 * (at most 3). One title search "사랑" per library, built like the adapter's searchUri. Libraries on the same host are
 * asked one at a time, 1.1s apart; at most 12 hosts at once.
 *
 * Usage (run from the repository root with the service JDK 25):
 *   java scripts/library/CheckFinalOrigins.java <input.tsv>
 * input.tsv: "id<TAB>platform_type<TAB>base_url" per line.
 * Output (stdout, TSV): id, platform, listed base_url, state, new base_url, hops
 *   state: SAME (no redirect) | MOVED_TO (redirected to the same search path: new base_url is filled)
 *          | MOVED_ELSEWHERE (final path is another page) | HTTP_<code> | ERROR (exception text in new base_url column)
 */
public class CheckFinalOrigins {

	record Row(String id, String platform, String baseUrl) {
	}

	public static void main(String[] args) throws Exception {
		String current = Security.getProperty("jdk.tls.disabledAlgorithms");
		Security.setProperty("jdk.tls.disabledAlgorithms", Arrays.stream(current.split(","))
			.map(String::strip)
			.filter(entry -> !entry.equals("TLS_RSA_*"))
			.collect(Collectors.joining(", ")));
		HttpClient client = HttpClient.newBuilder()
			.sslContext(CheckKyoboSites.libraryTls())
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		Map<String, List<Row>> byHost = new LinkedHashMap<>();
		for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
			if (line.isBlank()) {
				continue;
			}
			String[] cols = line.split("\t");
			Row row = new Row(cols[0].strip(), cols[1].strip(), cols[2].strip());
			byHost.computeIfAbsent(URI.create(row.baseUrl()).getHost(), h -> new ArrayList<>()).add(row);
		}
		Map<String, String> results = new ConcurrentHashMap<>();
		Semaphore hosts = new Semaphore(12);
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (List<Row> rows : byHost.values()) {
				pool.submit(() -> {
					hosts.acquireUninterruptibly();
					try {
						for (Row row : rows) {
							String result = check(client, row);
							results.put(row.id(), row.id() + "\t" + row.platform() + "\t" + row.baseUrl() + "\t" + result);
							System.err.println(results.get(row.id()));
							Thread.sleep(1100);
						}
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					finally {
						hosts.release();
					}
				});
			}
		}
		byHost.values().stream().flatMap(List::stream).forEach(row -> System.out.println(results.get(row.id())));
	}

	/** The search path and query after base_url, as the adapters build them (title "사랑"). */
	static String probe(Row row) {
		String utf8 = "%EC%82%AC%EB%9E%91";
		String eucKr = "%BB%E7%B6%FB";
		return switch (row.platform()) {
			case "BOOKCUBE_FX" -> "/xml/productSearch/?keyword=" + utf8 + "&keyoption2=1&itemCount=20&page=1";
			case "KYOBO_V1" -> "/elibrary-front/search/searchList.ink?schClst=ctts&schDvsn=000&schTxt=" + utf8
					+ "&recordCount=10&pageIndex=1";
			case "KYOBO_SUBSCRIPTION_V1" -> "/search/searchList.ink?schClst=ctts&schDvsn=000&schTxt=" + utf8
					+ "&recordCount=10&pageIndex=1";
			case "YES24_V2" -> "/search/?src_key=" + utf8 + "&srch_order=title&page_num=1&sort=title";
			case "YES24_V1", "YES24_V3" -> "/search/?src_key=" + eucKr + "&srch_order=title&page_num=1&sort=title";
			default -> null;
		};
	}

	static String check(HttpClient client, Row row) throws InterruptedException {
		String base = row.baseUrl().replaceAll("/+$", "");
		String suffix;
		if (row.platform().equals("ALADIN_V1")) {
			URI b = URI.create(base);
			String nav = b.getRawQuery();
			base = b.getScheme() + "://" + b.getRawAuthority();
			suffix = "/sub/sub.htm?" + nav + "&code=search&search_item=title&search_order=%EC%82%AC%EB%9E%91&view_level=1";
		}
		else {
			suffix = probe(row);
			if (suffix == null) {
				return "SKIPPED\t\t";
			}
		}
		String url = base + suffix;
		List<String> hops = new ArrayList<>();
		try {
			HttpResponse<Void> response = null;
			for (int hop = 0; hop < 4; hop++) {
				response = client.send(HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(5))
					.header("User-Agent", CheckKyoboSites.USER_AGENT)
					.GET()
					.build(), HttpResponse.BodyHandlers.discarding());
				if (response.statusCode() / 100 != 3) {
					break;
				}
				url = URI.create(url).resolve(response.headers().firstValue("Location").orElse("")).toString();
				hops.add(response.statusCode() + ">" + url);
				Thread.sleep(1100);
			}
			if (response.statusCode() / 100 == 3) {
				return "HTTP_" + response.statusCode() + "\t\t" + String.join(" ", hops);
			}
			if (hops.isEmpty()) {
				return (response.statusCode() == 200 ? "SAME" : "HTTP_" + response.statusCode()) + "\t\t";
			}
			String finalBase = url.endsWith(suffix) ? url.substring(0, url.length() - suffix.length()) : null;
			if (finalBase == null && row.platform().equals("ALADIN_V1")) {
				URI f = URI.create(url);
				if (f.getPath().equals("/sub/sub.htm")) {
					finalBase = f.getScheme() + "://" + f.getRawAuthority() + "/?" + URI.create(row.baseUrl()).getRawQuery();
				}
			}
			else if (finalBase != null && row.platform().equals("ALADIN_V1")) {
				finalBase = finalBase + "/?" + URI.create(row.baseUrl()).getRawQuery();
			}
			String state = finalBase == null ? "MOVED_ELSEWHERE" : "MOVED_TO";
			if (response.statusCode() != 200) {
				state = "HTTP_" + response.statusCode();
			}
			return state + "\t" + (finalBase == null ? "" : finalBase) + "\t" + String.join(" ", hops);
		}
		catch (Exception ex) {
			return "ERROR\t" + ex.getClass().getSimpleName() + ": "
					+ String.valueOf(ex.getMessage()).replace('\t', ' ').replace('\n', ' ') + "\t";
		}
	}

}
