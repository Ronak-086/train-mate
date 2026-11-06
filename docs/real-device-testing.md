# Testing on real phones

## What has been done
**Emulation only.** `npm run e2e:mobile` runs all 31 browser flows in Chromium configured as a Pixel 7: its screen size, touch input and mobile user agent. That catches layout and touch-handling
mistakes, but it is **not a real phone**. It cannot show: a real on-screen keyboard pushing the chat box, iOS Safari's own quirks (WebKit isn't installed where I worked), notch and home-indicator spacing,
real network conditions, or how Google's sign-in window behaves in a mobile browser.

## How to test on your own phone
1. **Same Wi-Fi, no sign-in:** run `npm run dev -- -H 0.0.0.0`, find your computer's IP, open `http://<ip>:3000` on the phone (dev sign-in works; allow port 3000 through your firewall).
2. **With Google sign-in:** Firebase refuses IP addresses. Expose the dev server on a real domain with a tunnel (Cloudflare Tunnel or ngrok), add that domain to Firebase's authorised domains, and open the tunnel URL.
3. **Many devices at once:** cloud device farms such as BrowserStack or LambdaTest give access to real iPhones and Androids.

## What to check, on at least one iPhone (Safari) and one Android (Chrome)
- Sign in with Google: pop-up or redirect works, you land on Home, sign-out works.
- Chat: tap the message box; the keyboard must not hide it or the Send button; sending scrolls to the newest message.
- The bottom tab bar stays put while scrolling and clears the iPhone's home indicator.
- Rotate to landscape and back; nothing is cut off.
- Browser back gesture and the app's back arrows land where you expect.
- Increase the system text size: nothing overlaps.
- Throttle to a slow connection: screens show loading placeholders, then content or a readable error, never a blank page.
- Airplane mode: errors are plain language, and the app recovers when the connection returns.
- Dark and light system themes (the app is dark either way).
- Tap targets feel big enough, and there are no accidental double-taps on Accept, Decline, Delete.
