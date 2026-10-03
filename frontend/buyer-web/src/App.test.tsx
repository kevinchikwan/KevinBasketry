import { render, screen, within } from '@testing-library/react';
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

async function fillAndSubmit(email: string, password: string, buttonName: RegExp, confirmPassword?: string) {
  const user = userEvent.setup();
  await user.clear(screen.getByLabelText('Email'));
  await user.type(screen.getByLabelText('Email'), email);
  await user.type(screen.getByLabelText('Password'), password);
  if (confirmPassword !== undefined) {
    await user.type(screen.getByLabelText(/^Confirm password/), confirmPassword);
  }
  await user.click(screen.getByRole('button', { name: buttonName }));
}

describe('Register', () => {
  it('posts only email and password to /accounts/register, then shows Login with the email filled in', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 201 }));
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: 'Create an account' }));

    await fillAndSubmit('new@example.com', 'Corr3ct-Horse!', /^Create account$/, 'Corr3ct-Horse!');

    // Exact body: confirm password is a UI-only check and is never sent.
    expect(fetchMock).toHaveBeenCalledWith(`${API}/accounts/register`, expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ email: 'new@example.com', password: 'Corr3ct-Horse!' }),
    }));
    expect(await screen.findByRole('heading', { name: 'Log in to your account' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Account created');
    expect(screen.getByLabelText('Email')).toHaveValue('new@example.com');
  });

  it('shows the server problem detail on a conflict', async () => {
    fetchMock.mockResolvedValueOnce(problem(409, 'An account with this email already exists.'));
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: 'Create an account' }));

    await fillAndSubmit('taken@example.com', 'Corr3ct-Horse!', /^Create account$/, 'Corr3ct-Horse!');

    expect(await screen.findByRole('alert')).toHaveTextContent('An account with this email already exists.');
    expect(screen.getByRole('heading', { name: 'Create your buyer account' })).toBeInTheDocument();
  });

  it('shows an inline error and does not call the API when the passwords differ', async () => {
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: 'Create an account' }));

    await fillAndSubmit('new@example.com', 'Corr3ct-Horse!', /^Create account$/, 'Corr3ct-Hosre!');

    expect(screen.getByRole('alert')).toHaveTextContent('Passwords do not match.');
    expect(screen.getByLabelText(/^Confirm password/)).toHaveAttribute('aria-invalid', 'true');
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByRole('heading', { name: 'Create your buyer account' })).toBeInTheDocument();
  });

  it('clears the mismatch error on edit and submits once the passwords match', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 201 }));
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole('button', { name: 'Create an account' }));
    await fillAndSubmit('new@example.com', 'Corr3ct-Horse!', /^Create account$/, 'Corr3ct-Hosre!');

    const confirm = screen.getByLabelText(/^Confirm password/);
    await user.clear(confirm);
    expect(screen.queryByText('Passwords do not match.')).not.toBeInTheDocument();
    await user.type(confirm, 'Corr3ct-Horse!');
    await user.click(screen.getByRole('button', { name: /^Create account$/ }));

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(`${API}/accounts/register`, expect.objectContaining({
      body: JSON.stringify({ email: 'new@example.com', password: 'Corr3ct-Horse!' }),
    }));
    expect(await screen.findByRole('heading', { name: 'Log in to your account' })).toBeInTheDocument();
  });

  it('shows the password checklist before anything is typed, then ticks rules off live', async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole('button', { name: 'Create an account' }));

    const checklist = screen.getByRole('list', { name: 'Password requirements' });
    expect(metRules(checklist)).toEqual([]);
    expect(within(checklist).getAllByRole('listitem')).toHaveLength(5);

    await user.type(screen.getByLabelText('Password'), 'abc');
    expect(metRules(checklist)).toEqual(['lower']);

    await user.type(screen.getByLabelText('Password'), 'D1');
    expect(metRules(checklist)).toEqual(['upper', 'lower', 'digit']);

    await user.type(screen.getByLabelText('Password'), '!xyz');
    expect(metRules(checklist)).toEqual(['length', 'upper', 'lower', 'digit', 'special']);
  });

  it('blocks submit and flags unmet rules when the password fails the policy', async () => {
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: 'Create an account' }));

    await fillAndSubmit('new@example.com', 'weakpass', /^Create account$/, 'weakpass');

    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent("Password doesn't meet all the requirements");
    expect(screen.getByLabelText('Password')).toHaveAttribute('aria-invalid', 'true');
    const failed = [...document.querySelectorAll('.rule-failed')].map((li) => li.getAttribute('data-rule'));
    expect(failed).toEqual(['upper', 'digit', 'special']);
  });

  it('shows the server rule message if the server still rejects the password', async () => {
    fetchMock.mockResolvedValueOnce(problem(400, 'Password must contain a digit (0-9).'));
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: 'Create an account' }));

    await fillAndSubmit('new@example.com', 'Corr3ct-Horse!', /^Create account$/, 'Corr3ct-Horse!');

    expect(await screen.findByRole('alert')).toHaveTextContent('Password must contain a digit (0-9).');
  });
});

function metRules(checklist: HTMLElement): string[] {
  return within(checklist)
    .getAllByRole('listitem')
    .filter((li) => li.getAttribute('data-met') === 'true')
    .map((li) => li.getAttribute('data-rule')!);
}

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
    expect(await screen.findByRole('heading', { name: 'buyer@example.com' })).toBeInTheDocument();
    const claims = JSON.parse(document.querySelector('pre.code')!.textContent!);
    expect(claims).toEqual({ sub: '7', capabilities: ['BUYER', 'MERCHANT'], exp });
  });

  it('shows the signed-in email and a Signed in badge on the dashboard', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { accessToken: token, tokenType: 'Bearer', expiresIn: 3600 }));
    render(<App />);

    await fillAndSubmit('buyer@example.com', 'correct-horse', /^Log in$/);

    expect(await screen.findByRole('heading', { name: 'buyer@example.com' })).toBeInTheDocument();
    expect(screen.getByText(/Signed in/)).toBeInTheDocument();
  });

  it('keeps the token in memory only', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { accessToken: token, tokenType: 'Bearer', expiresIn: 3600 }));
    render(<App />);

    await fillAndSubmit('buyer@example.com', 'correct-horse', /^Log in$/);
    await screen.findByRole('heading', { name: 'buyer@example.com' });

    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(document.cookie).not.toContain(token);
  });

  it('shows the server problem detail on a rejected login', async () => {
    fetchMock.mockResolvedValueOnce(problem(401, 'Invalid email or password.'));
    render(<App />);

    await fillAndSubmit('buyer@example.com', 'wrong-password', /^Log in$/);

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.');
    expect(screen.queryByRole('heading', { name: 'buyer@example.com' })).not.toBeInTheDocument();
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
    await screen.findByRole('heading', { name: 'buyer@example.com' });

    await userEvent.click(screen.getByRole('button', { name: 'Log out' }));

    expect(screen.getByRole('heading', { name: 'Log in to your account' })).toBeInTheDocument();
    expect(screen.queryByText(/"sub"/)).not.toBeInTheDocument();
  });
});
