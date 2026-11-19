import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { sanitizeHistory, finalizeReply, systemInstruction, buildGeminiRequest } from "../src/zuleide.js";

const ENV = { GEMINI_API_KEY: "test-key" };
const encoder = new TextEncoder();

function post(path, body, raw = false) {
  return new Request("https://worker.test" + path, { method: "POST", body: raw ? body : JSON.stringify(body) });
}

/** Resposta de streaming do Gemini, em pedaços de bytes arbitrários (podem cortar linhas e caracteres). */
function upstreamStream(pieces, status = 200) {
  const stream = new ReadableStream({
    start(controller) {
      for (const piece of pieces) controller.enqueue(typeof piece === "string" ? encoder.encode(piece) : piece);
      controller.close();
    }
  });
  return new Response(stream, { status, headers: { "content-type": "text/event-stream" } });
}

function geminiEvent(text, extra = {}) {
  return "data: " + JSON.stringify({ candidates: [{ content: { parts: [{ text }], role: "model" }, index: 0, ...extra }] }) + "\r\n\r\n";
}

async function withFetch(impl, fn) {
  const original = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init) => { calls.push({ url: String(url), init }); return impl(url, init, calls.length); };
  try { return await fn(calls); } finally { globalThis.fetch = original; }
}

async function readEvents(response) {
  const text = await response.text();
  return text.split("\n\n").filter(Boolean).map(block => JSON.parse(block.replace(/^data: /, "")));
}

test("rota de versão responde sem precisar da chave", async () => {
  const res = await worker.fetch(new Request("https://worker.test/api/zuleide/version"), {});
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.version, 2);
  assert.equal(body.assistant, "Zuleide");
  assert.equal(body.configured, false);
});

test("rotas desconhecidas e GET na rota de turno dão 404", async () => {
  assert.equal((await worker.fetch(new Request("https://worker.test/"), ENV)).status, 404);
  assert.equal((await worker.fetch(new Request("https://worker.test/api/zuleide/turn"), ENV)).status, 404);
  const body = await (await worker.fetch(new Request("https://worker.test/x"), ENV)).json();
  assert.equal(body.detail, "Rota não encontrada");
});

test("sem chave Gemini devolve 503 com a mensagem esperada pelo app", async () => {
  const res = await worker.fetch(post("/api/zuleide/turn", { message: "oi" }), {});
  assert.equal(res.status, 503);
  assert.equal((await res.json()).detail, "Chave Gemini não configurada no servidor");
});

test("validações: JSON inválido, turno vazio e anexos grandes demais", async () => {
  assert.equal((await worker.fetch(post("/api/zuleide/turn", "{nope", true), ENV)).status, 400);
  const empty = await worker.fetch(post("/api/zuleide/turn", { kind: "user" }), ENV);
  assert.equal(empty.status, 400);
  assert.equal((await empty.json()).detail, "Envie áudio ou mensagem");
  assert.equal((await worker.fetch(post("/api/zuleide/turn", { audio_base64: "A".repeat(2_500_001) }), ENV)).status, 413);
  assert.equal((await worker.fetch(post("/api/zuleide/turn", { message: "oi", image_base64: "A".repeat(2_500_001) }), ENV)).status, 413);
});

