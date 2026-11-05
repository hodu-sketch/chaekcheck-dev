import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks YES24 e-library sites (current screen YES24_V2, UTF-8, and previous generation YES24_V1, EUC-KR) the way
 * the service calls them: same JDK, same TLS setting (TLS_RSA_* allowed), connect 5s / read 5s, same User-Agent,
 * redirects followed by hand (at most 3) to learn the final origin. One request at a time, 1.1s apart.
 *
 * Usage (run from the repository root with the service JDK 25):
 *   java scripts/library/CheckYes24Sites.java <input.tsv> [search|holdings]
 * input.tsv: "name<TAB>url" per line (url = site root as listed; http is fine, redirects are followed).
 * search   (default): title search "사랑". The first request sends the query in UTF-8 (YES24_V2 URL). If the page
 *                     declares EUC-KR it is the previous screen (YES24_V1): a second request sends the query in
 *                     EUC-KR with view=10, as Yes24V1Adapter does.
 * holdings           : blank search -> total count = ranking value (library.holdings_count). One request; the blank
 *                     query needs no encoding, view=10 is added only when the site is known to be V1 (it is harmless
 *                     on V2 as well, so it is always sent).
 * Output (stdout, TSV): name, listed url, YES24_V1|YES24_V2|YES24_V3|?, state, count, final origin, redirect hops
 *   state: OK | HTTP_<code> | MOVED (final path is not the search page) | OTHER (page without the result list)
 *          (YES24_V3 = current markup in EUC-KR, found 2026-09-29; Yes24V3Adapter reads it)
 *          | ERROR (exception text in the count column)
 *   Run with -Dstdout.encoding=UTF-8 when redirecting to a file on Windows.
 * Selectors follow Yes24V1Adapter / Yes24V2Adapter: V2 "div.total em" + "div.ebook-list div.bx",
 * V1 "#content" text "전체 N 건" + "div#booklist li.line". See docs/libraries/library-maintenance.md.
 */
public class CheckYes24Sites {

	static final String USER_AGENT = "chaekcheck/0.0.1"; // keep equal to chaekcheck.availability.user-agent

	static final Charset EUC_KR = Charset.forName("EUC-KR");

	static final Pattern EUC_KR_META = Pattern.compile("charset\\s*=\\s*[\"']?euc-kr", Pattern.CASE_INSENSITIVE);

	static final Pattern V2_TOTAL = Pattern.compile("<div\\s+class=\"total\"[^>]*>.*?<em>\\s*([\\d,]+)\\s*</em>",
			Pattern.DOTALL);

	static final Pattern V2_ITEM = Pattern.compile("<div\\s+class=\"bx[\\s\"]");

	static final Pattern V1_TOTAL = Pattern.compile("전체\\s*([\\d,]+)\\s*건");

	static final Pattern V1_ITEM = Pattern.compile("<li\\s+class=\"line\"");

	static final Pattern TAG = Pattern.compile("<[^>]+>");

	static HttpClient client;

	record Fetched(HttpResponse<byte[]> response, String url, List<String> hops) {
	}

