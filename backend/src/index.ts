/**
 * InvoiceExtract — secure failover reverse proxy (Phase 11.1).
 */

interface Env {
  APP_CLIENT_KEY: string;
  GEMINI_API_KEY: string;
  GITHUB_TOKEN?: string;
  DEEPSEEK_API_KEY?: string;
}

interface ExtractRequest {
  ocrText?: string;
}

interface InvoiceItem {
  name: string;
  quantity: number;
  unit_price: number;
  discount: number;
  tax: number;
  total_price: number;
  confidence: number;
}

interface Invoice {
  invoice_number: string | null;
  date: string | null;
  seller_name: string | null;
  seller_tax_id: string | null;
  buyer_name: string | null;
  buyer_tax_id: string | null;
  items: InvoiceItem[];
  subtotal: number;
  total_tax: number;
  total_discount: number;
  grand_total: number;
  currency: "TOMAN" | "RIAL";
}

type ProviderResult =
  | { ok: true; invoice: Invoice }
  | { ok: false; status: number; reason: string };

const ROUTE = "/api/v1/extract";
const CLIENT_KEY_HEADER = "X-App-Client-Key";

// Google Gemini Setup (gemini-1.5-flash is stable and globally available)
const GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent";

// DeepSeek Setup (deepseek-chat is DeepSeek-V3, served via the OpenAI-compatible API)
const DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";
const DEEPSEEK_MODEL = "deepseek-chat";

// GitHub Models Setup (DeepSeek-V3, OpenAI-compatible, free with a GITHUB_TOKEN)
const GITHUB_MODELS_URL = "https://models.github.ai/inference/chat/completions";
const GITHUB_MODEL = "deepseek-v3";

// Free-tier quota. 10 invoices per device per UTC day; a client that omits the
// X-Device-Id header is capped at 3 so stripping the header cannot reset the counter.
const DAILY_QUOTA_PER_DEVICE = 10;
const ANONYMOUS_QUOTA = 3;
const QUOTA_TTL_SECONDS = 86400;

const MESSAGE_QUOTA_DEVICE =
  "سهمیه رایگان امروز این دستگاه (۱۰ فاکتور) به پایان رسیده است. فردا ساعت ۰۰:۰۰ مجدداً شارژ می‌شود.";

const MESSAGE_QUOTA_ANONYMOUS =
  "سهمیه رایگان امروز این دستگاه (۳ اسکن) به پایان رسیده است. فردا ساعت ۰۰:۰۰ مجدداً شارژ می‌شود.";

const INVOICE_SCHEMA = {
  type: "OBJECT",
  properties: {
    invoice_number: { type: "STRING" },
    date: { type: "STRING" },
    seller_name: { type: "STRING" },
    seller_tax_id: { type: "STRING" },
    buyer_name: { type: "STRING" },
    buyer_tax_id: { type: "STRING" },
    items: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          name: { type: "STRING" },
          quantity: { type: "NUMBER" },
          unit_price: { type: "NUMBER" },
          discount: { type: "NUMBER" },
          tax: { type: "NUMBER" },
          total_price: { type: "NUMBER" },
          confidence: { type: "NUMBER" },
        },
        required: ["name", "quantity", "unit_price", "total_price"],
      },
    },
    subtotal: { type: "NUMBER" },
    total_tax: { type: "NUMBER" },
    total_discount: { type: "NUMBER" },
    grand_total: { type: "NUMBER" },
    currency: { type: "STRING" },
  },
  required: ["items", "grand_total"],
};

