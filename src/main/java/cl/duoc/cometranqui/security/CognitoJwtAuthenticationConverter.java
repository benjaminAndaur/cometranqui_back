package cl.duoc.cometranqui.security;

import java.util.Collection;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class CognitoJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = authoritiesFrom(jwt);
        String principal = jwt.getClaimAsString("email");
        if (principal == null || principal.isBlank()) {
            principal = jwt.getSubject();
        }
        return new JwtAuthenticationToken(jwt, authorities, principal);
    }

    static List<GrantedAuthority> authoritiesFrom(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList("cognito:groups");
        if (groups == null) {
            return List.of();
        }
        return groups.stream()
                .map(String::toUpperCase)
                .map(group -> group.replaceAll("[^A-Z0-9_]", "_"))
                .map(group -> new SimpleGrantedAuthority("ROLE_" + group))
                .map(GrantedAuthority.class::cast)
                .toList();
    }
}
