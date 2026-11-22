package mtr.webdashboard.tunnel;

import mtr.webdashboard.WebDashboardAssets;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The browser's local entry point in multiplayer.
 * <p>
 * It is deliberately not a tunnel for everything. HTML, CSS and JavaScript are served straight from
 * this jar: those files are large, need no server state, and pushing them through 16 KB Minecraft
 * packets would make the page load slower for no benefit. Only requests below {@code /api/} are sent
 * over the game connection.
 * <p>
 * Because it is a small HTTP/1.1 server, not a byte pipe, one request is handled per connection and
 * the connection is then closed. That keeps request framing trivial and is free on loopback.
 */
public final class WebDashboardLocalProxy {

	/** The single-player dashboard also uses 8890, so this origin keeps localStorage/theme stable. */
	private static final int[] PREFERRED_PORTS = {8890, 8891, 8892};

	private static final int MAX_CONNECTIONS = 64;
	private static final int MAX_HEADER_BYTES = 32 * 1024;
	private static final int MAX_BODY_BYTES = WebDashboardTunnelProtocol.MAX_REQUEST_BODY_BYTES;
	private static final int MAX_STATIC_BYTES = 8 * 1024 * 1024;
	private static final int READ_TIMEOUT_MS = 10_000;
	private static final int API_READ_TIMEOUT_MS = 30_000;

	private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
	private static final Set<Socket> ACTIVE_SOCKETS = ConcurrentHashMap.newKeySet();

	private static ServerSocket serverSocket;
	private static ExecutorService executor;
	private static Semaphore connectionSlots;
	private static Thread acceptThread;
	private static volatile boolean running;
	private static int port = -1;

	private WebDashboardLocalProxy() {
	}

	public static synchronized boolean ensureRunning() {
		return running || start();
	}