const SYSTEM_PROMPT = `You are a precise invoice data extraction engine for Iranian (Persian) invoices.
Read the raw OCR text and return ONLY a JSON object matching the invoice schema. No prose, no markdown, no commentary.

Rules:
1. DIGITS: Persian/Arabic digits (۰-۹) MUST be converted to Latin (0-9) inside JSON numbers.
2. NUMBERS: Output JSON numbers for quantity, unit_price, discount, tax, total_price, confidence, subtotal, total_tax, total_discount, grand_total.
3. DATE: Keep Jalali solar date verbatim as string (e.g. "1403/07/15").
4. CURRENCY: Must be "TOMAN" or "RIAL".
5. LINE ITEMS: Correlate item name, quantity, and unit_price semantically.
6. MISSING DATA: Output null for missing strings, 0 for missing numbers.
7. NAMES: Keep Persian text in Persian as printed.`;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (request.method !== "POST" || new URL(request.url).pathname !== ROUTE) {
      return json({ error: "Not found" }, 404, "Only POST /api/v1/extract is served.");
    }

    // Authenticate
    const clientKey = request.headers.get(CLIENT_KEY_HEADER);
    if (!clientKey || !env.APP_CLIENT_KEY || !timingSafeEqual(clientKey, env.APP_CLIENT_KEY)) {
      return json({ error: "Unauthorized" }, 401, "Invalid client key.");
    }

    // Body validation
    let body: ExtractRequest;
    try {
      body = (await request.json()) as ExtractRequest;
    } catch {
      return json({ error: "Bad request" }, 400, "Request body is not valid JSON.");
    }

    const ocrText = body?.ocrText?.trim();
    if (!ocrText) {
      return json({ error: "Bad request" }, 400, 'Field "ocrText" is required.');
    }

    // Per-device free-tier quota, enforced before any upstream call so a device that is
    // already over its daily allowance never spends a provider request. Identified
    // hardware gets 10 invoices/day; a missing X-Device-Id falls back to 3 scans so an
    // anonymous client cannot simply omit the header to reset its counter.
    const deviceId = request.headers.get("X-Device-Id") || "unknown";
    const quotaResponse = await enforceDailyQuota(deviceId);
    if (quotaResponse) {
      return quotaResponse;
    }

    // Tier 1 — Google Gemini 3.6 Flash (primary).
    const gemini = await callGemini(env, ocrText);
    if (gemini.ok) {
      return json(gemini.invoice, 200, "Gemini");
    }

    console.warn(`Gemini failed: ${gemini.reason}; falling back to GitHub Models DeepSeek-V3...`);

    // Tier 2 — GitHub Models DeepSeek-V3 (secondary fallback).
    const github = await callGitHubDeepSeek(env, ocrText);
    if (github.ok) {
      return json(github.invoice, 200, "GitHub-DeepSeek");
    }

    console.warn(`GitHub Models failed: ${github.reason}; falling back to official DeepSeek API...`);

    // Tier 3 — Official DeepSeek API (final guaranteed fallback).
    const deepseek = await callDeepSeek(env, ocrText);
    if (deepseek.ok) {
      return json(deepseek.invoice, 200, "DeepSeek-Official");
    }

    // Every tier failed: report what each upstream said.
    return json(
      {
        error: "Upstream unavailable",
        gemini_error: gemini.reason,
        github_error: github.reason,
        deepseek_error: deepseek.reason,
      },
      502,
      "All providers failed",
    );
  },
} satisfies ExportedHandler<Env>;

