#!/bin/zsh

# Create certificates directory
CERT_DIR="./certs"
mkdir -p "$CERT_DIR"

echo "🔐 Generating ECDSA (P-256) certificates for development..."

# Generate CA (Certificate Authority)
echo "1. Generating CA ECDSA (P-256)..."
openssl ecparam -genkey -name prime256v1 -noout -out "$CERT_DIR/ca.key"
openssl req -new -x509 -days 3650 -key "$CERT_DIR/ca.key" -out "$CERT_DIR/ca.crt" \
    -subj "/C=US/ST=State/L=City/O=Interceptor/OU=Dev/CN=Interceptor-CA" \
    -sha384

# Generate Server Certificate (ECDSA P-256)
echo "2. Generating Server certificate - (ECDSA P-256)..."
openssl ecparam -genkey -name prime256v1 -noout -out "$CERT_DIR/server.key"

# Create server CSR
openssl req -new -key "$CERT_DIR/server.key" -out "$CERT_DIR/server.csr" \
    -subj "/C=US/ST=State/L=City/O=Interceptor/OU=Proxy/CN=localhost"

# Create extensions file for SAN (Subject Alternative Names)
cat > "$CERT_DIR/server.ext" << EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature
extendedKeyUsage = serverAuth
subjectAltName = @alt_names

[alt_names]
DNS.1 = localhost
DNS.2 = interceptor
DNS.3 = *.interceptor.local
IP.1 = 127.0.0.1
IP.2 = 0.0.0.0
EOF

# Sign server certificate with CA
openssl x509 -req -in "$CERT_DIR/server.csr" -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" \
    -CAcreateserial -out "$CERT_DIR/server.crt" -days 825 -sha384 -extfile "$CERT_DIR/server.ext"

# Generate Client Certificate - ECDSA P-256
echo "3. Generating Client certificate (ECDSA P-256)..."
openssl ecparam -genkey -name prime256v1 -noout -out "$CERT_DIR/client.key"
openssl req -new -key "$CERT_DIR/client.key" -out "$CERT_DIR/client.csr" \
    -subj "/C=US/ST=State/L=City/O=Interceptor/OU=Authority/CN=admin-authority"
openssl x509 -req -in "$CERT_DIR/client.csr" -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" \
    -CAcreateserial -out "$CERT_DIR/client.crt" -days 825 -sha384

# Create PKCS12 keystore for client certificates (for Postgresql JDBC driver)
echo "3a. Create PKCS12 client keystore..."
openssl pkcs12 -export -in "$CERT_DIR/client.crt" -inkey "$CERT_DIR/client.key" \
    -out "$CERT_DIR/client.p12" -name interceptor-client -CAfile "$CERT_DIR/ca.crt" \
    -caname root -password pass:changeit

# Create PKCS12 keystore for Spring Boot
echo "4. Creating PKCS12 keystore..."
openssl pkcs12 -export -in "$CERT_DIR/server.crt" -inkey "$CERT_DIR/server.key" \
    -out "$CERT_DIR/server.p12" -name interceptor -CAfile "$CERT_DIR/ca.crt" \
    -caname root -password pass:changeit

# Create truststore with CA certificate
echo "5. Creating truststore..."
if keytool -list -keystore "$CERT_DIR/truststore.p12" \
   -storetype PKCS12 \
   -storepass changeit \
   -alias interceptor-ca >/dev/null 2>&1; then
  echo "Removing existing interceptor-ca from truststore..."
  keytool -delete \
    -alias interceptor-ca \
    -keystore "$CERT_DIR/truststore.p12" \
    -storetype PKCS12 \
    -storepass changeit
fi

keytool -import -trustcacerts -noprompt -alias interceptor-ca \
    -file "$CERT_DIR/ca.crt" -keystore "$CERT_DIR/truststore.p12" \
    -storetype PKCS12 -storepass changeit

# Set permissions
chmod 600 "$CERT_DIR/ca.key" "$CERT_DIR/server.key" "$CERT_DIR/client.key"
chmod 644 "$CERT_DIR/ca.crt" "$CERT_DIR/server.crt" "$CERT_DIR/client.crt" \
          "$CERT_DIR/server.p12" "$CERT_DIR/client.p12" "$CERT_DIR/truststore.p12"

# Copy keystores to resources
echo "6. Copying keystores to src/main/resources/ssl/"
mkdir -p src/main/resources/ssl
cp "$CERT_DIR/server.p12" "$CERT_DIR/client.p12" "$CERT_DIR/truststore.p12" src/main/resources/ssl/

echo ""
echo "✅ ECDSA P-256 certificates generated successfully in $CERT_DIR/"
echo ""
echo "Files created @ certs:"
ls -la "$CERT_DIR/"
echo ""
ls -la src/main/resources/ssl/
echo ""

echo "Next steps:"
echo "  1. Start infrastructure: docker-compose up -d"
echo "  2. Run the application:  source creds.env && mvn spring-boot:run -Dspring-boot.run.profiles=dev"
echo "  3. Access dashboard:     https://localhost:3000"