	public static synchronized boolean start() {
		if (running) {
			return true;
		}

		if (!hasStaticIndex()) {
			System.out.println("[MTR-WebDashboard] The local proxy cannot start: " + WebDashboardAssets.RESOURCE_ROOT
					+ WebDashboardAssets.INDEX_PAGE + " is not on this client's classpath.");
			return false;
		}

		ServerSocket socket = null;
		for (final int candidate : PREFERRED_PORTS) {
			socket = bind(candidate);
			if (socket != null) {
				break;
			}
		}
		if (socket == null) {
			socket = bind(0);
		}
		if (socket == null) {
			System.out.println("[MTR-WebDashboard] Could not bind the local web dashboard proxy to 127.0.0.1.");
			return false;
		}

		port = socket.getLocalPort();
		serverSocket = socket;
		executor = Executors.newCachedThreadPool(runnable -> {
			final Thread thread = new Thread(runnable, "mtr-webdashboard-proxy-" + THREAD_COUNTER.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		connectionSlots = new Semaphore(MAX_CONNECTIONS);
		running = true;

		acceptThread = new Thread(WebDashboardLocalProxy::acceptLoop, "mtr-webdashboard-proxy-accept");
		acceptThread.setDaemon(true);
		acceptThread.start();

		System.out.println("[MTR-WebDashboard] Local proxy listening on " + getUrl()
				+ " (static assets served from this jar, API paths are tunnelled)");
		return true;
	}

	public static synchronized void stop() {
		if (!running) {
			return;
		}
		running = false;
		System.out.println("[MTR-WebDashboard] Local proxy on port " + port + " stopping.");

		final ServerSocket socket = serverSocket;
		serverSocket = null;
		closeQuietly(socket);

		for (final Socket active : ACTIVE_SOCKETS) {
			closeQuietly(active);
		}
		ACTIVE_SOCKETS.clear();

		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}

		acceptThread = null;
		connectionSlots = null;
		port = -1;
	}

	public static boolean isRunning() {
		return running;
	}

	public static int getPort() {
		return port;
	}

	public static String getUrl() {
		return "http://127.0.0.1:" + port + "/";
	}

	public static String getLoginUrl(String token) {
		return token == null || token.isEmpty() ? getUrl() : getUrl() + "#token=" + token;
	}

	private static boolean hasStaticIndex() {
		try (InputStream stream = WebDashboardLocalProxy.class.getResourceAsStream(WebDashboardAssets.RESOURCE_ROOT + WebDashboardAssets.INDEX_PAGE)) {
			return stream != null;
		} catch (Exception e) {
			return false;
		}
	}

	private static ServerSocket bind(int candidatePort) {
		ServerSocket socket = null;
		try {
			socket = new ServerSocket();
			socket.setReuseAddress(true);
			socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), candidatePort), 64);
			return socket;
		} catch (Exception e) {
			closeQuietly(socket);
			return null;
		}
	}

	private static void acceptLoop() {
		while (running) {
			final ServerSocket socket = serverSocket;
			if (socket == null) {
				return;
			}
			try {
				final Socket connection = socket.accept();
				if (!connection.getInetAddress().isLoopbackAddress()) {
					System.out.println("[MTR-WebDashboard] Refused a non-loopback connection to the local proxy.");
					closeQuietly(connection);
					continue;
				}
				if (connectionSlots == null || !connectionSlots.tryAcquire()) {
					closeQuietly(connection);
					continue;
				}
				final ExecutorService currentExecutor = executor;
				if (currentExecutor == null) {
					connectionSlots.release();
					closeQuietly(connection);
					continue;
				}
				// Registered before submission, not inside the task: a task that never starts (shutdown
				// drains the queue) would otherwise leave the accepted socket open.
				ACTIVE_SOCKETS.add(connection);
				try {
					currentExecutor.execute(() -> {
						try {
							handle(connection);
						} catch (IOException ignored) {
							// A browser that closed a tab or aborted a fetch is normal, not an error.
						} catch (Throwable t) {
							if (running) {
								System.out.println("[MTR-WebDashboard] Local proxy request failed: " + t);
							}
						} finally {
							ACTIVE_SOCKETS.remove(connection);
							closeQuietly(connection);
							if (connectionSlots != null) {
								connectionSlots.release();
							}
						}
					});
				} catch (RuntimeException e) {
					ACTIVE_SOCKETS.remove(connection);
					if (connectionSlots != null) {
						connectionSlots.release();
					}
					closeQuietly(connection);
				}
			} catch (SocketException e) {
				if (running) {
					System.out.println("[MTR-WebDashboard] Local proxy accept loop stopped: " + e.getMessage());
				}
				return;
			} catch (Exception e) {
				if (running) {
					System.out.println("[MTR-WebDashboard] Local proxy could not accept a connection: " + e);
				}
			}
		}
	}

	private static void handle(Socket socket) throws IOException {
		ACTIVE_SOCKETS.add(socket);
		socket.setTcpNoDelay(true);
		socket.setSoTimeout(READ_TIMEOUT_MS);

		final InputStream input = new BufferedInputStream(socket.getInputStream());
		final OutputStream output = socket.getOutputStream();
		final HttpRequest request = readRequest(socket, input, output);
		if (request == null) {
			return;
		}

		if (request.path().equals("/api") || request.path().startsWith("/api/")) {
			handleApi(request, output);
		} else {
			handleStatic(request, output);
		}
		output.flush();
	}

	private static void handleStatic(HttpRequest request, OutputStream output) throws IOException {
		if (!"GET".equalsIgnoreCase(request.method()) && !"HEAD".equalsIgnoreCase(request.method())) {
			writePlainResponse(output, 405, "Method Not Allowed", "Only GET and HEAD are supported here.", "Allow: GET, HEAD\r\n");
			return;
		}

		String resource = request.path();
		if (resource.isEmpty() || "/".equals(resource)) {
			resource = WebDashboardAssets.INDEX_PAGE;
		} else if (resource.startsWith("/")) {
			resource = resource.substring(1);
		}
		if (!isSafeResourceName(resource)) {
			writePlainResponse(output, 404, "Not Found", "Not found.", null);
			return;
		}

		final byte[] body = readResource(resource);
		if (body == null) {
			writePlainResponse(output, 404, "Not Found", "Not found.", null);
			return;
		}

		writeResponse(output, 200, "OK", contentType(resource), body, "HEAD".equalsIgnoreCase(request.method()), null);
	}

	private static void handleApi(HttpRequest request, OutputStream output) throws IOException {
		if (!WebDashboardTunnelClient.isConnected()) {
			writeJsonError(output, 503, "tunnel_unavailable");
			return;
		}

		final byte[] payload = buildApiRequest(request);
		final ClientApiStream stream;
		try {
			stream = WebDashboardTunnelClient.openApiStream();
		} catch (WebDashboardTunnelClient.TunnelException e) {
			writeJsonError(output, 503, e.getCode());
			return;
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not open a tunnel stream: " + e);
			writeJsonError(output, 503, "tunnel_error");
			return;
		}

		boolean wroteAnything = false;
		boolean reset = false;
		try {
			stream.write(payload, payload.length);

			while (true) {
				final byte[] chunk = stream.read(API_READ_TIMEOUT_MS);
				if (chunk == null) {
					break;
				}
				// Marked before the write: if the browser went away mid-write, appending an error
				// response afterwards would only corrupt a partially delivered response.
				wroteAnything = true;
				output.write(chunk);
				output.flush();
				stream.releaseReceive(chunk.length);
			}

			if (!wroteAnything) {
				if (stream.isTimedOut()) {
					writeJsonError(output, 503, "tunnel_timeout");
				} else {
					writeJsonError(output, 503, "tunnel_empty");
				}
			}
		} catch (IOException e) {
			reset = true;
			if (!wroteAnything) {
				writeJsonError(output, 503, "tunnel_unavailable");
			}
		} finally {
			final int closeReason = reset
					? WebDashboardTunnelProtocol.CLOSE_RESET
					: stream.isTimedOut() ? WebDashboardTunnelProtocol.CLOSE_TIMEOUT : WebDashboardTunnelProtocol.CLOSE_NORMAL;
			stream.closeWithPacket(closeReason);
		}
	}

	/**
	 * Builds the request that goes through the tunnel. Hop-by-hop headers are replaced with
	 * {@code Connection: close}: Jetty then closes after the response, so the stream ending is itself
	 * the response boundary and neither side has to parse HTTP again.
	 */
	private static byte[] buildApiRequest(HttpRequest request) throws IOException {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256 + request.body().length);
		writeAscii(buffer, request.method() + " " + request.target() + " HTTP/1.1\r\n");

		for (final Header header : request.headers()) {
			if (isHopByHop(header.name()) || "Content-Length".equalsIgnoreCase(header.name()) || "Expect".equalsIgnoreCase(header.name())) {
				continue;
			}
			writeAscii(buffer, header.name() + ": " + header.value() + "\r\n");
		}

		if (request.body().length > 0 || hasRequestBody(request.method())) {
			writeAscii(buffer, "Content-Length: " + request.body().length + "\r\n");
		}
		writeAscii(buffer, "Connection: close\r\n\r\n");
		buffer.write(request.body());
		return buffer.toByteArray();
	}

	private static boolean hasRequestBody(String method) {
		return "POST".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)
				|| "PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
	}

	private static boolean isHopByHop(String name) {
		return "Connection".equalsIgnoreCase(name)
				|| "Proxy-Connection".equalsIgnoreCase(name)
				|| "Keep-Alive".equalsIgnoreCase(name)
				|| "TE".equalsIgnoreCase(name)
				|| "Trailer".equalsIgnoreCase(name)
				|| "Transfer-Encoding".equalsIgnoreCase(name)
				|| "Upgrade".equalsIgnoreCase(name);
	}

	private static HttpRequest readRequest(Socket socket, InputStream input, OutputStream output) throws IOException {
		final byte[] headerBytes;
		try {
			headerBytes = readHeaderBlock(input);
		} catch (IOException e) {
			if (e instanceof EOFException) {
				return null;
			}
			writePlainResponse(output, 431, "Request Header Fields Too Large", "The request headers were too large.", null);
			return null;
		}
		if (headerBytes == null) {
			writePlainResponse(output, 400, "Bad Request", "Malformed request headers.", null);
			return null;
		}

		final String headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);
		final String[] lines = headerText.split("\r\n");
		if (lines.length == 0) {
			writePlainResponse(output, 400, "Bad Request", "Malformed request line.", null);
			return null;
		}

		final String[] requestLine = lines[0].split(" ", 3);
		if (requestLine.length != 3 || requestLine[0].isEmpty() || requestLine[1].isEmpty()) {
			writePlainResponse(output, 400, "Bad Request", "Malformed request line.", null);
			return null;
		}

		final String method = requestLine[0];
		final String target = requestLine[1];
		final String version = requestLine[2];
		if (!"HTTP/1.1".equals(version) && !"HTTP/1.0".equals(version)) {
			writePlainResponse(output, 505, "HTTP Version Not Supported", "Only HTTP/1.0 and HTTP/1.1 are supported.", null);
			return null;
		}
		if (!target.startsWith("/")) {
			writePlainResponse(output, 400, "Bad Request", "Only origin-form request targets are accepted.", null);
			return null;
		}

		final int queryStart = target.indexOf('?');
		final String path = queryStart < 0 ? target : target.substring(0, queryStart);
		final String query = queryStart < 0 ? "" : target.substring(queryStart);
		if (path.isEmpty() || path.indexOf('#') >= 0) {
			writePlainResponse(output, 400, "Bad Request", "Malformed request target.", null);
			return null;
		}

		final List<Header> headers = new ArrayList<>();
		long contentLength = 0;
		boolean sawContentLength = false;
		boolean chunked = false;
		boolean expectContinue = false;
		for (int i = 1; i < lines.length; i++) {
			final String line = lines[i];
			if (line.isEmpty()) {
				continue;
			}
			final int colon = line.indexOf(':');
			if (colon <= 0) {
				writePlainResponse(output, 400, "Bad Request", "Malformed request header.", null);
				return null;
			}
			final String name = line.substring(0, colon).trim();
			final String value = line.substring(colon + 1).trim();
			if (name.isEmpty()) {
				writePlainResponse(output, 400, "Bad Request", "Malformed request header.", null);
				return null;
			}
			headers.add(new Header(name, value));

			if ("Content-Length".equalsIgnoreCase(name)) {
				try {
					final long parsed = Long.parseLong(value);
					if (parsed < 0 || (sawContentLength && parsed != contentLength)) {
						writePlainResponse(output, 400, "Bad Request", "Invalid Content-Length.", null);
						return null;
					}
					contentLength = parsed;
					sawContentLength = true;
				} catch (NumberFormatException e) {
					writePlainResponse(output, 400, "Bad Request", "Invalid Content-Length.", null);
					return null;
				}
			} else if ("Transfer-Encoding".equalsIgnoreCase(name)) {
				chunked = value.length() > 0 && !"identity".equalsIgnoreCase(value);
			} else if ("Expect".equalsIgnoreCase(name)) {
				expectContinue = value.toLowerCase(Locale.ENGLISH).contains("100-continue");
			}
		}

		if (chunked) {
			writePlainResponse(output, 400, "Bad Request", "Chunked request bodies are not supported here.", null);
			return null;
		}
		if (contentLength > MAX_BODY_BYTES) {
			writePlainResponse(output, 413, "Payload Too Large", "The request body is too large.", null);
			return null;
		}
		if (expectContinue) {
			writeAsciiAsBytes(output, "HTTP/1.1 100 Continue\r\n\r\n");
			output.flush();
		}

		final byte[] body;
		if (contentLength > 0) {
			body = readExactly(input, (int) contentLength);
			if (body == null) {
				writePlainResponse(output, 400, "Bad Request", "The request body was truncated.", null);
				return null;
			}
		} else {
			body = new byte[0];
		}

		return new HttpRequest(method, target, path, query, version, headers, body);
	}

	/**
	 * Reads until the blank line that ends the header block. The terminating sequence is included;
	 * callers split on CRLF and simply skip the trailing empty line.
	 */
	private static byte[] readHeaderBlock(InputStream input) throws IOException {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
		int matched = 0;
		while (buffer.size() < MAX_HEADER_BYTES) {
			final int value = input.read();
			if (value < 0) {
				throw new EOFException();
			}
			buffer.write(value);

			if (value == '\r') {
				matched = matched == 2 ? 3 : 1;
			} else if (value == '\n' && (matched == 1 || matched == 3)) {
				matched++;
			} else {
				matched = 0;
			}
			if (matched == 4) {
				return buffer.toByteArray();
			}
		}
		throw new IOException("Header block too large");
	}

	private static byte[] readExactly(InputStream input, int length) throws IOException {
		final byte[] data = new byte[length];
		int offset = 0;
		while (offset < length) {
			final int read = input.read(data, offset, length - offset);
			if (read < 0) {
				return null;
			}
			offset += read;
		}
		return data;
	}

	private static byte[] readResource(String resource) {
		try (InputStream stream = WebDashboardLocalProxy.class.getResourceAsStream(WebDashboardAssets.RESOURCE_ROOT + resource)) {
			if (stream == null) {
				return null;
			}
			final ByteArrayOutputStream buffer = new ByteArrayOutputStream(16 * 1024);
			final byte[] chunk = new byte[16 * 1024];
			int read;
			while ((read = stream.read(chunk)) >= 0) {
				buffer.write(chunk, 0, read);
				if (buffer.size() > MAX_STATIC_BYTES) {
					System.out.println("[MTR-WebDashboard] Refused an oversized static resource: " + resource);
					return null;
				}
			}
			return buffer.toByteArray();
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not read static resource " + resource + ": " + e);
			return null;
		}
	}

	/**
	 * Only names that cannot escape the asset root are accepted. No percent-decoding is needed: the
	 * dashboard's own files use plain ASCII names, and refusing {@code %} removes a whole class of
	 * traversal tricks.
	 */
	private static boolean isSafeResourceName(String resource) {
		if (resource.isEmpty() || resource.startsWith("/") || resource.endsWith("/")) {
			return false;
		}
		if (resource.contains("..") || resource.contains("\\") || resource.contains("%")
				|| resource.contains("//") || resource.indexOf('\0') >= 0) {
			return false;
		}
		for (int i = 0; i < resource.length(); i++) {
			final char character = resource.charAt(i);
			final boolean allowed = character >= 'a' && character <= 'z'
					|| character >= 'A' && character <= 'Z'
					|| character >= '0' && character <= '9'
					|| character == '.' || character == '_' || character == '-' || character == '/';
			if (!allowed) {
				return false;
			}
		}
		return true;
	}

	private static String contentType(String resource) {
		final int dot = resource.lastIndexOf('.');
		final String extension = dot < 0 ? "" : resource.substring(dot + 1).toLowerCase(Locale.ENGLISH);
		return switch (extension) {
			case "html", "htm" -> "text/html; charset=utf-8";
			case "js", "mjs" -> "text/javascript; charset=utf-8";
			case "css" -> "text/css; charset=utf-8";
			case "json" -> "application/json; charset=utf-8";
			case "svg" -> "image/svg+xml";
			case "png" -> "image/png";
			case "ico" -> "image/x-icon";
			case "woff2" -> "font/woff2";
			default -> "application/octet-stream";
		};
	}

	private static void writeJsonError(OutputStream output, int status, String code) throws IOException {
		final String body = "{\"error\":\"" + code + "\"}";
		writeResponse(output, status, reasonPhrase(status), "application/json; charset=utf-8",
				body.getBytes(StandardCharsets.UTF_8), false, null);
	}

	private static void writePlainResponse(OutputStream output, int status, String reason, String message, String extraHeaders) throws IOException {
		final byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
		writeResponse(output, status, reason, "text/plain; charset=utf-8", body, false, extraHeaders);
	}

	private static void writeResponse(OutputStream output, int status, String reason, String contentType, byte[] body,
			boolean headOnly, String extraHeaders) throws IOException {
		final StringBuilder headers = new StringBuilder(256);
		headers.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
		headers.append("Content-Type: ").append(contentType).append("\r\n");
		headers.append("Content-Length: ").append(body.length).append("\r\n");
		headers.append("Cache-Control: no-store\r\n");
		headers.append("X-Content-Type-Options: nosniff\r\n");
		headers.append("Connection: close\r\n");
		if (extraHeaders != null && !extraHeaders.isEmpty()) {
			headers.append(extraHeaders);
			if (!extraHeaders.endsWith("\r\n")) {
				headers.append("\r\n");
			}
		}
		headers.append("\r\n");

		writeAsciiAsBytes(output, headers.toString());
		if (!headOnly && body.length > 0) {
			output.write(body);
		}
		output.flush();
	}

	private static void writeAscii(ByteArrayOutputStream buffer, String text) {
		final byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
		buffer.write(bytes, 0, bytes.length);
	}

	private static void writeAsciiAsBytes(OutputStream output, String text) throws IOException {
		output.write(text.getBytes(StandardCharsets.ISO_8859_1));
	}

	private static String reasonPhrase(int status) {
		return switch (status) {
			case 200 -> "OK";
			case 400 -> "Bad Request";
			case 404 -> "Not Found";
			case 405 -> "Method Not Allowed";
			case 413 -> "Payload Too Large";
			case 431 -> "Request Header Fields Too Large";
			case 500 -> "Internal Server Error";
			case 503 -> "Service Unavailable";
			case 505 -> "HTTP Version Not Supported";
			default -> "Error";
		};
	}

	private static void closeQuietly(ServerSocket socket) {
		if (socket != null) {
			try {
				socket.close();
			} catch (Exception ignored) {
			}
		}
	}

	private static void closeQuietly(Socket socket) {
		if (socket != null) {
			try {
				socket.close();
			} catch (Exception ignored) {
			}
		}
	}

	private record Header(String name, String value) {
	}

	private record HttpRequest(String method, String target, String path, String query, String version,
			List<Header> headers, byte[] body) {
	}
}
