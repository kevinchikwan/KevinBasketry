import { describe, expect, it } from 'vitest';
import { meetsPolicy, PASSWORD_RULES } from './passwordPolicy';

// Same inputs as identity-service's AccountRegistrationTests, so the checklist
// and the server agree on every case.

function unmetRules(password: string): string[] {
  return PASSWORD_RULES.filter((rule) => !rule.isMet(password)).map((rule) => rule.id);
}

describe('password policy', () => {
  it.each([
    ['Abcdef1!', 'meets all rules'],
    ['Abcde1!x', 'exactly 8 characters'],
    ['Abcde1!xyzabcde', 'exactly 15 characters'],
    ['Abcde1!xyzabcd\u{1F600}', '15 code points including an emoji (16 UTF-16 units)'],
  ])('accepts %s (%s)', (password) => {
    expect(unmetRules(password)).toEqual([]);
    expect(meetsPolicy(password)).toBe(true);
  });

  it.each([
    ['Abcd1!x', 'length', 'too short (7)'],
    ['Abcde1!xyzabcdef', 'length', 'too long (16)'],
    ['Abcde1!xyzabcde\u{1F600}', 'length', 'too long (16 code points)'],
    ['abcdef1!', 'upper', 'no uppercase'],
    ['ABCDEF1!', 'lower', 'no lowercase'],
    ['Abcdefg!', 'digit', 'no digit'],
    ['Abcdefg1', 'special', 'no special character'],
    ['Abcdef1?.', 'special', 'special character not from the allowed list'],
  ])('rejects %s on rule %s (%s)', (password, rule) => {
    expect(unmetRules(password)).toEqual([rule]);
    expect(meetsPolicy(password)).toBe(false);
  });

  it('reports every failed rule at once', () => {
    expect(unmetRules('abc')).toEqual(['length', 'upper', 'digit', 'special']);
  });
});
