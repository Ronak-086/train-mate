# Setting up Google sign-in (Firebase)

The code is in place. What it needs from you is a Firebase project.

> **Why you only see the dev picker:** the Google button is off until you set `NEXT_PUBLIC_AUTH_MODE=firebase` (step 2 below).
> Next.js reads that setting when it starts, so **stop and restart `npm run dev`** after creating or changing `web/.env.local`.

## 1. Create the project
1. Go to the [Firebase console](https://console.firebase.google.com), **Add project**.
2. **Build > Authentication > Get started > Sign-in method > Google > Enable.** Choose a support email.
3. **Project settings (gear) > Your apps > Add app > Web.** Register it; Firebase shows a config object.

## 2. Give the web app the settings
Copy `web/.env.example` to `web/.env.local` and fill in:
```
NEXT_PUBLIC_AUTH_MODE=firebase
NEXT_PUBLIC_FIREBASE_API_KEY=...        # apiKey
NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=...    # authDomain
NEXT_PUBLIC_FIREBASE_PROJECT_ID=...     # projectId
NEXT_PUBLIC_FIREBASE_APP_ID=...         # appId
MOCK_TRUST_FIREBASE=1                   # only while using the built-in mock API (see below)
```
These are public by design. Access is protected because the backend verifies every ID token. They are read **at build time**, so rebuild after changing them.

### Trying Google sign-in with the built-in mock API (no backend needed)
The mock API normally only accepts the dev picker's tokens, so a real Google sign-in would be rejected and bounce you back to the sign-in screen.
`MOCK_TRUST_FIREBASE=1` makes the mock accept Google tokens for your project. **It does not verify signatures**, so anyone could forge a sign-in:
that is fine on your own computer and the reason it must never be used on a shared or public server. A new Google user starts with no trip,
so you will be asked to add a train first (search for 12345 and add it).

### Using the real backend instead
Remove `MOCK_TRUST_FIREBASE`, set `BACKEND_URL=http://localhost:8080`, and run the Java API with `AUTH_MODE=firebase` and your project id (step 3).
The backend verifies signatures properly. Its posts, board and chat endpoints are not built yet, so those screens will show errors until they are.

## 3. Give the backend the project id
`AUTH_MODE=firebase` and `FIREBASE_PROJECT_ID=<your projectId>`. The backend downloads Google's public keys itself and checks the token's signature, issuer, audience and expiry.

## 4. Authorised domains
**Authentication > Settings > Authorised domains.** `localhost` is there by default. Add your real domain. **IP addresses are not allowed**, so testing from a phone over your Wi-Fi
(`http://192.168.x.x:3000`) will fail with "Sign-in isn't set up for this web address yet." Use a tunnel or a deployed preview instead (see `real-device-testing.md`).

## What is and is not tested
- **Tested:** the sign-in screen in Firebase mode, that every route is closed to signed-out visitors, plain-language errors, the "taking longer than usual" notice, fresh-token-per-request, and the backend's token verification with locally generated keys.
- **Not tested:** an actual Google account signing in, because that needs your project. After setup, try it once on a desktop browser and once on a real phone.
- On phones that block pop-ups, the app falls back to a full-page redirect. That path is written but has not been exercised on a device.
