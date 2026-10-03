import { jwtDecode } from 'jwt-decode';

interface Props {
  token: string;
  email: string;
  onLogOut: () => void;
}

interface Claims {
  sub: string;
  capabilities: string[];
  exp: number;
}

/**
 * Shows the token's claims, decoded client-side. Decoding does not verify the
 * signature; that is the backend's job when the token is presented. The email
 * comes from the login form, since the token's `sub` is the account id.
 */
export default function Dashboard({ token, email, onLogOut }: Props) {
  const { sub, capabilities, exp } = jwtDecode<Claims>(token);

  return (
    <div className="stack stack-wide">
      <header className="brand-row">
        <div className="brand">Basketry</div>
        <span className="badge">● Signed in</span>
      </header>
      <section className="card card-dashboard">
        <div>
          <h1 className="account-email">{email}</h1>
          <p className="account-hint">Authenticated via POST /auth/login — identity-service issued an RS256 JWT.</p>
        </div>
        <pre className="code">{JSON.stringify({ sub, capabilities, exp }, null, 2)}</pre>
        <p className="footnote">Expires {new Date(exp * 1000).toLocaleString()}</p>
      </section>
      {/* Logout is client-side only: dropping the token is all there is (DESIGN.md Section 2). */}
      <button type="button" className="link link-muted" onClick={onLogOut}>
        Log out
      </button>
    </div>
  );
}
