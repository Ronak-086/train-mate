import { defineConfig, devices } from "@playwright/test";

const PORT = 3100;
const FIREBASE_PORT = 3101;
const STACK_PORT = 3102;
const API_PORT = 8081;
// The full-stack servers need Java, PostgreSQL and Redis, so they only start when that project is asked for (npm run e2e:backend).
// The project itself is always defined, because test workers load this file without the command-line flag.
const fullStack = process.argv.some((a) => a.includes("full-stack"));

export default defineConfig({
  workers: 1, // one shared mock server per project, reset before every test
  fullyParallel: false,
  timeout: 40_000,
  expect: { timeout: 10_000 },
  reporter: [["list"]],
  use: {
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    locale: "en-IN",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: [
    ...(fullStack
      ? [
          {
            // The Java API on the plain JDK server, against a real PostgreSQL and Redis.
            command: "sh ../backend/scripts/run-dev.sh",
            url: `http://localhost:${API_PORT}/api/health`,
            reuseExistingServer: true,
            timeout: 120_000,
            env: {
              PORT: String(API_PORT), AUTH_MODE: "dev", DEV_TOOLS: "1", MIGRATE: "1",
              DB_URL: process.env.DB_URL ?? "jdbc:postgresql://localhost:5432/trainmate_dev",
              DB_USER: process.env.DB_USER ?? "trainmate", DB_PASSWORD: process.env.DB_PASSWORD ?? "trainmate",
              REDIS_URL: process.env.REDIS_URL ?? "redis://localhost:6379",
            },
          },
          {
            // The web app built to proxy /api to that Java API instead of using its built-in mock.
            command: `npm run build && npx next start -p ${STACK_PORT}`,
            url: `http://localhost:${STACK_PORT}/api/health`,
            reuseExistingServer: true,
            timeout: 240_000,
            env: { NEXT_DIST_DIR: ".next-backend", BACKEND_URL: `http://localhost:${API_PORT}` },
          },
        ]
      : []),
    {
      command: `npm run build && npx next start -p ${PORT}`,
      url: `http://localhost:${PORT}/api/health`,
      reuseExistingServer: true,
      timeout: 240_000,
      env: { MOCK_DEV_ROUTES: "1" }, // enables /api/dev/reset and /api/dev/clock in the production build
    },
    {
      // Same app built for real Google sign-in, with placeholder settings (no real Firebase project needed).
      command: `npm run build && npx next start -p ${FIREBASE_PORT}`,
      url: `http://localhost:${FIREBASE_PORT}/login`,
      reuseExistingServer: true,
      timeout: 240_000,
      env: {
        NEXT_DIST_DIR: ".next-firebase",
        NEXT_PUBLIC_AUTH_MODE: "firebase",
        NEXT_PUBLIC_FIREBASE_API_KEY: "placeholder-api-key",
        NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN: "placeholder.firebaseapp.com",
        NEXT_PUBLIC_FIREBASE_PROJECT_ID: "placeholder",
        NEXT_PUBLIC_FIREBASE_APP_ID: "1:0:web:0",
      },
    },
  ],
  projects: [
    { name: "full-stack", testDir: "e2e-backend", use: { browserName: "chromium", baseURL: `http://localhost:${STACK_PORT}` } },
    { name: "chromium", testDir: "e2e", use: { browserName: "chromium", baseURL: `http://localhost:${PORT}` } },
    {
      // EMULATED Android phone (Pixel 7 screen, touch, mobile user agent) running the same flows. Not a real device:
      // see docs/real-device-testing.md. Hover tests are skipped because phones have no hover.
      name: "mobile-chrome",
      testDir: "e2e",
      testIgnore: /hover/,
      use: { ...devices["Pixel 7"], baseURL: `http://localhost:${PORT}` },
    },
    { name: "firebase-mode", testDir: "e2e-firebase", use: { browserName: "chromium", baseURL: `http://localhost:${FIREBASE_PORT}` } },
  ],
});
