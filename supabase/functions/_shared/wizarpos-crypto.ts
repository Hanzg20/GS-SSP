/**
 * WizarPOS PayWizard V3 Signature implementation for Supabase Edge Functions (Deno).
 * Used to sign requests to the WizarPOS Open Platform Portal.
 */

/**
 * Generates an HMAC-SHA256 signature for WizarPOS V3 protocol.
 * @param params The request parameters (excluding 'sign').
 * @param appSecret The developer secret key.
 * @returns Upper-case Hex string of the HMAC-SHA256 signature.
 */
export async function generateV3Signature(
  params: Record<string, any>,
  appSecret: string
): Promise<string> {
  // 1. Filter out null/undefined and 'sign'
  const filtered = Object.keys(params)
    .filter((key) => key !== "sign" && params[key] !== undefined && params[key] !== null)
    .reduce((obj: any, key) => {
      obj[key] = params[key];
      return obj;
    }, {});

  // 2. Sort keys alphabetically
  const sortedKeys = Object.keys(filtered).sort();

  // 3. Build query string: k1=v1&k2=v2...&appSecret=SECRET
  const queryString = sortedKeys
    .map((key) => `${key}=${filtered[key]}`)
    .join("&");

  const baseString = `${queryString}&appSecret=${appSecret}`;

  // 4. HMAC-SHA256 calculation using Web Crypto API
  const encoder = new TextEncoder();
  const keyData = encoder.encode(appSecret);
  const messageData = encoder.encode(baseString);

  const key = await crypto.subtle.importKey(
    "raw",
    keyData,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signature = await crypto.subtle.sign("HMAC", key, messageData);

  // 5. Convert to Upper-case Hex
  return Array.from(new Uint8Array(signature))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("")
    .toUpperCase();
}
