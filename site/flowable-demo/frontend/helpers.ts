export function orderLabel(number: string, company: string): string {
  return `${number} — ${company}`;
}

export function formatIban(raw: string): string {
  return raw.replace(/(.{4})/g, '$1 ').trim();
}
