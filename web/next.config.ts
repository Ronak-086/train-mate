import type { NextConfig } from "next";

// When BACKEND_URL is set, /api/* is proxied to the real Spring Boot API. Without it, the built-in
// mock API (src/app/api) answers instead, which is what development and the browser tests use.
const backend = process.env.BACKEND_URL?.replace(/\/+$/, "");

const nextConfig: NextConfig = {
  // Lets a second build (e.g. Firebase sign-in mode in the browser tests) live beside the normal one.
  distDir: process.env.NEXT_DIST_DIR || ".next",
  // Cache Components / partial prefetching are intentionally off: the app is per-user and
  // live (status, feed, chat), and they force Suspense around every route that reads params.
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  async rewrites() {
    return backend ? { beforeFiles: [{ source: "/api/:path*", destination: `${backend}/api/:path*` }], afterFiles: [], fallback: [] } : [];
  },
};

export default nextConfig;
