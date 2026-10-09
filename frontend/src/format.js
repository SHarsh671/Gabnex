export function money(value, currency = 'USD') {
  if (value === null || value === undefined) return 'Unavailable';
  return new Intl.NumberFormat(undefined, {style: 'currency', currency, maximumFractionDigits: 2}).format(Number(value));
}

export function quoteLabel(quote) {
  if (!quote || quote.price == null) return 'Price unavailable';
  const when = quote.retrievedAt ? new Date(quote.retrievedAt).toLocaleString() : 'time unavailable';
  return `${quote.stale ? 'STALE · ' : ''}${quote.classification === 'END_OF_DAY' ? 'End of day' : 'Quote'} · ${quote.provider || 'Unknown source'} · retrieved ${when}`;
}
