import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { generateV3Signature } from "../_shared/wizarpos-crypto.ts";

const WIZARPOS_PORTAL_BASE = "https://portal.paywizard.biz/ovstrade/openPay";
const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

serve(async (req: Request) => {
  // 1. Handle CORS preflight
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const { endpoint, payload } = await req.json();

    // Safety: Only allow specific WizarPOS endpoints
    const allowedEndpoints = ["doTransaction", "queryOrder", "quickPay", "pushParams"];
    if (!allowedEndpoints.includes(endpoint)) {
      return new Response(JSON.stringify({ error: "forbidden_endpoint" }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const appId = Deno.env.get("WIZARPOS_APP_ID");
    const appSecret = Deno.env.get("WIZARPOS_APP_SECRET");

    if (!appId || !appSecret) {
      return new Response(JSON.stringify({ error: "server_config_missing" }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // 2. Build signed payload
    const signedParams = {
      ...payload,
      clientId: appId,
    };

    const signature = await generateV3Signature(signedParams, appSecret);
    signedParams.sign = signature;

    // 3. Forward to WizarPOS
    const targetUrl = `${WIZARPOS_PORTAL_BASE}/${endpoint}`;
    console.log(`[wizarpos-proxy] Forwarding to ${targetUrl}`);

    const response = await fetch(targetUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        // Note: For some APIs, a jwt-token header might be required.
        // If needed, implement a /login call to get a token and cache it here.
      },
      body: JSON.stringify(signedParams),
    });

    const data = await response.json();
    console.log(`[wizarpos-proxy] Received response from WizarPOS:`, data);

    return new Response(JSON.stringify(data), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });

  } catch (e) {
    console.error(`[wizarpos-proxy] Error:`, e);
    return new Response(JSON.stringify({ error: "internal_error", message: e.message }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
