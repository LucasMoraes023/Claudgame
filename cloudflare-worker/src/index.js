const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Content-Type": "application/json; charset=utf-8"
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: corsHeaders });
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { headers: corsHeaders });
    if (request.method !== "POST" || new URL(request.url).pathname !== "/api/game/analyze") {
      return json({ detail: "Rota não encontrada" }, 404);
    }
    if (!env.GEMINI_API_KEY) return json({ detail: "Chave Gemini não configurada no servidor" }, 503);

    let input;
    try {
      input = await request.json();
    } catch {
      return json({ detail: "JSON inválido" }, 400);
    }

    const message = String(input.message || "").trim().slice(0, 1500);
    const screenContext = String(input.screen_context || "").trim().slice(0, 2500);
    if (!message) return json({ detail: "A mensagem está vazia" }, 400);

    const prompt = [
      "Você é ClaudGame, um companheiro experiente de jogos, que conversa em português brasileiro.",
      "Responda de forma natural, direta e útil. Não invente elementos que não aparecem no contexto.",
      "Use o texto reconhecido na tela como evidência, mas deixe claro quando ele não for suficiente.",
      "Dê orientações práticas e curtas que possam ser ouvidas durante uma partida.",
      input.troll_enabled ? "O modo brincalhão está ativo: use humor leve, sem atrapalhar a orientação." : "Não use provocações.",
      "Mensagem do jogador: " + message,
      "Contexto OCR atual da tela: " + (screenContext || "Nenhum texto reconhecido na tela.")
    ].join("\n\n");

    const parts = [{ text: prompt }];
    if (typeof input.image_base64 === "string" && input.image_base64.length > 0) {
      if (input.image_base64.length > 5_500_000) return json({ detail: "Imagem muito grande" }, 413);
      const mime = ["image/jpeg", "image/png", "image/webp"].includes(input.mime_type)
        ? input.mime_type : "image/jpeg";
      parts.push({ inline_data: { mime_type: mime, data: input.image_base64 } });
    }

    try {
      const upstream = await fetch(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" +
          encodeURIComponent(env.GEMINI_API_KEY),
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            contents: [{ role: "user", parts }],
            generationConfig: { temperature: 0.65, maxOutputTokens: 220 }
          })
        }
      );
      const data = await upstream.json();
      if (!upstream.ok) {
        console.error("Gemini error", upstream.status, JSON.stringify(data).slice(0, 1000));
        return json({ detail: "A IA não conseguiu responder agora (HTTP " + upstream.status + ")" }, 502);
      }
      const reply = (data.candidates?.[0]?.content?.parts || [])
        .map(part => part.text || "")
        .join("\n")
        .trim();
      if (!reply) return json({ detail: "A IA retornou uma resposta vazia" }, 502);
      return json({ reply });
    } catch (error) {
      console.error("AI request failed", error);
      return json({ detail: "Falha de conexão com o serviço de IA" }, 502);
    }
  }
};
