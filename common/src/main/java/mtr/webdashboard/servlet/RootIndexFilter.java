package mtr.webdashboard.servlet;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.util.EnumSet;

/**
 * Serves the web dashboard page for {@code GET /}, by rewriting the request path to
 * {@code /index.html} and letting the static handler do the work it already does correctly.
 * <p>
 * A filter rather than a servlet on purpose. In Jetty the path {@code "/"} is the <em>default
 * servlet</em> mapping, so registering another servlet there fails the whole context with
 * "Multiple servlets map to path /" - which took the service down entirely rather than just 404ing.
 * A filter can intercept {@code /} without claiming the mapping.
 * <p>
 * This also sidesteps the welcome-file machinery, which does not resolve reliably when the document
 * root is a {@code jar:} URL: that is what made {@code /} return 404 while {@code /index.html} worked.
 * <p>
 * Stage 2 will extend this to read a one-time sign-in token off the query string, which is why the
 * root is intercepted here rather than left to the static handler.
 */
public class RootIndexFilter implements Filter {

	/** Prefixed with a slash so it can be rewritten into a request path, not a relative URL. */
	private static final String INDEX_PATH = "/index.html";

	@Override
	public void init(FilterConfig filterConfig) {
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
		if (request instanceof HttpServletRequest && isRootRequest((HttpServletRequest) request)) {
			try {
				chain.doFilter(new IndexRequestWrapper((HttpServletRequest) request), response);
			} catch (Exception e) {
				// Never propagate: a failure here should degrade to the static handler's own response
				// rather than a blank connection reset.
				System.out.println("[MTR-WebDashboard] Could not rewrite the root request to " + INDEX_PATH + ": " + e);
			}
			return;
		}

		try {
			chain.doFilter(request, response);
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Request failed: " + e);
		}
	}

	@Override
	public void destroy() {
	}

	/**
	 * True only for the bare document root. {@code /index.html}, {@code /app.js} and the API paths are
	 * left alone, and the context path is stripped first so this keeps working if the handler is ever
	 * mounted under a prefix.
	 */
	private static boolean isRootRequest(HttpServletRequest request) {
		String path = request.getRequestURI();
		final String contextPath = request.getContextPath();
		if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
			path = path.substring(contextPath.length());
		}
		return path.isEmpty() || "/".equals(path);
	}

	/**
	 * Presents the request as though the browser had asked for {@code /index.html}.
	 * <p>
	 * Every path-derived method is overridden, not just the URI: Jetty's static handler resolves the
	 * file from {@code getServletPath} / {@code getPathInfo} as well, and rewriting only one of them
	 * leaves it looking for the document root.
	 * <p>
	 * Deliberately not a redirect: an internal rewrite keeps the address bar on {@code /}, so relative
	 * URLs in the page and the {@code /api/...} fetches stay on whatever host the visitor actually
	 * typed, including {@code localhost} versus {@code 127.0.0.1}.
	 */
	private static final class IndexRequestWrapper extends HttpServletRequestWrapper {

		private IndexRequestWrapper(HttpServletRequest request) {
			super(request);
		}

		@Override
		public String getRequestURI() {
			return INDEX_PATH;
		}

		@Override
		public String getServletPath() {
			return INDEX_PATH;
		}

		@Override
		public String getPathInfo() {
			return null;
		}

		@Override
		public String getPathTranslated() {
			return null;
		}
	}

	/**
	 * Convenience for the server setup: the filter applies to every request so it can inspect the
	 * root, and returns a dispatcher set matching a plain browser request.
	 */
	public static EnumSet<DispatcherType> dispatcherTypes() {
		return EnumSet.of(DispatcherType.REQUEST);
	}
}