test("turno com áudio + tela: monta o pedido certo e repassa o streaming em eventos simples", async () => {
  await withFetch(() => upstreamStream([
    geminiEvent("Opa, "), geminiEvent("bora lá!\n###OUVI: oi zuleide")
  ]), async calls => {
    const res = await worker.fetch(post("/api/zuleide/turn", {
      kind: "user", troll: false, audio_base64: "QVVESU8=", audio_mime_type: "audio/wav",
      image_base64: "SU1BR0VN", mime_type: "image/jpeg", screen_context: "Texto visível na tela: Menu",
      history: [{ who: "player", text: "oi" }, { who: "zuleide", text: "e aí!" }]
    }), ENV);
    assert.equal(res.status, 200);
    assert.match(res.headers.get("content-type"), /text\/event-stream/);
    const events = await readEvents(res);
    assert.deepEqual(events.map(e => e.t || (e.done ? "DONE" : "?")), ["Opa, ", "bora lá!\n###OUVI: oi zuleide", "DONE"]);

    assert.equal(calls.length, 1);
    assert.match(calls[0].url, /gemini-3\.8-flash:streamGenerateContent\?alt=sse$/);
    assert.equal(calls[0].init.headers["x-goog-api-key"], "test-key");
    const sent = JSON.parse(calls[0].init.body);
    const parts = sent.contents[0].parts;
    assert.equal(sent.contents.length, 1);
    assert.equal(sent.contents[0].role, "user");
    assert.ok(parts[0].text.includes("MODO CONVERSA"));
    assert.ok(parts[0].text.includes("Jogador: oi") && parts[0].text.includes("Zuleide: e aí!"));
    assert.ok(parts[0].text.includes("Menu"));
    assert.deepEqual(parts.slice(1).map(p => p.inline_data.mime_type), ["image/jpeg", "audio/wav"]);
    assert.ok(sent.system_instruction.parts[0].text.includes("MODO PARCEIRA ANALISTA"));
    assert.ok(sent.system_instruction.parts[0].text.includes("ANÁLISE PROFUNDA DA PARTIDA"));
    assert.ok(sent.system_instruction.parts[0].text.includes("CONTE O QUE REALMENTE ESTÁ ACONTECENDO"));
    assert.equal(sent.generationConfig.maxOutputTokens, 420);
  });
});

test("modo zueira muda a persona e a temperatura", () => {
  const normal = buildGeminiRequest({ message: "oi", troll: false });
  const troll = buildGeminiRequest({ message: "oi", troll: true });
  assert.ok(normal.body.system_instruction.parts[0].text.includes("MODO PARCEIRA ANALISTA"));
  assert.ok(troll.body.system_instruction.parts[0].text.includes("MODO TROLAGEM OPCIONAL"));
  assert.ok(troll.body.generationConfig.temperature > normal.body.generationConfig.temperature);
  assert.ok(systemInstruction({ troll: true }).includes("assistência tática como prioridade absoluta"));
  assert.ok(systemInstruction({ troll: false }).includes("ZULEIDE"));
});

test("comentário espontâneo: prompt de observação, limite menor e dicas de tela parada", () => {
  const built = buildGeminiRequest({ kind: "proactive", image_base64: "SU1H", hints: { idle_seconds: 42, screen_static_seconds: 31 }, troll: true });
  const text = built.body.contents[0].parts[0].text;
  assert.ok(text.includes("MODO OBSERVADORA EM TEMPO REAL"));
  assert.ok(text.includes("há algo novo, importante"));
  assert.ok(text.includes("sem falar há 42 segundos"));
  assert.ok(text.includes("praticamente parada há 31 segundos"));
  assert.equal(built.body.generationConfig.maxOutputTokens, 320);
  assert.equal(built.body.contents[0].parts.length, 2); // texto + imagem (nunca áudio)
});

test("áudio é ignorado em comentário espontâneo e imagem ausente é avisada ao modelo", () => {
  const built = buildGeminiRequest({ kind: "proactive", audio_base64: "QQ==" });
  assert.equal(built.body.contents[0].parts.length, 1);
  assert.ok(built.body.contents[0].parts[0].text.includes("NENHUMA imagem da tela chegou"));
});

test("comentário espontâneo sem imagem não chama o Gemini", async () => {
  await withFetch(() => { throw new Error("não deveria chamar o Gemini"); }, async calls => {
    const res = await worker.fetch(post("/api/zuleide/turn", { kind: "proactive" }), ENV);
    assert.equal(res.status, 200);
    const events = await readEvents(res);
    assert.deepEqual(events, [{ t: "[SILENCIO]" }, { done: true }]);
    assert.equal(calls.length, 0);
  });
});

test("streaming tolera \\r\\n, linhas partidas no meio e caractere UTF-8 cortado entre pedaços", async () => {
  const full = encoder.encode(geminiEvent("Ação é isso, né? ") + geminiEvent("Vamos!"));
  const cut = full.findIndex((b, i) => b === 0xC3 && full[i + 1] !== undefined) + 1; // meio de "ç"/"ã"
  await withFetch(() => upstreamStream([full.slice(0, cut), full.slice(cut, cut + 7), full.slice(cut + 7)]), async () => {
    const res = await worker.fetch(post("/api/zuleide/turn", { message: "oi" }), ENV);
    const events = await readEvents(res);
    assert.equal(events.filter(e => e.t).map(e => e.t).join(""), "Ação é isso, né? Vamos!");
    assert.deepEqual(events.at(-1), { done: true });
  });
});

