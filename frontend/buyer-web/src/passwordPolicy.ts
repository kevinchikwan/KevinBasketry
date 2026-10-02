// The password policy (REQUIREMENTS.md Section 4). Mirrors identity-service's
// PasswordPolicy.java rule for rule: ASCII letters and digits, length counted in
// code points. The server is authoritative; this only lets the user see the
// rules before submitting.

export const SPECIAL_CHARACTERS = '!@#$%^&*()-_=+';

export interface PasswordRule {
  id: string;
  label: string;
  isMet: (password: string) => boolean;
}

const length = (password: string) => [...password].length;
const containsAny = (password: string, chars: string) => [...password].some((c) => chars.includes(c));

export const PASSWORD_RULES: readonly PasswordRule[] = [
  { id: 'length', label: '8–15 characters', isMet: (p) => length(p) >= 8 && length(p) <= 15 },
  { id: 'upper', label: 'An uppercase letter (A–Z)', isMet: (p) => /[A-Z]/.test(p) },
  { id: 'lower', label: 'A lowercase letter (a–z)', isMet: (p) => /[a-z]/.test(p) },
  { id: 'digit', label: 'A digit (0–9)', isMet: (p) => /[0-9]/.test(p) },
  {
    id: 'special',
    label: `A special character: ${SPECIAL_CHARACTERS}`,
    isMet: (p) => containsAny(p, SPECIAL_CHARACTERS),
  },
];

export function meetsPolicy(password: string): boolean {
  return PASSWORD_RULES.every((rule) => rule.isMet(password));
}
