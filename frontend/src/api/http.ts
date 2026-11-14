/**
 * HTTP helpers for the Vue SPA. Write requests must carry the XSRF-TOKEN
 * cookie value in the X-XSRF-TOKEN header (Spring Security spa() mode);
 * the cookie value is the raw token, never the encoded token a body might
 * expose.
 */

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
  }
}

function xsrfCookieValue(): string | null {
  const match = document.cookie
    .split("; ")
    .find((c) => c.startsWith("XSRF-TOKEN="));
  return match ? decodeURIComponent(match.slice("XSRF-TOKEN=".length)) : null;
}

async function ensureCsrfCookie(): Promise<void> {
  if (xsrfCookieValue() === null) {
    await fetch("/api/v1/auth/csrf", { credentials: "same-origin" });
  }
}

export async function apiGet<T>(path: string): Promise<T> {
  const response = await fetch(path, { credentials: "same-origin" });
  if (!response.ok) {
    throw new ApiError(`接口返回 HTTP ${response.status}`, response.status);
  }
  return (await response.json()) as T;
}

export async function apiSend<T>(
  method: "POST" | "PUT" | "DELETE",
  path: string,
  body?: unknown,
): Promise<T> {
  await ensureCsrfCookie();
  const token = xsrfCookieValue();
  if (token === null) {
    throw new ApiError("缺少 CSRF 凭据，请刷新页面重试", 0);
  }
  const response = await fetch(path, {
    method,
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": token,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    let message = `接口返回 HTTP ${response.status}`;
    try {
      const payload = (await response.json()) as { message?: string };
      if (payload?.message) {
        message = payload.message;
      }
    } catch {
      // keep the generic message
    }
    throw new ApiError(message, response.status);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
