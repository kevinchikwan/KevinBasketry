import { jwtDecode } from 'jwt-decode';

interface Props {
  token: string;
  onLogOut: () => void;
}

interface Claims {
  sub: string;
  capabilities: string[];
  exp: number;
}

/**
 * Shows the token's claims, decoded client-side. Decoding does not verify the
 * signature; that is the backend's job when the token is presented.
 */
export default function Dashboard({ token, onLogOut }: Props) {
  const { sub, capabilities, exp } = jwtDecode<Claims>(token);

  return (
    <section className="card">
      <h1>Dashboard</h1>
      <p>You are logged in. Your token's claims:</p>
      <pre className="code">{JSON.stringify({ sub, capabilities, exp }, null, 2)}</pre>
      <p className="hint">Expires {new Date(exp * 1000).toLocaleString()}</p>
      <p className="switch">
        {/* Logout is client-side only: dropping the token is all there is (DESIGN.md Section 2). */}
        <button type="button" className="link" onClick={onLogOut}>
          Log out
        </button>
      </p>
    </section>
  );
}
