package com.rensilver.ativaflow_api.http;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiBodyLimitFilterTests {

	@Test
	void unknownOrUnderreportedLengthReadsAtMostLimitPlusOneWithoutInvokingApplication() throws Exception {
		for (int declaredLength : new int[] {-1, 1}) {
			var read = new AtomicInteger();
			var request = new MockHttpServletRequest("POST", "/api/v1/requests") {
				@Override public long getContentLengthLong() { return declaredLength; }
				@Override public ServletInputStream getInputStream() {
					return new ServletInputStream() {
						@Override public int read() throws IOException {
							if (read.incrementAndGet() > 17) throw new IOException("Read beyond bounded prefix");
							return 'x'; // Fluxo potencialmente infinito, sem alocar body gigante.
						}
						@Override public boolean isFinished() { return false; }
						@Override public boolean isReady() { return true; }
						@Override public void setReadListener(ReadListener listener) { }
					};
				}
			};
			var response = new MockHttpServletResponse();
			filter().doFilter(request, response, (req, res) -> { throw new AssertionError("Application invoked"); });
			assertThat(response.getStatus()).isEqualTo(413);
			assertThat(read.get()).isEqualTo(17);
		}
	}

	@Test
	void oversizedContentLengthIsRejectedWithoutReadingInput() throws Exception {
		var request = new MockHttpServletRequest("POST", "/api/v1/requests") {
			@Override public long getContentLengthLong() { return 17; }
			@Override public ServletInputStream getInputStream() { throw new AssertionError("Input read"); }
		};
		var response = new MockHttpServletResponse();
		filter().doFilter(request, response, (req, res) -> { throw new AssertionError("Application invoked"); });
		assertThat(response.getStatus()).isEqualTo(413);
	}

	@Test
	void acceptedBodyRemainsReadableUnderContextPath() throws Exception {
		var request = new MockHttpServletRequest("POST", "/service/api/v1/requests");
		request.setContextPath("/service");
		request.setContent("0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		var response = new MockHttpServletResponse();
		filter().doFilter(request, response, (req, res) -> {
			assertThat(req.getReader().readLine()).isEqualTo("0123456789abcdef");
			res.getWriter().write("accepted");
		});
		assertThat(response.getContentAsString()).isEqualTo("accepted");
	}

	private ApiBodyLimitFilter filter() {
		return new ApiBodyLimitFilter(new HttpLimitProperties(16, null), new ApiLimitProblemWriter(
				new MappingJackson2HttpMessageConverter().getObjectMapper()));
	}
}
