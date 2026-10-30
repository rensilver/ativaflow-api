package com.rensilver.ativaflow_api.security;

import java.text.ParseException;

import com.nimbusds.jwt.SignedJWT;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** Define o proprietário exclusivamente pelo sub do JWT já validado pelo decoder. */
public final class ConsumerJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		Object subject;
		try {
			// Nimbus aceita sub numérico e o converte em String antes de construir Jwt.
			// Inspeciona o tipo original do payload cuja assinatura o decoder já validou.
			var claims = SignedJWT.parse(jwt.getTokenValue()).getPayload().toJSONObject();
			subject = claims != null ? claims.get(JwtClaimNames.SUB) : null;
		}
		catch (ParseException exception) {
			throw new InvalidBearerTokenException("Invalid consumer identity");
		}
		if (!(subject instanceof String consumerId) || consumerId.isBlank() || consumerId.length() > 100) {
			throw new InvalidBearerTokenException("Invalid consumer identity");
		}
		// Preserva o identificador exato: normalizar poderia unir proprietários distintos.
		return new JwtAuthenticationToken(jwt, authoritiesConverter.convert(jwt), consumerId);
	}
}
