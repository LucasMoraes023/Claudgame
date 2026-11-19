// Converte o SSE do Gemini (streamGenerateContent?alt=sse) no SSE simples que o app lê:
//   data: {"t":"texto parcial"}      (zero ou mais)
//   data: {"done":true}              (sempre no fim; pode vir {"blocked":true} antes)

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function sseEvent(obj) {
  return encoder.encode("data: " + JSON.stringify(obj) + "\n\n");
}

/** Extrai o texto de um evento do Gemini; null se não houver. */
export function textFromGeminiEvent(event) {
  const parts = event?.candidates?.[0]?.content?.parts;
  if (!Array.isArray(parts)) return null;
  // Partes de raciocínio ("thought") nunca devem ser faladas, caso o modelo as devolva.
  const text = parts.map(p => (typeof p?.text === "string" && !p.thought ? p.text : "")).join("");
  return text.length ? text : null;
}

export function isBlockedEvent(event) {
  const reason = event?.candidates?.[0]?.finishReason;
  return Boolean(event?.promptFeedback?.blockReason) || reason === "SAFETY" || reason === "PROHIBITED_CONTENT" || reason === "BLOCKLIST";
}

export function geminiToClientStream(upstreamBody) {
  let buffer = "";
  let blocked = false;
  let sentText = false;

  const handleLine = (line, controller) => {
    const trimmed = line.trim();
    if (!trimmed.startsWith("data:")) return;
    const payload = trimmed.slice(5).trim();
    if (!payload || payload === "[DONE]") return;
    let event;
    try { event = JSON.parse(payload); } catch { return; }
    const text = textFromGeminiEvent(event);
    if (text) { sentText = true; controller.enqueue(sseEvent({ t: text })); }
    if (isBlockedEvent(event)) blocked = true;
  };

  const transform = new TransformStream({
    transform(chunk, controller) {
      buffer += decoder.decode(chunk, { stream: true });
      let newline;
      while ((newline = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, newline);
        buffer = buffer.slice(newline + 1);
        handleLine(line, controller);
      }
    },
    flush(controller) {
      buffer += decoder.decode();
      if (buffer) handleLine(buffer, controller);
      if (blocked && !sentText) controller.enqueue(sseEvent({ blocked: true }));
      controller.enqueue(sseEvent({ done: true }));
    }
  });
  return upstreamBody.pipeThrough(transform);
}
