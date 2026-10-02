import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

const API = 'http://localhost:8080';

/** An unsigned JWT with the given payload; the dashboard only decodes, never verifies. */
function fakeJwt(payload: object): string {
  const encode = (obj: object) =>
    btoa(JSON.stringify(obj)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${encode({ alg: 'RS256', typ: 'JWT' })}.${encode(payload)}.signature`;
}

function jsonResponse(status: number, body: unknown, contentType = 'application/json') {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

function problem(status: number, detail: string) {
  return jsonResponse(status, { status, detail }, 'application/problem+json');
}

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  localStorage.clear();
  sessionStorage.clear();
});

afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});

async function fillAndSubmit(email: string, password: string, buttonName: RegExp) {
  const user = userEvent.setup();
  await user.clear(screen.getByLabelText('Email'));
  await user.type(screen.getByLabelText('Email'), email);
  await user.type(screen.getByLabelText(/^Password/), password);
  await user.click(screen.getByRole('button', { name: buttonName }));
}

describe('Register', () => {
  it('posts to /accounts/register, then shows Login with the email filled in', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 201 }));
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: 'Register' }));

    await fillAndSubmit('new@example.com', 'correct-horse', /^Register$/);

    expect(fetchMock).toHaveBeenCalledWith(`${API}/accounts/register`, expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ email: 'new@example.com', password: 'correct-horse' }),
    }));
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Account created');
    expect(screen.getByLabelText('Email')).toHaveValue('new@example.com');
  });

  it('shows the server problem detail on a conflict', async () => {
    fetchMock.mockResolvedValueOnce(problem(409, 'An account with this email already exists.'));
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: 'Register' }));

    await fillAndSubmit('taken@example.com', 'correct-horse', /^Register$/);

    expect(await screen.findByRole('alert')).toHaveTextContent('An account with this email already exists.');
    expect(screen.getByRole('heading', { name: 'Create an account' })).toBeInTheDocument();
  });
});

describe('Login', () => {
  const exp = 1_900_000_000;
  const token = fakeJwt({ sub: '7', capabilities: ['BUYER', 'MERCHANT'], merchantId: 42, iat: exp - 3600, exp });

  it('posts to /auth/login and shows sub, capabilities and exp on the dashboard', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { accessToken: token, tokenType: 'Bearer', expiresIn: 3600 }));
    render(<App />);

    await fillAndSubmit('buyer@example.com', 'correct-horse', /^Log in$/);

    expect(fetchMock).toHaveBeenCalledWith(`${API}/auth/login`, expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ email: 'buyer@example.com', password: 'correct-horse' }),
    }));
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    const claims = JSON.parse(document.querySelector('pre.code')!.textContent!);
    expect(claims).toEqual({ sub: '7', capabilities: ['BUYER', 'MERCHANT'], exp });
  });

  it('keeps the token in memory only', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { accessToken: token, tokenType: 'Bearer', expiresIn: 3600 }));
    render(<App />);

    await fillAndSubmit('buyer@example.com', 'correct-horse', /^Log in$/);
    await screen.findByRole('heading', { name: 'Dashboard' });

    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(document.cookie).not.toContain(token);
  });

  it('shows the server problem detail on a rejected login', async () => {
    fetchMock.mockResolvedValueOnce(problem(401, 'Invalid email or password.'));
    render(<App />);

    await fillAndSubmit('buyer@example.com', 'wrong-password', /^Log in$/);

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.');
    expect(screen.queryByRole('heading', { name: 'Dashboard' })).not.toBeInTheDocument();
  });

  it('explains when the server cannot be reached', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    render(<App />);

    await fillAndSubmit('buyer@example.com', 'correct-horse', /^Log in$/);

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the server');
  });

  it('Log out returns to Login and the dashboard is gone', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { accessToken: token, tokenType: 'Bearer', expiresIn: 3600 }));
    render(<App />);
    await fillAndSubmit('buyer@example.com', 'correct-horse', /^Log in$/);
    await screen.findByRole('heading', { name: 'Dashboard' });

    await userEvent.click(screen.getByRole('button', { name: 'Log out' }));

    expect(screen.getByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.queryByText(/"sub"/)).not.toBeInTheDocument();
  });
});
