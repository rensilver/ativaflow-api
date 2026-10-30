package com.rensilver.ativaflow_api.observability;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Disponibiliza correlação antes da segurança, sem copiar headers ou credenciais. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		var values = request.getHeaders(CorrelationId.HEADER);
		String incoming = values.hasMoreElements() ? values.nextElement() : null;
		String correlationId = !values.hasMoreElements() && CorrelationId.isValid(incoming)
				? incoming : UUID.randomUUID().toString();
		response.setHeader(CorrelationId.HEADER, correlationId);
		try (var ignored = CorrelationId.withId(correlationId)) {
			filterChain.doFilter(request, response);
		}
	}
}
