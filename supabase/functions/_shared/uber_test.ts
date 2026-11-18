// deno test --allow-env --allow-net supabase/functions/_shared/uber_test.ts
// Uber's order (v1 and v2 shapes from developer.uber.com) -> ours; the webhook signature.

import { cents, signatureOk, toOurOrder } from "./uber.ts";

function eq(a: unknown, b: unknown, what: string) {
  const x = JSON.stringify(a), y = JSON.stringify(b);
  if (x !== y) throw new Error(`${what}: got ${x}, want ${y}`);
}

Deno.test("money: v2 amount_e5, v1 formatted / minor units, bare number", () => {
  eq(cents({ amount_e5: 1299000 }), 1299, "e5");
  eq(cents({ amount: 1299, currency_code: "CAD", formatted_amount: "$12.99" }), 1299, "v1");
  eq(cents({ amount: 1299 }), 1299, "minor");
  eq(cents({ amount: 12.99 }), 1299, "major float");
  eq(cents({ gross: { amount_e5: 500000 } }), 500, "gross");
  eq(cents(null), null, "none");
});

Deno.test("v1 order", () => {
  const o = toOurOrder({
    id: "f9a2f3c4-0000-4000-8000-000000000001", display_id: "8C2A1", current_state: "CREATED", type: "DELIVERY_BY_UBER",
    placed_at: "2026-11-17T23:40:00Z",
    eater: { first_name: "Jane", last_name: "Doe", phone: "+1 555-555-5555", phone_code: "123 45 678" },
    cart: {
      special_instructions: "No cutlery",
      items: [
        { title: "Kung Pao Chicken", quantity: 2, special_instructions: "extra spicy",
          price: { unit_price: { amount: 1450, formatted_amount: "$14.50" }, total_price: { amount: 2900, formatted_amount: "$29.00" } },
          selected_modifier_groups: [{ title: "Rice", selected_items: [{ title: "Fried rice", quantity: 1 }] }] },
        { title: "Spring Roll", quantity: 1, price: { total_price: { amount: 400, formatted_amount: "$4.00" } } },
      ],
    },
    payment: { charges: { sub_total: { amount: 3300, formatted_amount: "$33.00" }, tax: { amount: 429, formatted_amount: "$4.29" } } },
  })!;
  eq(o.display_id, "8C2A1", "code");
  eq(o.type, "DELIVERY_BY_UBER", "type");
  eq(o.customer_name, "Jane D.", "name");
  eq(o.customer_phone, "+1 555-555-5555 / 123 45 678", "phone");
  eq(o.note, "No cutlery", "note");
  eq(o.items.map((i) => [i.name, i.quantity, i.unit_price_cents, i.options, i.note]),
    [["Kung Pao Chicken", 2, 1450, ["Fried rice"], "extra spicy"], ["Spring Roll", 1, 400, [], null]], "items");
  eq([o.subtotal_cents, o.tax_cents], [3300, 429], "money");
});

Deno.test("v2 order (wrapped, carts, amount_e5, nested options)", () => {
  const o = toOurOrder({
    order: {
      id: "f9a2f3c4-0000-4000-8000-000000000002", display_id: "B7", fulfillment_type: "PICK_UP", created_time: "2026-11-17T23:41:00Z",
      customers: [{ name: { display_name: "Sam K." }, contact: { phone: { number: "+15555550000", pin_code: "987" } } }],
      carts: [{ items: [{
        title: "Combo", quantity: { amount: 1 }, customer_request: { special_instructions: "no onion" },
        price: { unit: { gross: { amount_e5: 1000000 } } },
        selected_modifier_groups: [{ title: "Drink", selected_items: [{ title: "Coke", quantity: { amount: 2 },
          selected_modifier_groups: [{ title: "Ice", selected_items: [{ title: "No ice" }] }] }] }],
      }] }],
      payment: { payment_detail: { item_charges: { subtotal_including_promos: { gross: { amount_e5: 1000000 } }, total_tax: { gross: { amount_e5: 130000 } } } } },
    },
  })!;
  eq([o.id.endsWith("02"), o.type, o.customer_name, o.customer_phone], [true, "PICK_UP", "Sam K.", "+15555550000 / 987"], "who");
  eq(o.items[0].options, ["2 × Coke", "No ice"], "options");
  eq(o.items[0].note, "no onion", "note");
  eq(o.items[0].unit_price_cents, 1000, "unit price");
  eq([o.subtotal_cents, o.tax_cents], [1000, 130], "money");
});

Deno.test("not an order", () => {
  eq(toOurOrder({ error: "x" }), null, "no id");
  eq(toOurOrder(null), null, "null");
});

Deno.test("signature", async () => {
  Deno.env.set("UBER_CLIENT_SECRET", "test-secret");
  Deno.env.delete("UBER_WEBHOOK_SECRET");
  const body = '{"event_id":"e1","event_type":"orders.notification"}';
  const k = await crypto.subtle.importKey("raw", new TextEncoder().encode("test-secret"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const hex = Array.from(new Uint8Array(await crypto.subtle.sign("HMAC", k, new TextEncoder().encode(body))), (b) => b.toString(16).padStart(2, "0")).join("");
  eq(await signatureOk(body, hex), true, "right");
  eq(await signatureOk(body, hex.toUpperCase()), true, "upper case accepted");
  eq(await signatureOk(body + " ", hex), false, "body changed");
  eq(await signatureOk(body, null), false, "missing");
  eq(await signatureOk(body, "00"), false, "short");
});
