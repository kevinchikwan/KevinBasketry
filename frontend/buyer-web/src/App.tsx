import { useState } from 'react';
import Dashboard from './screens/Dashboard';
import Login from './screens/Login';
import Register from './screens/Register';

type View =
  | { name: 'login'; notice?: string; email?: string }
  | { name: 'register' }
  | { name: 'dashboard'; token: string; email: string };

/**
 * Three screens, switched by state rather than a router. The token lives only
 * in this component's state: never in localStorage, so it is gone on reload
 * and not readable by other scripts on the origin.
 */
export default function App() {
  const [view, setView] = useState<View>({ name: 'login' });

  return (
    <main className="page">
      {view.name === 'login' && (
        <Login
          notice={view.notice}
          initialEmail={view.email}
          onLoggedIn={(token, email) => setView({ name: 'dashboard', token, email })}
          onGoToRegister={() => setView({ name: 'register' })}
        />
      )}
      {view.name === 'register' && (
        <Register
          onRegistered={(email) =>
            setView({ name: 'login', email, notice: 'Account created. You can log in now.' })
          }
          onGoToLogin={() => setView({ name: 'login' })}
        />
      )}
      {view.name === 'dashboard' && (
        <Dashboard token={view.token} email={view.email} onLogOut={() => setView({ name: 'login' })} />
      )}
    </main>
  );
}
