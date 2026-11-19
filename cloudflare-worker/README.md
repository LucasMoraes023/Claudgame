# ClaudGame AI Worker (Zuleide)

Proxy gratuito para a Gemini Developer API. A chave Gemini fica como secret no Cloudflare Worker e nunca é incluída no APK.

## Publicação (o jeito mais simples: GitHub Actions)

1. Crie uma chave em https://aistudio.google.com/app/apikey.
2. No GitHub, em Settings > Secrets and variables > Actions, crie `GEMINI_API_KEY`, `CLOUDFLARE_API_TOKEN` (modelo "Edit Cloudflare Workers") e `CLOUDFLARE_ACCOUNT_ID`.
3. Todo push em `cloudflare-worker/**` publica o Worker (workflow **Deploy ClaudGame AI Worker**); também dá para rodar manualmente em Actions > Run workflow. Os testes (`npm test`) rodam antes da publicação.
4. A URL é `https://wandering-haze-ce04.<sua-conta>.workers.dev` (o nome vem de `wrangler.toml`).

Pelo terminal: `npm install -g wrangler`, `wrangler login`, `wrangler secret put GEMINI_API_KEY` e `wrangler deploy` nesta pasta.

## Conferir se está no ar e atualizado

Abra no navegador `https://wandering-haze-ce04.<sua-conta>.workers.dev/api/zuleide/version`. Deve aparecer algo como `{"version":2,"assistant":"Zuleide",...,"configured":true}`:
- `Rota não encontrada` = o Worker publicado é antigo (publique de novo);
- `"configured":false` = falta o secret `GEMINI_API_KEY`.

## Rotas

`GET /api/zuleide/version`: versão do protocolo (o app confere ao iniciar).

`POST /api/zuleide/turn`: um turno da conversa, resposta em streaming (`text/event-stream`). JSON de entrada:
- `kind`: `user` (o jogador falou) ou `proactive` (ela decidiu comentar sozinha);
- `troll`: modo zueira;
- `audio_base64` + `audio_mime_type` (WAV): a fala do jogador (só em `user`);
- `message`: texto, como alternativa ao áudio;
- `image_base64` + `mime_type`: a tela atual (JPEG/PNG/WebP);
- `screen_context`: texto do OCR; `history`: últimas falas (`{who: "player"|"zuleide", text}`);
- `hints`: `idle_seconds` e `screen_static_seconds`.

Eventos de saída: `data: {"t":"texto"}` (zero ou mais), opcionalmente `{"blocked":true}`, e `{"done":true}` no fim. A resposta do modelo é só a fala da Zuleide; ela pode terminar com linhas `###OUVI: ...` (transcrição) e `###CALAR: N` (pedido para ficar quieta N minutos), que o app nunca fala. `[SILENCIO]` significa "nada a dizer".

`POST /api/game/analyze`: rota antiga (app 0.9.x), devolve `{"reply": "..."}`.

## Variáveis

- `GEMINI_API_KEY` (secret, obrigatória);
- `GEMINI_MODEL` (opcional): troca o modelo sem mexer no código (padrão `gemini-3.5-flash-lite`).

A chave do provedor é lida apenas de `env.GEMINI_API_KEY`. Nunca coloque essa chave no Android, no GitHub como arquivo ou no código-fonte. A rota é aberta: quem souber a URL consegue gastar a sua cota, então não a divulgue.

## Testes

`npm test` (Node 20+) roda `test/worker.test.mjs` com o Gemini simulado: streaming com quebras no meio de linhas e de caracteres UTF-8, erros 429/500, bloqueio de segurança, prompts do modo normal/zueira/observação e a rota antiga.

## Custos e privacidade

O Worker não cobra por chamada dentro dos limites gratuitos da Cloudflare, mas os limites podem mudar. A Gemini Developer API tem uma camada gratuita limitada e sujeita a cotas. No nível gratuito, os dados enviados (áudio e imagens da tela) podem ser usados pelo Google para melhorar os produtos; não envie dados pessoais ou confidenciais.
