package com.rensilver.ativaflow_api.http;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/** Faz leitura limitada antes do MVC, inclusive do sufixo que o parser JSON poderia ignorar. */
final class ApiBodyLimitFilter extends OncePerRequestFilter {

	private final int maxBytes;
	private final ApiLimitProblemWriter problems;

	ApiBodyLimitFilter(HttpLimitProperties properties, ApiLimitProblemWriter problems) {
		this.maxBytes = properties.maxBodyBytes();
		this.problems = problems;
	}

	static boolean isApiRequest(HttpServletRequest request) {
		// O servlet usa o caminho já decodificado; comparar só a URI bruta permitiria /a%70i/.
		String path = request.getPathInfo();
		if (path == null) path = request.getServletPath();
		if (path == null || path.isEmpty()) {
			path = UriUtils.decode(request.getRequestURI().substring(request.getContextPath().length()),
					StandardCharsets.UTF_8);
		}
		return path.startsWith("/api/");
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !isApiRequest(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > maxBytes) {
			reject(request, response);
			return;
		}
		byte[] bytes = request.getInputStream().readNBytes(maxBytes + 1);
		if (bytes.length > maxBytes) {
			reject(request, response);
			return;
		}
		chain.doFilter(new BufferedRequest(request, bytes), response);
	}

	private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
		problems.write(request, response, HttpStatus.PAYLOAD_TOO_LARGE, "Request body is too large");
	}

	/** O processamento MVC é síncrono; o corpo aceito ocupa no máximo maxBodyBytes. */
	private static final class BufferedRequest extends HttpServletRequestWrapper {
		private final ServletInputStream input;

		BufferedRequest(HttpServletRequest request, byte[] bytes) {
			super(request);
			var buffer = new ByteArrayInputStream(bytes);
			input = new ServletInputStream() {
				@Override public int read() { return buffer.read(); }
				@Override public int read(byte[] b, int off, int len) { return buffer.read(b, off, len); }
				@Override public long skip(long n) { return buffer.skip(n); }
				@Override public int available() { return buffer.available(); }
				@Override public boolean isFinished() { return buffer.available() == 0; }
				@Override public boolean isReady() { return true; }
				@Override public void setReadListener(ReadListener listener) {
					throw new IllegalStateException("Non-blocking request reads are not supported");
				}
			};
		}

		@Override public ServletInputStream getInputStream() { return input; }
		@Override public BufferedReader getReader() {
			String encoding = getCharacterEncoding();
			return new BufferedReader(new InputStreamReader(input,
					encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
		}
	}
}
