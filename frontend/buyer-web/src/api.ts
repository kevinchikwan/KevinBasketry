// Calls to identity-service. Errors come back as RFC 9457 problem details;
// the `detail` field is the human-readable message.

const BASE_URL: string = import.meta.env.VITE_IDENTITY_API_URL ?? 'http://localhost:8080';

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

async function postJson(path: string, body: unknown): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'Could not reach the server. Is identity-service running?');
  }
  if (!response.ok) {
    throw new ApiError(response.status, await problemDetail(response));
  }
  return response;
}

async function problemDetail(response: Response): Promise<string> {
  try {
    const problem = await response.json();
    if (typeof problem?.detail === 'string' && problem.detail) {
      return problem.detail;
    }
  } catch {
    // Not JSON; fall through to the generic message.
  }
  return `Request failed (${response.status}).`;
}

export async function register(email: string, password: string): Promise<void> {
  await postJson('/accounts/register', { email, password });
}

export async function login(email: string, password: string): Promise<LoginResponse> {
  const response = await postJson('/auth/login', { email, password });
  return response.json();
}