	public static void main(String[] args) throws Exception {
		String current = Security.getProperty("jdk.tls.disabledAlgorithms");
		Security.setProperty("jdk.tls.disabledAlgorithms", Arrays.stream(current.split(","))
			.map(String::strip)
			.filter(entry -> !entry.equals("TLS_RSA_*"))
			.collect(Collectors.joining(", ")));
		boolean holdings = args.length > 1 && args[1].equals("holdings");
		client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] cols = line.split("\t");
			String name = cols[0];
			String root = cols[1].strip().replaceFirst("^http://https://", "https://").replaceAll("/+$", "");
			String platform = "?";
			String result;
			try {
				String love = URLEncoder.encode("사랑", StandardCharsets.UTF_8);
				String query = holdings ? "?src_key=&srch_order=title&sort=title&view=10&page_num=1"
						: "?src_key=" + love + "&srch_order=title&sort=title&page_num=1";
				Fetched fetched = fetch(root + "/search/" + query);
				byte[] body = fetched.response().body();
				boolean v1 = EUC_KR_META.matcher(new String(body, StandardCharsets.ISO_8859_1)).find();
				platform = fetched.response().statusCode() != 200 ? "?" : v1 ? "YES24_V1" : "YES24_V2";
				if (v1 && !holdings && fetched.response().statusCode() == 200) {
					// The V1 screen finds nothing unless the query is EUC-KR; ask again at the final origin/path.
					URI finalUri = URI.create(fetched.url());
					String base = finalUri.getScheme() + "://" + finalUri.getRawAuthority() + finalUri.getRawPath();
					Fetched second = fetch(base + "?src_key=" + URLEncoder.encode("사랑", EUC_KR)
							+ "&srch_order=title&sort=title&view=10&page_num=1");
					List<String> hops = new ArrayList<>(fetched.hops());
					hops.addAll(second.hops());
					fetched = new Fetched(second.response(), second.url(), hops);
					body = fetched.response().body();
				}
				URI finalUri = URI.create(fetched.url());
				String finalOrigin = finalUri.getScheme() + "://" + finalUri.getRawAuthority();
				String state;
				int status = fetched.response().statusCode();
				if (status != 200) {
					state = "HTTP_" + status + "\t";
				}
				else if (!finalUri.getPath().endsWith("/search/")) {
					state = "MOVED\t";
				}
				else if (v1 && V2_TOTAL.matcher(new String(body, EUC_KR)).find()) {
					// Current (V2) markup served in EUC-KR = YES24_V3 (the search text must be EUC-KR).
					platform = "YES24_V3";
					state = parseV2(new String(body, EUC_KR));
				}
				else {
					state = v1 ? parseV1(new String(body, EUC_KR)) : parseV2(new String(body, StandardCharsets.UTF_8));
				}
				result = state + "\t" + finalOrigin + "\t" + String.join(" ", fetched.hops());
			}
			catch (Exception ex) {
				result = "ERROR\t" + ex.getClass().getSimpleName() + ": "
						+ String.valueOf(ex.getMessage()).replace('\t', ' ').replace('\n', ' ') + "\t\t";
				Thread.sleep(1100);
			}
			System.out.println(name + "\t" + cols[1].strip() + "\t" + platform + "\t" + result);
			System.out.flush();
		}
	}

	static Fetched fetch(String url) throws Exception {
		List<String> hops = new ArrayList<>();
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
		return new Fetched(response, url, hops);
	}

	/** div.total em + div.ebook-list div.bx (Yes24V2Adapter). */
	static String parseV2(String body) {
		Matcher total = V2_TOTAL.matcher(body);
		if (!total.find()) {
			return "OTHER\t";
		}
		int count = Integer.parseInt(total.group(1).replace(",", ""));
		int list = body.indexOf("ebook-list");
		int items = list < 0 ? 0 : (int) V2_ITEM.matcher(body.substring(list)).results().count();
		return count > 0 && items == 0 ? "OTHER\t" + count : "OK\t" + count;
	}

	/** #content text "전체 N 건" + div#booklist li.line (Yes24V1Adapter). */
	static String parseV1(String body) {
		int content = body.indexOf("id=\"content\"");
		if (content < 0) {
			return "OTHER\t";
		}
		String rest = body.substring(content);
		Matcher total = V1_TOTAL.matcher(TAG.matcher(rest).replaceAll(""));
		if (!total.find()) {
			return "OTHER\t";
		}
		int count = Integer.parseInt(total.group(1).replace(",", ""));
		int list = rest.indexOf("id=\"booklist\"");
		int items = list < 0 ? 0 : (int) V1_ITEM.matcher(rest.substring(list)).results().count();
		return count > 0 && items == 0 ? "OTHER\t" + count : "OK\t" + count;
	}

}