async function callGemini(env: Env, ocrText: string): Promise<ProviderResult> {
  if (!env.GEMINI_API_KEY || env.GEMINI_API_KEY === "none") {
    return { ok: false, status: 503, reason: "GEMINI_API_KEY is not configured" };
  }

  const payload = {
    contents: [
      {
        role: "user",
        parts: [{ text: `${SYSTEM_PROMPT}\n\nOCR TEXT:\n${ocrText}` }],
      },
    ],
    generationConfig: {
      temperature: 0.1,
      responseMimeType: "application/json",
      responseSchema: INVOICE_SCHEMA,
    },
  };

  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      const response = await fetch(`${GEMINI_URL}?key=${env.GEMINI_API_KEY}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      if (response.status === 503 && attempt === 1) {
        await new Promise((resolve) => setTimeout(resolve, 1500));
        continue;
      }

      if (!response.ok) {
        const errText = await response.text();
        return { ok: false, status: response.status, reason: `Gemini ${response.status}: ${errText.slice(0, 200)}` };
      }

      const resJson: any = await response.json();
      const raw = resJson?.candidates?.[0]?.content?.parts?.[0]?.text;
      if (!raw) {
        return { ok: false, status: 502, reason: "Gemini returned empty candidate text" };
      }

      return parseInvoice(raw, "Gemini");
    } catch (err) {
      if (attempt === 1) {
        await new Promise((resolve) => setTimeout(resolve, 1000));
        continue;
      }
      return { ok: false, status: 0, reason: `Gemini network error: ${String(err)}` };
    }
  }

  return { ok: false, status: 503, reason: "Gemini unavailable after retry" };
}

async function callGitHubDeepSeek(env: Env, ocrText: string): Promise<ProviderResult> {
  if (!env.GITHUB_TOKEN || env.GITHUB_TOKEN === "none") {
    return { ok: false, status: 503, reason: "GITHUB_TOKEN is not configured" };
  }

  const payload = {
    model: GITHUB_MODEL,
    temperature: 0.1,
    response_format: { type: "json_object" },
    messages: [
      { role: "system", content: SYSTEM_PROMPT },
      { role: "user", content: `OCR TEXT:\n${ocrText}` },
    ],
  };

  try {
    const response = await fetch(GITHUB_MODELS_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
      },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      const errText = await response.text();
      return { ok: false, status: response.status, reason: `GitHub ${response.status}: ${errText.slice(0, 200)}` };
    }

    const resJson: any = await response.json();
    const content = resJson?.choices?.[0]?.message?.content;
    if (!content) {
      return { ok: false, status: 502, reason: "GitHub returned empty content" };
    }

    return parseInvoice(content, "GitHub-DeepSeek");
  } catch (err) {
    return { ok: false, status: 0, reason: `GitHub network error: ${String(err)}` };
  }
}

async function callDeepSeek(env: Env, ocrText: string): Promise<ProviderResult> {  if (!env.DEEPSEEK_API_KEY || env.DEEPSEEK_API_KEY === "none") {
    return { ok: false, status: 503, reason: "DEEPSEEK_API_KEY is not configured" };
  }

  const payload = {
    model: DEEPSEEK_MODEL,
    temperature: 0.1,
    response_format: { type: "json_object" },
    messages: [
      { role: "system", content: SYSTEM_PROMPT },
      { role: "user", content: `OCR TEXT:\n${ocrText}` },
    ],
  };

  try {
    const response = await fetch(DEEPSEEK_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${env.DEEPSEEK_API_KEY}`,
      },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      const errText = await response.text();
      return { ok: false, status: response.status, reason: `DeepSeek ${response.status}: ${errText.slice(0, 200)}` };
    }

    const resJson: any = await response.json();
    const content = resJson?.choices?.[0]?.message?.content;
    if (!content) {
      return { ok: false, status: 502, reason: "DeepSeek returned empty content" };
    }

    return parseInvoice(content, "DeepSeek");
  } catch (err) {
    return { ok: false, status: 0, reason: `DeepSeek network error: ${String(err)}` };
  }
}

/**
 * Per-device daily quota gate backed by the Cloudflare Cache API.
 *
 * The counter is keyed by device id and the UTC calendar day, so it resets at 00:00 UTC
 * with no cron job or scheduled worker — the previous day's entry simply ages out of the
 * cache and a fresh key starts at zero.
 *
 * @returns A `429` [Response] when the device is already over its allowance, or `null`
 *   when the request may proceed (the counter has already been incremented).
 */
