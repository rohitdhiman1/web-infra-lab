package dev.webinfra.labs.orders;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * The token issuer's key pair: P-256, for ES256 signatures. Generated at startup, so restarting
 * the server invalidates every token. A real identity provider keeps its private key in a KMS or HSM,
 * rotates it, and publishes the public half as a JWKS; resource servers fetch that JWKS by URL.
 */
@Component
public class SigningKeys {
  private final ECKey jwk;

  public SigningKeys() throws Exception {
    var gen = KeyPairGenerator.getInstance("EC");
    gen.initialize(new ECGenParameterSpec("secp256r1"));
    var pair = gen.generateKeyPair();
    // kid lets verifiers pick the right key during a rotation, when two keys are live at once.
    this.jwk = new ECKey.Builder(Curve.P_256, (ECPublicKey) pair.getPublic())
        .privateKey((ECPrivateKey) pair.getPrivate())
        .keyID(UUID.randomUUID().toString())
        .build();
  }

  /** The public half only, safe to publish. */
  JWKSet publicJwks() {
    return new JWKSet(jwk.toPublicJWK());
  }

  String keyId() {
    return jwk.getKeyID();
  }

  @Bean
  JwtEncoder jwtEncoder() {
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
  }
}
