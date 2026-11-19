import { ASSISTANT_NAME, PROTOCOL_VERSION, SILENCE_TOKEN, buildGeminiRequest, finalizeReply } from "./zuleide.js";
import { geminiToClientStream, sseEvent } from "./stream.js";

// Gemini 3.5 Flash-Lite: multimodal (texto, imagem e áudio) e com raciocínio mínimo por padrão,
// ou seja, a menor latência da linha. Dá para trocar sem mexer no código: variável GEMINI_MODEL.
const DEFAULT_MODEL = "gemini-3.8-flash";
const API_ROOT = "https://generativelanguage.googleapis.com/v1beta/models/";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type"
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8" }
  });
}

function sseResponse(stream) {
  return new Response(stream, {
    status: 200,
    headers: {
      ...corsHeaders,
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-store"
    }
  });
}

function silenceStream() {
  return new ReadableStream({
    start(controller) {
      controller.enqueue(sseEvent({ t: SILENCE_TOKEN }));
      controller.enqueue(sseEvent({ done: true }));
      controller.close();
    }
  });
}

async function providerFailure(upstream) {
  let providerMessage = "";
  try {
    const data = await upstream.json();
    providerMessage = data?.error?.message || "";
    console.error("Gemini error", upstream.status, JSON.stringify(data).slice(0, 1000));
  } catch {
    console.error("Gemini error", upstream.status);
  }
  const detail = "Falha no provedor Gemini (HTTP " + upstream.status + ")" +
    (providerMessage ? ": " + String(providerMessage).slice(0, 300) : "");
  return json({ detail }, upstream.status === 429 ? 429 : 502);
}

/** Conversa da Zuleide: recebe áudio/texto + tela e responde em streaming (SSE). */
async function handleTurn(request, env) {
  let input;
  try {
    input = await request.json();
  } catch {
    return json({ detail: "JSON inválido" }, 400);
  }
  const built = buildGeminiRequest(input);
  if (built.error) return json({ detail: built.error.detail }, built.error.status);

  // Comentário espontâneo sem imagem não tem o que comentar: nem gasta cota do Gemini.
  if (built.kind === "proactive" && !(typeof input.image_base64 === "string" && input.image_base64)) {
    return sseResponse(silenceStream());
  }

  const model = env.GEMINI_MODEL || DEFAULT_MODEL;
  let upstream;
  try {
    upstream = await fetch(API_ROOT + model + ":streamGenerateContent?alt=sse", {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
      body: JSON.stringify(built.body)
    });
  } catch (error) {
    console.error("AI request failed", error);
    return json({ detail: "Falha de conexão com o serviço de IA" }, 502);
  }
  if (!upstream.ok) return providerFailure(upstream);
  if (!upstream.body) return json({ detail: "A IA retornou uma resposta vazia" }, 502);
  return sseResponse(geminiToClientStream(upstream.body));
}

/** Rota antiga (app 0.9.x): texto -> JSON {reply}. Mantida para não quebrar quem ainda não atualizou o app. */
async function handleLegacyAnalyze(request, env) {
  let input;
  try {
    input = await request.json();
  } catch {
    return json({ detail: "JSON inválido" }, 400);
  }
  const built = buildGeminiRequest({ ...input, kind: "user", audio_base64: "" }, { legacy: true });
  if (built.error) return json({ detail: built.error.detail }, built.error.status);

  const model = env.GEMINI_MODEL || DEFAULT_MODEL;
  try {
    const upstream = await fetch(API_ROOT + model + ":generateContent", {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
      body: JSON.stringify(built.body)
    });
    if (!upstream.ok) return providerFailure(upstream);
    const data = await upstream.json();
    const reply = finalizeReply(
      (data.candidates?.[0]?.content?.parts || []).map(part => part.text || "").join("\n")
    );
    if (!reply) return json({ detail: "A IA retornou uma resposta vazia" }, 502);
    return json({ reply });
  } catch (error) {
    console.error("AI request failed", error);
    return json({ detail: "Falha de conexão com o serviço de IA" }, 502);
  }
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders });
    const { pathname } = new URL(request.url);

    // O app confere esta rota ao iniciar: se ela não existir, o Worker publicado é antigo.
    if (request.method === "GET" && pathname === "/api/zuleide/version") {
      return json({
        version: PROTOCOL_VERSION,
        assistant: ASSISTANT_NAME,
        model: env.GEMINI_MODEL || DEFAULT_MODEL,
        features: ["audio", "stream", "proactive"],
        configured: Boolean(env.GEMINI_API_KEY)
      });
    }

    const isTurn = request.method === "POST" && pathname === "/api/zuleide/turn";
    const isLegacy = request.method === "POST" && pathname === "/api/game/analyze";
    if (!isTurn && !isLegacy) return json({ detail: "Rota não encontrada" }, 404);
    if (!env.GEMINI_API_KEY) return json({ detail: "Chave Gemini não configurada no servidor" }, 503);
    return isTurn ? handleTurn(request, env) : handleLegacyAnalyze(request, env);
  }
};
