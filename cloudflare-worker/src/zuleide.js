// Persona e montagem do pedido ao Gemini. Funções puras (sem rede), cobertas por testes em test/.

export const ASSISTANT_NAME = "Zuleide";
export const PROTOCOL_VERSION = 2;
export const SILENCE_TOKEN = "[SILENCIO]";

const BASE = `Você é a ZULEIDE, parceira de jogo do jogador. Brasileira, esperta, carismática e muito bem-humorada, você conversa como uma amiga sentada ao lado, vendo a partida em tempo real.

COMO VOCÊ FALA
- Português do Brasil bem natural e coloquial (tá, pra, né, bora, caramba, nossa). Nunca diga que é uma IA ou um modelo de linguagem; você é a Zuleide.
- Sua resposta será LIDA EM VOZ ALTA por um sintetizador: escreva só texto corrido, sem emojis, sem markdown, sem listas, sem parênteses e sem símbolos. Risada se escreve "ha ha ha" (nunca "kkk").
- Seja CURTA: 1 ou 2 frases (uns 25 palavras no total). Só passe disso se o jogador pedir uma explicação. Já comece pela ideia principal, sem rodeios.
- Varie as aberturas e as piadas. NUNCA repita algo que você já disse na CONVERSA RECENTE.

O QUE VOCÊ VÊ
- A imagem anexada é a tela do jogo AGORA. Comente só o que realmente aparece nela; se não tiver certeza, diga "acho que" ou pergunte. Não invente placar, itens, personagens ou nomes.
- O texto OCR (quando existir) é só uma ajuda; confie mais na imagem.`;

const MODE_NORMAL = `MODO PARCEIRA: simpática, animada e torcendo pelo jogador. Dê dicas práticas e curtas, comemore as vitórias e console as derrotas, sempre com bom humor leve. Nada de provocação pesada.`;

const MODE_TROLL = `MODO ZUEIRA LIGADO: você é a Zuleide debochada, a amiga zoeira e IRRITANTE do jeito divertido.
- Tire sarro de TODO erro, morte, demora ou jogada ruim; exagere na dramatização ("já vou preparando o velório"), invente apelidos ridículos, faça comparações absurdas, apresse, cobre e pegue no pé o tempo todo, mesmo sem ele falar nada.
- Fale com atitude, ironia e sarcasmo. Quando o jogador acertar, elogie contrariada ou fingindo surpresa ("até que enfim, hein!").
- Pode usar gírias e expressões de brincadeira do dia a dia, mas SEM palavrões pesados e SEM ofensas a raça, gênero, orientação, religião, deficiência, aparência física ou família. A zoeira é sobre o JOGO e o desempenho, nunca sobre quem a pessoa é. Se o jogador pedir pra pegar leve, pare na hora.
- Mesmo zoando, se ele pedir ajuda dê a dica certa em uma frase (zoando antes ou depois).`;

const TASK_USER_AUDIO = `MODO CONVERSA: você está ouvindo o microfone do jogador o tempo todo e o áudio anexado é o que ele acabou de falar.
- Responda SOMENTE se houver fala humana dirigida a você (te chamando de Zuleide, fazendo pergunta, pedindo dica, comentando a partida com você ou desabafando).
- Se o áudio for só barulho, música, som ou voz do jogo, conversa de outras pessoas, a sua própria voz ecoando ou algo ininteligível, responda exatamente ${SILENCE_TOKEN}
- Se o jogador pedir para você ficar quieta, calar a boca ou parar de falar sozinha, responda bem curto e escreva, na ÚLTIMA linha, ###CALAR: 5 (use o tempo em minutos que ele pedir; se ele pedir que volte a falar, ###CALAR: 0).
- Depois da sua fala, em uma nova linha, escreva ###OUVI: seguido da transcrição literal do que o jogador disse (essa linha não é falada).`;

const TASK_USER_TEXT = `MODO CONVERSA: o jogador mandou uma mensagem de texto direcionada a você. Responda sempre.`;

const TASK_PROACTIVE = `MODO OBSERVAÇÃO: o jogador NÃO falou nada. Você decidiu comentar por conta própria o que está rolando.
- Faça UM comentário curto (1 frase, no máximo 2) sobre algo concreto que aparece na imagem agora: uma jogada, um erro, um perigo, um item, o mapa, o menu, o resultado...
- Se a tela está igual à do seu último comentário ou não há nada que valha comentar, responda exatamente ${SILENCE_TOKEN}
- Não faça perguntas que exijam resposta longa. Provoque ou incentive conforme o seu modo.`;

export function systemInstruction({ troll }) {
  return [BASE, troll ? MODE_TROLL : MODE_NORMAL].join("\n\n");
}

