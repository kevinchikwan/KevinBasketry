import { useState, type FormEvent } from 'react';
import { ApiError, login } from '../api';
import AuthLayout from './AuthLayout';

interface Props {
  notice?: string;
  initialEmail?: string;
  onLoggedIn: (token: string, email: string) => void;
  onGoToRegister: () => void;
}

export default function Login({ notice, initialEmail = '', onLoggedIn, onGoToRegister }: Props) {
  const [email, setEmail] = useState(initialEmail);
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const { accessToken } = await login(email, password);
      onLoggedIn(accessToken, email);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Something went wrong.');
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      title="Log in to your account"
      footer={
        <>
          New to Basketry?{' '}
          <button type="button" className="link" onClick={onGoToRegister}>
            Create an account
          </button>
        </>
      }
    >
      {notice && <p className="notice" role="status">{notice}</p>}
      <form onSubmit={handleSubmit}>
        <label>
          Email
          <input
            type="email"
            autoComplete="username"
            placeholder="you@example.com"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </label>
        <label>
          Password
          <input
            type="password"
            autoComplete="current-password"
            placeholder="Your password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? 'Logging in…' : 'Log in'}
        </button>
      </form>
    </AuthLayout>
  );
}