async function enforceDailyQuota(deviceId: string): Promise<Response | null> {
  const limit = deviceId === "unknown" ? ANONYMOUS_QUOTA : DAILY_QUOTA_PER_DEVICE;

  const today = new Date().toISOString().split("T")[0]; // "YYYY-MM-DD"
  const cacheKey = new Request(`https://quota.local/${deviceId}/${today}`);

  const cached = await caches.default.match(cacheKey);
  const count = cached ? parseInt(await cached.text(), 10) || 0 : 0;

  if (count >= limit) {
    return json(
      {
        error: "Daily quota exceeded",
        detail:
          limit === ANONYMOUS_QUOTA
            ? MESSAGE_QUOTA_ANONYMOUS
            : MESSAGE_QUOTA_DEVICE,
      },
      429,
      "Rate Limited",
    );
  }

  await caches.default.put(
    cacheKey,
    new Response(String(count + 1), {
      headers: { "Cache-Control": `max-age=${QUOTA_TTL_SECONDS}` },
    }),
  );

  return null;
}

function parseInvoice(raw: string, provider: string): ProviderResult {
  const cleaned = stripMarkdown(raw);
  try {
    const parsed = JSON.parse(cleaned);
    const validation = validateInvoice(parsed);
    if (!validation.ok) {
      return { ok: false, status: 502, reason: `${provider} invalid schema: ${validation.error}` };
    }
    return { ok: true, invoice: validation.invoice };
  } catch (err) {
    return { ok: false, status: 502, reason: `${provider} non-JSON output: ${String(err).slice(0, 150)}` };
  }
}

function stripMarkdown(raw: string): string {
  let text = raw.trim();
  const fenceStart = text.indexOf("```");
  if (fenceStart !== -1) {
    const lineEnd = text.indexOf("\n", fenceStart);
    const bodyStart = lineEnd === -1 ? fenceStart + 3 : lineEnd + 1;
    const fenceEnd = text.indexOf("```", bodyStart);
    text = fenceEnd === -1 ? text.slice(bodyStart) : text.slice(bodyStart, fenceEnd);
  }
  const first = text.indexOf("{");
  const last = text.lastIndexOf("}");
  if (first !== -1 && last !== -1 && last > first) {
    text = text.slice(first, last + 1);
  }
  return text.trim();
}

function validateInvoice(value: any): { ok: true; invoice: Invoice } | { ok: false; error: string } {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return { ok: false, error: "root is not an object" };
  }

  const currency = String(value.currency || "").trim().toUpperCase();
  const validCurrency = currency === "RIAL" ? "RIAL" : "TOMAN";

  const items: InvoiceItem[] = [];
  if (Array.isArray(value.items)) {
    for (const item of value.items) {
      if (item && typeof item === "object") {
        items.push({
          name: String(item.name || "کالا"),
          quantity: Number(item.quantity) || 1,
          unit_price: Number(item.unit_price) || 0,
          discount: Number(item.discount) || 0,
          tax: Number(item.tax) || 0,
          total_price: Number(item.total_price) || 0,
          confidence: Math.min(1, Math.max(0, Number(item.confidence) || 1)),
        });
      }
    }
  }

  const invoice: Invoice = {
    invoice_number: value.invoice_number ? String(value.invoice_number) : null,
    date: value.date ? String(value.date) : null,
    seller_name: value.seller_name ? String(value.seller_name) : null,
    seller_tax_id: value.seller_tax_id ? String(value.seller_tax_id) : null,
    buyer_name: value.buyer_name ? String(value.buyer_name) : null,
    buyer_tax_id: value.buyer_tax_id ? String(value.buyer_tax_id) : null,
    items,
    subtotal: Number(value.subtotal) || 0,
    total_tax: Number(value.total_tax) || 0,
    total_discount: Number(value.total_discount) || 0,
    grand_total: Number(value.grand_total) || 0,
    currency: validCurrency,
  };

  return { ok: true, invoice };
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let mismatch = 0;
  for (let i = 0; i < a.length; i++) {
    mismatch |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return mismatch === 0;
}

function json(body: unknown, status: number, via: string): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      ...corsHeaders(),
      "X-Served-By": via,
    },
  });
}

function corsHeaders(): Record<string, string> {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, X-App-Client-Key",
    "Access-Control-Max-Age": "86400",
  };
}