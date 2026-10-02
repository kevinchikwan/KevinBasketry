import { useState, type FormEvent } from 'react';
import { ApiError, login } from '../api';

interface Props {
  notice?: string;
  initialEmail?: string;
  onLoggedIn: (token: string) => void;
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
      onLoggedIn(accessToken);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Something went wrong.');
      setSubmitting(false);
    }
  }

  return (
    <section className="card">
      <h1>Log in</h1>
      {notice && <p className="notice" role="status">{notice}</p>}
      <form onSubmit={handleSubmit}>
        <label>
          Email
          <input
            type="email"
            autoComplete="username"
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
      <p className="switch">
        No account?{' '}
        <button type="button" className="link" onClick={onGoToRegister}>
          Register
        </button>
      </p>
    </section>
  );
}
