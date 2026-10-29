import { assert, assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { DmnParams, isEmail, refundChecksumInput, sha256Hex, timeStamp, toAmount, toCents, verifyDmn } from "./nuvei-checkout.ts";

Deno.test("DMN checksum: Nuvei's own worked example", async () => {
  // docs.nuvei.com Payment Page quick start: Secret123 + 115 + USD + 2007-11-13.13:22:34 + 3453459 + APPROVED + YourProduct
  const p = new DmnParams(new URLSearchParams({
    totalAmount: "115", currency: "USD", responseTimeStamp: "2007-11-13.13:22:34",
    PPP_TransactionID: "3453459", Status: "APPROVED", productId: "YourProduct",
    advanceResponseChecksum: "4cf3573877655c9b84ae8040beb586d9bc69d09d33ee4f4b451027579aa3f586",
  }));
  assert(await verifyDmn(p, "Secret123"));
  assert(!(await verifyDmn(p, "Secret124")));
});

Deno.test("DMN: names in any case, a changed amount fails", async () => {
  const sum = await sha256Hex("k" + "12.34" + "CAD" + "t" + "9" + "APPROVED" + "");
  const ok = new DmnParams(new URLSearchParams({
    TOTALAMOUNT: "12.34", Currency: "CAD", responseTimeStamp: "t", ppp_TransactionID: "9", status: "APPROVED",
    AdvanceResponseChecksum: sum.toUpperCase(),
  }));
  assert(await verifyDmn(ok, "k"));
  const changed = new DmnParams(new URLSearchParams({
    totalAmount: "99.99", currency: "CAD", responseTimeStamp: "t", PPP_TransactionID: "9", Status: "APPROVED", advanceResponseChecksum: sum,
  }));
  assert(!(await verifyDmn(changed, "k")));
  assert(!(await verifyDmn(new DmnParams(new URLSearchParams({ Status: "APPROVED" })), "k")));
});

Deno.test("amounts: cents <-> Nuvei decimals", () => {
  assertEquals(toAmount(1234), "12.34");
  assertEquals(toAmount(5), "0.05");
  assertEquals(toCents("12.34"), 1234);
  assertEquals(toCents("0.29"), 29);
  assertEquals(toCents(""), null);
  assertEquals(toCents("abc"), null);
});

Deno.test("timeStamp: YYYYMMDDHHmmss", () => {
  assertEquals(timeStamp(new Date("2026-10-28T09:05:07Z")), "20261028090507");
});

Deno.test("email: what Nuvei gets", () => {
  assert(isEmail("a@b.ca"));
  assert(isEmail("jane.smith+dinner@example.co.uk"));
  assert(!isEmail("jane@example"));
  assert(!isEmail("jane smith@example.com"));
  assert(!isEmail(""));
  assert(!isEmail("x".repeat(95) + "@a.com"));
});

Deno.test("void / refund checksum: Nuvei's field order, optional fields left out", () => {
  const account = { id: "a", merchantId: "M", merchantSiteId: "S", secret: "K", env: "int" as const };
  // merchantId, merchantSiteId, clientRequestId, clientUniqueId, amount, currency, relatedTransactionId, (authCode, comment, urlDetails), timeStamp, secret
  assertEquals(refundChecksumInput(account, "R1", "U1", "26.94", "CAD", "811", "20261028210000"), "MSR1U126.94CAD81120261028210000K");
});
