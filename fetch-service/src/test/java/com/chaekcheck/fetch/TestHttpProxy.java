package com.chaekcheck.fetch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal HTTP proxy for tests (webshare cycle (in cycle 20)): plain {@code GET http://host:port/path} requests are forwarded, and
 * {@code CONNECT host:port} opens a tunnel (https sites). Every host is sent to one fixed target address (the test's
 * fake library), so test host names need no DNS. Optionally requires {@code Proxy-Authorization: Basic} and answers
 * 407 without it — with a {@code Proxy-Authenticate} challenge by default, or like Webshare's static residential
 * proxies ({@link #webshareLike()}, verified with curl 2026-10-18): {@code 407} with {@code Proxy-Connection: close},
 * {@code Connection: close} and <em>no</em> {@code Proxy-Authenticate}, so a client that waits for a challenge never
 * authenticates. Credentials on the first request are accepted either way. Records the request lines it served, so
 * a test can prove which lane a request left through, and counts the requests it refused.
 * Also runnable on its own ({@link #main}) for the local {@code bootRun} check of handoff 1-15.
 */
public final class TestHttpProxy implements AutoCloseable {

	private final ServerSocket server;

	private final InetSocketAddress target;

	private final String requiredAuthorization;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	/** Request lines served ({@code GET http://… HTTP/1.1} or {@code CONNECT host:port HTTP/1.1}). */
	public final List<String> served = new CopyOnWriteArrayList<>();

	public final AtomicInteger refused = new AtomicInteger();

	private volatile boolean challenge = true;

	private volatile boolean closed;

	/**
	 * @param target where every request goes whatever host it names; null = connect to the host the request names
	 * @param username with {@code password}: require Basic credentials; null = no authentication
	 */
	public TestHttpProxy(InetSocketAddress target, String username, String password) throws IOException {
		this(0, target, username, password);
	}

	/** @param port a fixed port, or 0 for any free one */
	public TestHttpProxy(int port, InetSocketAddress target, String username, String password) throws IOException {
		this.server = new ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"));
		this.target = target;
		this.requiredAuthorization = username == null ? null : "Basic " + Base64.getEncoder()
			.encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
		Thread.ofVirtual().name("test-proxy-accept").start(this::accept);
	}

	/** Refuse like Webshare: 407 without a {@code Proxy-Authenticate} challenge, connection closed. */
	public TestHttpProxy webshareLike() {
		this.challenge = false;
		return this;
	}

	public int port() {
		return server.getLocalPort();
	}

	public String hostPort() {
		return "127.0.0.1:" + port();
	}

	private void accept() {
		while (!closed) {
			try {
				Socket client = server.accept();
				executor.execute(() -> serve(client));
			}
			catch (IOException ex) {
				return;
			}
		}
	}

	private void serve(Socket client) {
		try (client) {
			client.setSoTimeout(15_000);
			InputStream in = client.getInputStream();
			OutputStream out = client.getOutputStream();
			while (true) {
				List<String> head = readHead(in);
				if (head.isEmpty()) {
					return;
				}
				String requestLine = head.get(0);
				if (requiredAuthorization != null && head.stream()
					.noneMatch(h -> h.toLowerCase().startsWith("proxy-authorization:")
							&& h.substring(h.indexOf(':') + 1).strip().equals(requiredAuthorization))) {
					refused.incrementAndGet();
					String answer = challenge
							? "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\n"
									+ "Content-Length: 0\r\nConnection: close\r\n\r\n"
							: "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Connection: close\r\n"
									+ "Connection: close\r\nContent-Length: 0\r\n\r\n";
					out.write(answer.getBytes(StandardCharsets.US_ASCII));
					out.flush();
					return;
				}
				served.add(requestLine);
				String[] parts = requestLine.split(" ");
				if (parts[0].equals("CONNECT")) {
					InetSocketAddress to = target != null ? target : address(parts[1]);
					try (Socket upstream = new Socket(to.getAddress(), to.getPort())) {
						out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
						out.flush();
						pipe(in, out, upstream);
					}
					return;
				}
				java.net.URI uri = java.net.URI.create(parts[1]);
				InetSocketAddress to = target != null ? target
						: new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 80 : uri.getPort());
				String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
				try (Socket upstream = new Socket(to.getAddress(), to.getPort())) {
					StringBuilder forwarded = new StringBuilder(parts[0] + " " + path + " " + parts[2] + "\r\n");
					for (int i = 1; i < head.size(); i++) {
						String h = head.get(i);
						String lower = h.toLowerCase();
						if (lower.startsWith("proxy-") || lower.startsWith("connection:")) {
							continue;
						}
						forwarded.append(h).append("\r\n");
					}
					forwarded.append("Connection: close\r\n\r\n");
					upstream.getOutputStream().write(forwarded.toString().getBytes(StandardCharsets.ISO_8859_1));
					upstream.getOutputStream().flush();
					// this proxy serves one request per connection and says so, as a real proxy does
					InputStream upstreamIn = upstream.getInputStream();
					StringBuilder responseHead = new StringBuilder();
					for (String h : readHead(upstreamIn)) {
						if (!h.toLowerCase().startsWith("connection:")) {
							responseHead.append(h).append("\r\n");
						}
					}
					responseHead.append("Connection: close\r\n\r\n");
					out.write(responseHead.toString().getBytes(StandardCharsets.ISO_8859_1));
					upstreamIn.transferTo(out);
					out.flush();
				}
				return;
			}
		}
		catch (IOException ex) {
			// the client went away or the target is down: the test sees the failure on its side
		}
	}

	private static InetSocketAddress address(String hostPort) {
		int colon = hostPort.lastIndexOf(':');
		return new InetSocketAddress(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)));
	}

	private void pipe(InputStream clientIn, OutputStream clientOut, Socket upstream) throws IOException {
		Thread up = Thread.ofVirtual().start(() -> {
			try {
				clientIn.transferTo(upstream.getOutputStream());
				upstream.shutdownOutput();
			}
			catch (IOException ex) {
				// closed
			}
		});
		try {
			upstream.getInputStream().transferTo(clientOut);
			clientOut.flush();
		}
		finally {
			up.interrupt();
		}
	}

	private static List<String> readHead(InputStream in) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		int last4 = 0;
		int b;
		while ((b = in.read()) != -1) {
			buffer.write(b);
			last4 = ((last4 << 8) | b) & 0xFFFFFFFF;
			if (last4 == 0x0D0A0D0A) {
				break;
			}
		}
		if (buffer.size() == 0) {
			return List.of();
		}
		List<String> lines = new ArrayList<>();
		for (String line : buffer.toString(StandardCharsets.ISO_8859_1).split("\r\n")) {
			if (!line.isEmpty()) {
				lines.add(line);
			}
		}
		return lines;
	}

	@Override
	public void close() throws IOException {
		closed = true;
		server.close();
		executor.shutdownNow();
	}

	/**
	 * Standalone: {@code java TestHttpProxy.java <port> [<port> …]} starts one forwarding proxy per port (real
	 * targets, no authentication) for the local bootRun check with {@code CHAEKCHECK_AVAILABILITY_EGRESS_PROXIES}.
	 */
	public static void main(String[] args) throws Exception {
		List<TestHttpProxy> proxies = new ArrayList<>();
		for (String arg : args) {
			TestHttpProxy proxy = new TestHttpProxy(Integer.parseInt(arg), null, null, null);
			proxies.add(proxy);
			System.out.println("test proxy on " + proxy.hostPort());
		}
		Thread.currentThread().join();
	}

}