test("partes de raciocínio (thought) do modelo nunca chegam ao app", async () => {
  const event = "data: " + JSON.stringify({ candidates: [{ content: { parts: [{ text: "pensando alto...", thought: true }, { text: "Oi!" }] } }] }) + "\n\n";
  await withFetch(() => upstreamStream([event]), async () => {
    const res = await worker.fetch(post("/api/zuleide/turn", { message: "oi" }), ENV);
    const events = await readEvents(res);
    assert.deepEqual(events, [{ t: "Oi!" }, { done: true }]);
  });
});

test("resposta bloqueada por segurança, sem texto, sinaliza blocked", async () => {
  await withFetch(() => upstreamStream(["data: " + JSON.stringify({ candidates: [{ finishReason: "SAFETY" }] }) + "\n\n"]), async () => {
    const res = await worker.fetch(post("/api/zuleide/turn", { message: "oi" }), ENV);
    assert.deepEqual(await readEvents(res), [{ blocked: true }, { done: true }]);
  });
});

test("erros do Gemini: 429 vira 429 e outros viram 502, com a mensagem do provedor", async () => {
  const errorResponse = status => new Response(JSON.stringify({ error: { message: "cota estourada" } }), { status });
  await withFetch(() => errorResponse(429), async () => {
    const res = await worker.fetch(post("/api/zuleide/turn", { message: "oi" }), ENV);
    assert.equal(res.status, 429);
    assert.match((await res.json()).detail, /HTTP 429.*cota estourada/);
  });
  await withFetch(() => errorResponse(500), async () => {
    assert.equal((await worker.fetch(post("/api/zuleide/turn", { message: "oi" }), ENV)).status, 502);
  });
  await withFetch(() => { throw new Error("rede"); }, async () => {
    const res = await worker.fetch(post("/api/zuleide/turn", { message: "oi" }), ENV);
    assert.equal(res.status, 502);
    assert.equal((await res.json()).detail, "Falha de conexão com o serviço de IA");
  });
});

test("rota antiga /api/game/analyze continua devolvendo {reply} sem linhas ###", async () => {
  await withFetch(() => new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: "Fala, campeão!\n###OUVI: oi" }] } }] })), async calls => {
    const res = await worker.fetch(post("/api/game/analyze", { message: "oi", troll_enabled: true, screen_context: "" }), ENV);
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { reply: "Fala, campeão!" });
    assert.match(calls[0].url, /:generateContent$/);
    assert.ok(JSON.parse(calls[0].init.body).system_instruction.parts[0].text.includes("MODO TROLAGEM OPCIONAL"));
  });
  const empty = await worker.fetch(post("/api/game/analyze", { message: "   " }), ENV);
  assert.equal(empty.status, 400);
  assert.equal((await empty.json()).detail, "A mensagem está vazia");
});

test("GEMINI_MODEL permite trocar o modelo sem alterar o código", async () => {
  await withFetch(() => upstreamStream([geminiEvent("ok")]), async calls => {
    await (await worker.fetch(post("/api/zuleide/turn", { message: "oi" }), { ...ENV, GEMINI_MODEL: "gemini-3.5-flash" })).text();
    assert.match(calls[0].url, /models\/gemini-3\.5-flash:streamGenerateContent/);
  });
});

test("sanitizeHistory limita quantidade, tamanho e ignora lixo", () => {
  const long = Array.from({ length: 30 }, (_, i) => ({ who: i % 2 ? "zuleide" : "player", text: "x".repeat(300) }));
  const kept = sanitizeHistory([...long, { who: "outro", text: "ignorado" }, null, { who: "player", text: "   " }]);
  assert.ok(kept.length <= 12);
  assert.ok(kept.every(h => h.text.length <= 240));
  assert.ok(kept.reduce((n, h) => n + h.text.length, 0) <= 2000);
  assert.deepEqual(sanitizeHistory("lixo"), []);
});

test("finalizeReply remove metadados e trata silêncio", () => {
  assert.equal(finalizeReply("Fala aí!\n###OUVI: oi\n###CALAR: 5"), "Fala aí!");
  assert.equal(finalizeReply("[SILENCIO]"), "");
  assert.equal(finalizeReply("  [silencio]  "), "");
  assert.equal(finalizeReply("Oi"), "Oi");
});
