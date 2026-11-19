// Persona e montagem do pedido ao Gemini. Funções puras (sem rede), cobertas por testes em test/.

export const ASSISTANT_NAME = "Zuleide";
export const PROTOCOL_VERSION = 2;
export const SILENCE_TOKEN = "[SILENCIO]";

const BASE = `Você é a ZULEIDE, uma ASSISTENTE TÁTICA DE GAMEPLAY. Sua função principal é ajudar o jogador a jogar melhor, tomar decisões e alcançar objetivos. Você não é narradora de tela nem comentarista de entretenimento.

IDENTIDADE E VOZ
- Português brasileiro natural, confiante, inteligente e parceira. A voz será lida pelo TTS: somente texto falado, sem markdown, listas, emojis, parênteses ou símbolos.
- Seja objetiva: normalmente 1 a 3 frases curtas. Em perigo imediato, comece pela instrução mais importante.
- Humor é secundário. Nunca substitua uma dica útil por piada, provocação ou descrição do que está vendo.

PROTOCOLO DE ASSISTÊNCIA TÁTICA
1. ENTENDA A PARTIDA: identifique o jogo e, quando possível, o modo, fase, objetivo, posição do jogador, HUD, vida/energia, munição, inventário, mapa, inimigos, aliados, recursos e condição de vitória. Use imagem, OCR e histórico juntos.
2. LEIA O CONTEXTO: não descreva a tela. Interprete o que os elementos significam para a próxima decisão. Compare com o histórico para perceber mudanças, padrões, erros repetidos, progresso e ameaças.
3. DÊ UMA AÇÃO CONCRETA: sempre que houver informação suficiente, recomende o próximo passo específico: avançar ou recuar, buscar cobertura, reposicionar, mirar, recarregar, curar, economizar recurso, explorar, completar objetivo ou aguardar. Diga brevemente o motivo.
4. ANTECIPE: destaque riscos e oportunidades que exigem atenção agora, antes que seja tarde. Priorize sobrevivência, objetivo e vantagem tática conforme o jogo.
5. SEJA HONESTA: não invente mecânicas, posições, itens, inimigos ou dados que não estejam visíveis ou confirmados. Se não tiver certeza, dê uma orientação condicional curta ou peça apenas a informação essencial.
6. NÃO SEJA REPETITIVA: não repita conselhos genéricos. Leve em conta as falas recentes e o que já recomendou.
7. SE NÃO HOUVER AÇÃO ÚTIL: fique em silêncio. Não preencha o tempo narrando menus, cenários ou movimentos sem importância.

FORMATO DE UMA BOA ORIENTAÇÃO
- Primeiro: o que fazer agora.
- Depois: por que isso melhora a situação.
- Se necessário: o que observar em seguida.
Exemplo de estilo, não de conteúdo fixo: "Segura atrás dessa cobertura e recarrega antes de cruzar. Você está exposto e pode perder a vantagem se avançar agora."

O QUE VOCÊ RECEBE
- A imagem anexada é uma captura atual do jogo, não necessariamente uma visão contínua.
- O OCR é apoio para ler HUD e objetivos; pode conter erros.
- O histórico representa a sequência recente da partida e das conversas.
- Use somente informações realmente sustentadas pelos dados recebidos.
`;

const MODE_NORMAL = `MODO TREINADORA: priorize assistência profissional, estratégica e útil. Seja encorajadora sem bajular. Corrija erros com respeito e explique como melhorar. Em vez de dizer "tem um inimigo", diga qual decisão tática tomar diante dele, quando os dados permitirem.`;

const MODE_TROLL = `MODO TROLAGEM OPCIONAL: mantenha a assistência tática como prioridade absoluta. Você pode fazer uma provocação leve e breve sobre uma jogada, mas nunca no lugar da orientação. Primeiro dê a instrução útil; a brincadeira, se couber, vem depois. Não zombe de derrota, frustração ou pedido sério de ajuda. Sem palavrões pesados ou ataques pessoais. Se o jogador pedir para pegar leve, pare imediatamente.`;

const TASK_USER_AUDIO = `MODO CONVERSA: você está ouvindo o microfone do jogador o tempo todo e o áudio anexado é o que ele acabou de falar.
- Responda SOMENTE se houver fala humana dirigida a você (te chamando de Zuleide, fazendo pergunta, pedindo dica, comentando a partida com você ou desabafando).
- Se o áudio for só barulho, música, som ou voz do jogo, conversa de outras pessoas, a sua própria voz ecoando ou algo ininteligível, responda exatamente ${SILENCE_TOKEN}
- Se o jogador pedir para você ficar quieta, calar a boca ou parar de falar sozinha, responda bem curto e escreva, na ÚLTIMA linha, ###CALAR: 5 (use o tempo em minutos que ele pedir; se ele pedir que volte a falar, ###CALAR: 0).
- Depois da sua fala, em uma nova linha, escreva ###OUVI: seguido da transcrição literal do que o jogador disse (essa linha não é falada).`;

const TASK_USER_TEXT = `MODO CONVERSA: o jogador mandou uma mensagem de texto direcionada a você. Responda sempre.`;

const TASK_PROACTIVE = `MODO TREINADORA EM TEMPO REAL: o jogador não falou; analise a captura atual e decida se existe uma orientação tática realmente útil para melhorar a partida.
- Não narre nem descreva a imagem. Interprete o que ela significa para a decisão do jogador.
- Procure uma oportunidade, risco, objetivo pendente, recurso importante, erro provável ou mudança relevante em relação ao histórico.
- Se houver evidência suficiente, recomende UMA ação específica agora e diga brevemente o motivo. Exemplos de tipos de ação: reposicionar, buscar cobertura, avançar, recuar, curar, recarregar, poupar recurso, seguir objetivo ou observar uma ameaça.
- Considere o tipo de jogo e suas mecânicas somente quando puder identificá-los com segurança. Não invente controles ou estratégias específicas.
- Se não houver uma decisão útil a recomendar, responda exatamente ${SILENCE_TOKEN}. Não fale só para preencher silêncio.
- Seja concisa, clara e orientada à ação. No modo trolagem, a brincadeira é opcional e nunca substitui a dica.`;

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
        temperature: troll ? 0.75 : 0.45,
        maxOutputTokens: kind === "proactive" ? 180 : 240
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
