// Emails CRITICAL terminal alerts (app_error_logs) to operations.
//
// Trigger: a Supabase Database Webhook on app_error_logs INSERT that POSTs the
// standard webhook payload ({type, table, record, ...}) here with header
// `x-alert-secret: <ALERT_WEBHOOK_SECRET>`. verify_jwt is OFF (config.toml):
// the caller is the database, not a signed-in user, so that shared secret is
// the only thing keeping this endpoint from being used to send mail.
//
// Mail goes out over SMTP -- the same account configured for Supabase Auth
// emails, but Auth's SMTP settings aren't readable from functions, so the
// same values are set again as function secrets:
//   SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS, SMTP_FROM
//   ALERT_EMAIL_TO   (comma-separated; default service@goldsky.ca)
//   ALERT_WEBHOOK_SECRET
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import nodemailer from "npm:nodemailer@6.9.14";

// Same plain-words explanations CMP shows (DeviceAlertsPanel).
const DESCRIPTIONS: Record<string, string> = {
  VOID_AND_REFUND_FAILED: "出货失败且自动撤销/退款均失败：客户已扣款，需人工退款",
  HARDWARE_PULSE_FAIL: "洗车出货失败（已尝试自动撤销）",
  HARDWARE_PARTIAL_DISPENSE: "洗车只出了部分脉冲：客户服务不足额，需人工处理",
  TIMER_OUTPUT_START_FAIL: "按时服务未能启动（已尝试自动撤销）",
  PENDING_APPROVED_REVERSAL_FAILED: "崩溃遗留的已扣款交易自动撤销失败：需人工退款",
  PENDING_UNRESOLVED: "交易状态超过 24 小时无法确认：需人工核对",
  BATCH_CLOSE_GAVE_UP: "每日结算连续失败：资金未结算，需人工处理",
  CARD_READER_FAULT: "读卡/支付服务故障：终端无法刷卡",
  APP_CRASH: "终端应用崩溃（已自动重启）",
};

// One mail per device + code per window, so a flapping fault doesn't flood.
const DEDUPE_MINUTES = 30;

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("Method Not Allowed", { status: 405 });

  const secret = Deno.env.get("ALERT_WEBHOOK_SECRET");
  if (!secret || req.headers.get("x-alert-secret") !== secret) {
    return new Response("forbidden", { status: 403 });
  }

  const payload = await req.json().catch(() => null);
  const alert = payload?.record;
  if (payload?.type !== "INSERT" || !alert) return new Response("ignored", { status: 200 });
  if (alert.severity !== "CRITICAL") return new Response("not critical", { status: 200 });

  const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

  const since = new Date(new Date(alert.created_at).getTime() - DEDUPE_MINUTES * 60_000).toISOString();
  const { data: recent } = await supabase
    .from("app_error_logs")
    .select("id")
    .eq("device_sn", alert.device_sn)
    .eq("error_code", alert.error_code)
    .eq("severity", "CRITICAL")
    .gte("created_at", since)
    .lt("id", alert.id)
    .limit(1);
  if (recent && recent.length > 0) {
    console.log(`[alert-notify] ${alert.device_sn} ${alert.error_code} already mailed within ${DEDUPE_MINUTES} min`);
    return new Response("deduplicated", { status: 200 });
  }

  const { data: device } = await supabase
    .from("devices")
    .select("sn, alias, org_id, loc_id, organizations(name), locations(name, address)")
    .eq("sn", alert.device_sn)
    .maybeSingle();

  const deviceName = device?.alias || alert.device_sn;
  const org = (device as any)?.organizations?.name ?? "";
  const loc = (device as any)?.locations;
  const when = new Date(alert.created_at).toLocaleString("zh-CN", { timeZone: "America/Toronto" });
  const what = DESCRIPTIONS[alert.error_code] ?? alert.error_code;

  const subject = `[GoldSky 告警] ${what} — ${deviceName}${org ? ` (${org})` : ""}`;
  const text = [
    `时间：${when}（多伦多时间）`,
    `设备：${deviceName}（SN ${alert.device_sn}）`,
    org ? `商户：${org}` : null,
    loc ? `门店：${loc.name ?? ""} ${loc.address ?? ""}`.trim() : null,
    `告警：${what}`,
    `代码：${alert.error_code}`,
    alert.stack_trace ? `详情：${String(alert.stack_trace).slice(0, 1000)}` : null,
    "",
    "请到 CMP（https://cmp.goldsky.ca）设备管理页处理，处理后标记为已处理。",
  ].filter((l) => l !== null).join("\n");

  const port = Number(Deno.env.get("SMTP_PORT") ?? "465");
  const transport = nodemailer.createTransport({
    host: Deno.env.get("SMTP_HOST"),
    port,
    secure: port === 465,
    auth: { user: Deno.env.get("SMTP_USER"), pass: Deno.env.get("SMTP_PASS") },
  });

  try {
    await transport.sendMail({
      from: Deno.env.get("SMTP_FROM") ?? Deno.env.get("SMTP_USER"),
      to: (Deno.env.get("ALERT_EMAIL_TO") ?? "service@goldsky.ca").split(",").map((s) => s.trim()),
      subject,
      text,
    });
  } catch (e) {
    console.error(`[alert-notify] send failed for alert ${alert.id}: ${e}`);
    return new Response("send failed", { status: 500 });
  }
  console.log(`[alert-notify] mailed alert ${alert.id} (${alert.error_code}, ${alert.device_sn})`);
  return new Response("sent", { status: 200 });
});
