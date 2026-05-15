#!/bin/zsh

echo "🔐 Logging in as Admin..."

ADMIN_TOKEN=$(curl -k -s -X POST https://localhost/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"14495abc"}' | grep -o '"token":"[^"]*' | cut -d'"' -f4)

echo "Admin token: $ADMIN_TOKEN"

if [[ -z "$ADMIN_TOKEN" ]]; then
    echo "❌ Failed to get Admin token. Is the server running?"
    exit 1
fi

echo "✅ Admin logged in. Creating 20 PEER users..."

for i in {1..20}; do
    USER="peer${i}"
    echo -n "Creating ${USER}... "

    RESPONSE=$(curl -k -s -o /dev/null -w "%{http_code}" -X POST https://localhost/api/users \
      -H "Authorization: Bearer $ADMIN_TOKEN" \
      -H "Content-Type: application/json" \
      -d '{"username":"'"$USER"'","password":"password123","role":"PEER"}')

    if [[ "$RESPONSE" == "200" ]]; then
        echo "✅ Success"
    else
        echo "⚠️ Failed (HTTP $RESPONSE)"
    fi
done

echo "🎉 Done creating 20 PEER users."

# --- LOGOUT SECTION ---
echo "🚪 Logging out Admin user..."

LOGOUT_RESPONSE=$(curl -k -s -X POST https://localhost/api/logout \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json")

if [[ $(echo $LOGOUT_RESPONSE | grep -o '"success":true') ]]; then
    echo "✅ Admin logged out successfully."
else
    echo "⚠️ Logout failed or returned an unexpected response."
    echo "Response: $LOGOUT_RESPONSE"
fi