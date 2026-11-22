package mtr.webdashboard.servlet;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.util.EnumSet;

public class RootIndexFilter implements Filter {

	private static final String INDEX_PATH = "/index.html";

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
		if (request instanceof HttpServletRequest) {
			final HttpServletRequest httpRequest = (HttpServletRequest) request;
			String path = httpRequest.getRequestURI();
			final String contextPath = httpRequest.getContextPath();
			if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
				path = path.substring(contextPath.length());
			}
			if (path.isEmpty() || "/".equals(path)) {
				try {
					chain.doFilter(new IndexRequestWrapper(httpRequest), response);
				} catch (Exception e) {
					System.out.println("[MTR-WebDashboard] Could not rewrite the root request to " + INDEX_PATH + ": " + e);
				}
				return;
			}
		}

		try {
			chain.doFilter(request, response);
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Request failed: " + e);
		}
	}

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
}
