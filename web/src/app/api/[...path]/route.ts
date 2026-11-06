import { handle } from "@/server/mock/router";

/**
 * Development stand-in for the Spring Boot API: same paths, same JSON. In production the
 * app proxies /api/* to the real backend (a rewrite in next.config), and this folder is removed.
 */
export const dynamic = "force-dynamic";

export const GET = handle;
export const POST = handle;
export const PATCH = handle;
export const DELETE = handle;
