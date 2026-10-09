import test from 'node:test';
import assert from 'node:assert/strict';
import {money, quoteLabel} from '../src/format.js';

test('money distinguishes missing values from a real zero', () => {
  assert.equal(money(null), 'Unavailable');
  assert.match(money(0), /0\.00/);
});

test('quote labels identify end-of-day data and retrieval time', () => {
  const label = quoteLabel({price: '10', classification: 'END_OF_DAY', provider: 'Alpha Vantage', retrievedAt: '2026-01-01T12:00:00Z'});
  assert.match(label, /End of day/);
  assert.match(label, /Alpha Vantage/);
  assert.match(label, /retrieved/);
});

test('missing quote is explicitly unavailable', () => {
  assert.equal(quoteLabel(null), 'Price unavailable');
});

test('stale quotes are called out in the freshness label', () => {
  assert.match(quoteLabel({price: '10', stale: true, provider: 'Alpha Vantage'}), /^STALE/);
});
