// CORS helper -- needed for submit-acquirer-onboarding/upload-acquirer-document
// specifically because they're the first Edge Functions in this repo ever
// called from a browser (gs-ssp-cmp). Every other function here is called
// from the Android app (Ktor, doesn't enforce CORS) or server-to-server by
// a payment gateway, so no existing pattern for this existed to follow --
// found the hard way when a real browser call's OPTIONS preflight hit
// submit-acquirer-onboarding and got a 405 (only POST was handled),
// silently failing as a generic "Failed to send a request to the Edge
// Function" on the client with no server-side error logged at all.
export const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

// Call first in any Deno.serve handler a browser calls directly; returns a
// response to send immediately if this was the preflight, otherwise null.
export function handleCorsPreflight(req: Request): Response | null {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  return null;
}

// Every real response also needs Access-Control-Allow-Origin -- the
// preflight passing isn't enough on its own for the browser to accept the
// actual response.
export function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}