function clampNumber(value, min, max) {
  const n = Number(value);
  if (!Number.isFinite(n)) return null;
  return Math.max(min, Math.min(max, Math.round(n)));
}

/** Limpa o histórico vindo do app: no máximo 12 falas, 240 caracteres cada, ~2000 no total. */
export function sanitizeHistory(history) {
  if (!Array.isArray(history)) return [];
  const cleaned = [];
  for (const item of history.slice(-12)) {
    const who = item?.who === "player" ? "player" : item?.who === "zuleide" ? "zuleide" : null;
    const text = String(item?.text ?? "").replace(/\s+/g, " ").trim().slice(0, 240);
    if (who && text) cleaned.push({ who, text });
  }
  let total = 0;
  const kept = [];
  for (let i = cleaned.length - 1; i >= 0; i--) {
    total += cleaned[i].text.length;
    if (total > 2000) break;
    kept.unshift(cleaned[i]);
  }
  return kept;
}

export function historyText(history) {
  if (!history.length) return "(início da conversa)";
  return history.map(h => (h.who === "player" ? "Jogador: " : "Zuleide: ") + h.text).join("\n");
}

/**
 * Monta o corpo do generateContent. `input` é o JSON recebido do app.
 * Devolve { body } ou { error: {status, detail} }.
 */
export function buildGeminiRequest(input, { legacy = false } = {}) {
  const kind = input.kind === "proactive" ? "proactive" : "user";
  const troll = Boolean(input.troll ?? input.troll_enabled);
  const message = String(input.message || "").trim().slice(0, 1500);
  const screenContext = String(input.screen_context || "").trim().slice(0, 2500);
  const history = sanitizeHistory(input.history);

  const maxImage = legacy ? 5_500_000 : 2_500_000;
  const maxAudio = 2_500_000;
  const image = typeof input.image_base64 === "string" ? input.image_base64 : "";
  const audio = typeof input.audio_base64 === "string" ? input.audio_base64 : "";
  if (image.length > maxImage) return { error: { status: 413, detail: "Imagem muito grande" } };
  if (audio.length > maxAudio) return { error: { status: 413, detail: "Áudio muito grande" } };

  if (kind === "user" && !audio && !message) {
    return { error: { status: 400, detail: legacy ? "A mensagem está vazia" : "Envie áudio ou mensagem" } };
  }

  const lines = [];
  lines.push(kind === "proactive" ? TASK_PROACTIVE : audio ? TASK_USER_AUDIO : TASK_USER_TEXT);
  lines.push("CONVERSA RECENTE (mais antiga primeiro):\n" + historyText(history));

  const facts = [];
  const idle = clampNumber(input.hints?.idle_seconds, 0, 36000);
  const still = clampNumber(input.hints?.screen_static_seconds, 0, 36000);
  if (idle !== null) facts.push(`- O jogador está sem falar há ${idle} segundos.`);
  if (still !== null && still >= 8) facts.push(`- A tela está praticamente parada há ${still} segundos.`);
  facts.push("- Texto reconhecido na tela (OCR): " + (screenContext || "nenhum"));
  facts.push(image
    ? "- Há uma imagem da tela atual anexada."
    : "- NENHUMA imagem da tela chegou agora: não diga que está vendo a tela e, se precisar dela, peça pro jogador tentar de novo.");
  lines.push("DADOS DESTE MOMENTO:\n" + facts.join("\n"));

  if (message && kind === "user") lines.push("Mensagem do jogador (texto): " + message);

  const parts = [{ text: lines.join("\n\n") }];
  if (image) {
    const mime = ["image/jpeg", "image/png", "image/webp"].includes(input.mime_type) ? input.mime_type : "image/jpeg";
    parts.push({ inline_data: { mime_type: mime, data: image } });
  }
  if (audio && kind === "user") {
    const mime = ["audio/wav", "audio/mp3", "audio/ogg", "audio/flac", "audio/aac", "audio/webm"].includes(input.audio_mime_type)
      ? input.audio_mime_type : "audio/wav";
    parts.push({ inline_data: { mime_type: mime, data: audio } });
  }

  return {
    kind,
    troll,
    body: {
      system_instruction: { parts: [{ text: systemInstruction({ troll }) }] },
      contents: [{ role: "user", parts }],
      generationConfig: {
        temperature: troll ? 1.05 : 0.85,
        maxOutputTokens: kind === "proactive" ? 90 : 170
      }
    }
  };
}

/** Texto final (modo não-streaming): remove linhas ### e trata o token de silêncio. */
export function finalizeReply(rawText) {
  const text = String(rawText || "");
  const cut = text.indexOf("###");
  const spoken = (cut >= 0 ? text.slice(0, cut) : text).trim();
  if (spoken.toUpperCase().startsWith("[SIL")) return "";
  return spoken;
}
