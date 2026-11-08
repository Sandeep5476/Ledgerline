/**
 * Formats a paise amount into Indian Rupees (INR) with standard symbol and two decimal places.
 * Strictly uses integer and string operations; NEVER uses floating-point arithmetic.
 *
 * @param {number|string|bigint} paise
 * @returns {string} Formatted Rupee string, e.g. "₹1,250.50"
 */
export function formatPaise(paise) {
  if (paise === null || paise === undefined || paise === '') return '₹0.00';
  const bigPaise = BigInt(paise);
  const isNegative = bigPaise < 0n;
  const abs = isNegative ? -bigPaise : bigPaise;
  const rupees = abs / 100n;
  const remainder = abs % 100n;

  // Format integer rupees with commas using Indian numbering scheme
  const formattedRupees = Number(rupees).toLocaleString('en-IN');
  const formattedPaise = remainder.toString().padStart(2, '0');

  return `${isNegative ? '-' : ''}₹${formattedRupees}.${formattedPaise}`;
}

/**
 * Parses user rupee input (e.g. "120.50" or "500") into integer paise (minor units).
 * Strictly avoids floating point multiplication by splitting on '.' and padding.
 *
 * @param {string} rupeesStr
 * @returns {number} Integer paise
 */
export function parseRupeesToPaise(rupeesStr) {
  if (!rupeesStr) throw new Error('Amount is required');
  const clean = rupeesStr.trim();
  if (!/^\d+(\.\d{1,2})?$/.test(clean)) {
    throw new Error('Please enter a valid positive amount (e.g. 100 or 150.50)');
  }
  const parts = clean.split('.');
  const whole = BigInt(parts[0]);
  const decimal = parts[1] ? parts[1].padEnd(2, '0') : '00';
  const total = whole * 100n + BigInt(decimal.slice(0, 2));
  if (total <= 0n) {
    throw new Error('Amount must be greater than zero');
  }
  return Number(total);
}

/**
 * Generates a standard UUID v4 for idempotency keys.
 */
export function generateIdempotencyKey() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return 'key-' + Math.random().toString(36).substring(2, 15) + '-' + Date.now();
}
