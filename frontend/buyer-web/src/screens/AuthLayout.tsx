import type { ReactNode } from 'react';

interface Props {
  title: string;
  footer: ReactNode;
  children: ReactNode;
}

/** Register and Login share this frame: brand header above the card, switch link below it. */
export default function AuthLayout({ title, footer, children }: Props) {
  return (
    <div className="stack">
      <header className="brand-header">
        <div className="brand">Basketry</div>
        <h1 className="subtitle">{title}</h1>
      </header>
      <section className="card">{children}</section>
      <p className="switch">{footer}</p>
    </div>
  );
}
