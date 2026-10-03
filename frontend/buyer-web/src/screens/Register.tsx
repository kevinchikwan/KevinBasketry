import { useState, type FormEvent } from 'react';
import { ApiError, register } from '../api';
import { meetsPolicy, PASSWORD_RULES } from '../passwordPolicy';
import AuthLayout from './AuthLayout';
import PasswordField from './PasswordField';

interface Props {
  onRegistered: (email: string) => void;
  onGoToLogin: () => void;
}

/**
 * Buyer self-registration. The password checklist shows the policy before
 * submitting; the server re-checks it and is authoritative. Confirm password
 * is a UI-only typo check: it is never sent.
 */
export default function Register({ onRegistered, onGoToLogin }: Props) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [mismatch, setMismatch] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // Until the first submit the checklist is neutral guidance; after it, unmet rules turn red.
  const policyError = submitAttempted && !meetsPolicy(password);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitAttempted(true);
    const passwordsDiffer = password !== confirmPassword;
    setMismatch(passwordsDiffer);
    if (!meetsPolicy(password) || passwordsDiffer) {
      return;
    }
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
    <AuthLayout
      title="Create your buyer account"
      footer={
        <>
          Already have an account?{' '}
          <button type="button" className="link" onClick={onGoToLogin}>
            Log in
          </button>
        </>
      }
    >
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
        <div className="field">
          <PasswordField
            label="Password"
            autoComplete="new-password"
            placeholder="At least 8 characters"
            required
            value={password}
            aria-invalid={policyError}
            aria-describedby="password-rules"
            onChange={(e) => {
              setPassword(e.target.value);
              setMismatch(false);
            }}
          />
          <ul id="password-rules" className="rules" aria-label="Password requirements">
            {PASSWORD_RULES.map((rule) => {
              const met = rule.isMet(password);
              const state = met ? 'met' : policyError ? 'failed' : 'pending';
              return (
                <li key={rule.id} className={`rule rule-${state}`} data-rule={rule.id} data-met={met}>
                  <span className="rule-icon" aria-hidden="true">{met ? '✓' : '○'}</span>
                  {rule.label}
                  <span className="visually-hidden">{met ? ' (met)' : ' (not met)'}</span>
                </li>
              );
            })}
          </ul>
          {policyError && (
            <span className="field-error" role="alert">
              Password doesn't meet all the requirements above.
            </span>
          )}
        </div>
        <PasswordField
          label="Confirm password"
          autoComplete="new-password"
          placeholder="Repeat your password"
          required
          value={confirmPassword}
          aria-invalid={mismatch}
          aria-describedby={mismatch ? 'confirm-password-error' : undefined}
          onChange={(e) => {
            setConfirmPassword(e.target.value);
            setMismatch(false);
          }}
        >
          {mismatch && (
            <span id="confirm-password-error" className="field-error" role="alert">
              Passwords do not match.
            </span>
          )}
        </PasswordField>
        {error && <p className="error" role="alert">{error}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? 'Creating account…' : 'Create account'}
        </button>
      </form>
    </AuthLayout>
  );
}
