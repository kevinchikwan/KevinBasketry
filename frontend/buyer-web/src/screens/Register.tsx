import { useState, type FormEvent } from 'react';
import { ApiError, register } from '../api';

interface Props {
  onRegistered: (email: string) => void;
  onGoToLogin: () => void;
}

/** Buyer self-registration. The server enforces the rules; minLength just saves a round trip. */
export default function Register({ onRegistered, onGoToLogin }: Props) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await register(email, password);
      onRegistered(email);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Something went wrong.');
      setSubmitting(false);
    }
  }

  return (
    <section className="card">
      <h1>Create an account</h1>
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
            autoComplete="new-password"
            required
            minLength={8}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <span className="hint">At least 8 characters.</span>
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? 'Creating account…' : 'Register'}
        </button>
      </form>
      <p className="switch">
        Already registered?{' '}
        <button type="button" className="link" onClick={onGoToLogin}>
          Log in
        </button>
      </p>
    </section>
  );
}
