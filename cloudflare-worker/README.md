# ClaudGame AI Worker

Proxy gratuito para a Gemini Developer API. A chave Gemini fica como secret no Cloudflare Worker e nunca é incluída no APK.

## Configuração

1. Crie uma chave em https://aistudio.google.com/app/apikey.
2. Instale Node.js e Wrangler: `npm install -g wrangler`.
3. Entre na sua conta Cloudflare: `wrangler login`.
4. Nesta pasta, cadastre a chave: `wrangler secret put GEMINI_API_KEY`.
5. Publique: `wrangler deploy`.
6. Copie a URL publicada, normalmente `https://claudgame-ai.<sua-conta>.workers.dev`.
7. No GitHub, abra Settings > Secrets and variables > Actions > New repository secret.
8. Crie o secret `CLAUDGAME_AI_BACKEND_URL` com a URL do Worker (sem barra no final).
9. Execute novamente o workflow Build ClaudGame APK.

## Endpoint

`POST /api/game/analyze`

JSON:
- `message`: pergunta do jogador.
- `screen_context`: texto identificado pelo OCR.
- `troll_enabled`: ativa humor leve.
- `image_base64` e `mime_type`: campos opcionais para uma captura JPEG/PNG/WebP.

A chave do provedor é lida apenas de `env.GEMINI_API_KEY`. Nunca coloque essa chave no Android, no GitHub como arquivo ou no código-fonte.

## Custos e privacidade

O Worker não cobra por chamada dentro dos limites gratuitos da Cloudflare, mas os limites podem mudar. A Gemini Developer API tem uma camada gratuita limitada e sujeita a cotas. Não há garantia de disponibilidade ilimitada. No nível gratuito, os dados enviados podem ser usados pelo Google para melhorar os produtos; não envie dados pessoais ou confidenciais.
