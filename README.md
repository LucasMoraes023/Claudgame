# CLAUDGAME

**ClaudGame** é o projeto Android de **Game Hub + Game Booster + AI Game Companion**.

O projeto Python/web original foi preservado em `app/` e `frontend/`. O produto principal agora está sendo construído em Android nativo (Kotlin) dentro de `android/`.

## Objetivo

- 🎮 Biblioteca de jogos instalados e acesso rápido.
- ⚡ Modo de jogo usando somente recursos que o Android realmente permite controlar.
- 👁️ Captura de tela com **MediaProjection**, sempre mediante autorização do usuário.
- 🗣️ **ZAYRO**, a companheira de jogo: escuta o jogador sem apertar nada, responde por voz em português e comenta a partida por conta própria.
- 😈 **Modo Trolagem** (zueira): ela fica pegando no pé do jogador o tempo todo, mesmo sem ele falar.
- 🪟 Painel flutuante mínimo sobre o jogo, sem texto na tela.
- 🔐 Nenhuma chave de API é embutida no APK.

## Fundação já implementada

- Projeto Gradle/Kotlin nativo.
- Tela inicial ClaudGame.
- Detecção de aplicativos instalados que podem ser iniciados.
- Seleção e abertura de jogo.
- Fluxo de autorização de sobreposição.
- Fluxo de autorização MediaProjection.
- Serviço foreground para captura.
- Aquisição de frames com ImageReader.
- TTS nativo Android em português brasileiro (motor do Google quando instalado, voz de maior qualidade, troca de voz no Game Hub).
- Modo Trolagem como configuração local.
- Captura JPEG compactada (1024 px) do frame atual, enviada junto com a voz do jogador para análise multimodal.
- Cliente Android com resposta em streaming (a ZAYRO começa a falar na primeira frase) para o proxy seguro Gemini.
- Worker Cloudflare em `cloudflare-worker/` para manter a chave Gemini no servidor.
- GitHub Actions para testar e gerar o APK debug (com Gradle Wrapper).
- Painel flutuante compacto e arrastável (estado, mic, zueira, encerrar) e ações "Silenciar mic"/"Encerrar" na notificação.
- Falas locais da ZAYRO quando a IA online está indisponível (saudação, avisos e comentários).
- Testes unitários da lógica pura no Android (detector de fala, parser de streaming, planejador, voz, JSON) e do Worker (Node).

## Arquitetura planejada

**Android**
→ Game Hub  
→ Game Session  
→ Screen Capture  
→ Frame Selector  
→ OCR/Visão  
→ AI Engine  
→ Context Memory  
→ Voice/TTS  
→ Overlay

**Backend**
→ autenticação  
→ configuração do provedor  
→ chamadas de IA  
→ limites/segurança  
→ observabilidade

A chave de qualquer provedor de IA nunca deve ficar dentro do APK.

## Booster: o que será real

Não vamos prometer "aumentar FPS" por mágica. O Android não oferece uma API pública genérica para um aplicativo comum liberar RAM ou controlar o hardware de qualquer fabricante.

O ClaudGame poderá, conforme suporte do aparelho/versão do Android, organizar sessão de jogo, modo imersivo, notificações, monitoramento e integrações legítimas de Game Mode.

## Próximas implementações

1. Game Hub com cartões e perfis por jogo.
2. Overlay flutuante real com estado da IA.
3. Seleção inteligente de frames e redução de consumo.
4. OCR/visão e eventos de gameplay.
5. Publicar o proxy Gemini de `cloudflare-worker/` e configurar `CLAUDGAME_AI_BACKEND_URL` nos secrets do GitHub Actions.
6. Conectar e validar a análise multimodal Gemini com OCR + captura de tela.
7. Reconhecimento de fala + diálogo natural e Modo Trolagem 0–100 com cooldown e contexto.
8. Histórico de sessões e preferências.
9. Testes Android e tratamento completo de permissões.
10. Build release assinada e APK final.

## Build

Pré-requisitos: JDK 17 e acesso à internet (Gradle, Google Maven e Maven Central).

```bash
cd android
./gradlew testDebugUnitTest   # testes unitários
./gradlew assembleDebug       # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # APK: app/build/outputs/apk/release/app-release.apk (mesma assinatura do projeto)
```

Workflow: `.github/workflows/android-apk.yml` (roda os testes, gera o APK e o publica como artefato `ClaudGame-debug`).

Opções de build (`-P` na linha de comando):

| Opção | Efeito |
| --- | --- |
| `-PclaudgameAiBackendUrl=https://...` | URL do proxy de IA. Sem ela vale `CLAUDGAME_AI_BACKEND_URL` e, por fim, o Worker padrão do projeto. |
| `-PclaudgameOcr=none` | Compila **sem** o ML Kit (sem OCR local). A captura de tela e a IA multimodal continuam funcionando, pois o quadro vai direto à IA. Útil quando o Maven do Google não está acessível. |

**Assinatura:** `android/claudgame-debug.keystore` é versionada de propósito (chave de uso pessoal, senha no `app/build.gradle.kts`). Assim todo build tem a mesma assinatura e um APK novo instala por cima do anterior. Ela **não serve para publicar em loja**: para isso gere uma chave própria.

Tecnologias:
- Kotlin 2.0.21
- Android Gradle Plugin 8.7.3
- Gradle 8.9 (wrapper incluído)
- Java 17
- minSdk 26
- targetSdk 35
- Dependências: somente `com.google.mlkit:text-recognition` (OCR). O app usa apenas APIs da plataforma; AppCompat/Material foram removidos.

## IA online (Gemini) e a ZAYRO

O app fala com o Worker em `cloudflare-worker/`, que chama a Gemini Developer API (padrão `gemini-3.5-flash-lite`, que já usa raciocínio mínimo para responder rápido; dá para trocar pela variável `GEMINI_MODEL`). A chave `GEMINI_API_KEY` fica só no Cloudflare. Para ativar, siga `cloudflare-worker/README.md`. Sem o secret `CLAUDGAME_AI_BACKEND_URL`, o build usa o Worker padrão definido em `android/app/build.gradle.kts`.

**Protocolo v2** (`POST /api/zuleide/turn`, resposta em streaming SSE): o app manda a fala do jogador (WAV 16 kHz) e a tela (JPEG) numa única chamada; o Gemini ouve, vê e responde, sem um reconhecedor de voz separado. Isso corta uma etapa de rede e permite falar a primeira frase enquanto o resto ainda está sendo gerado. A rota antiga `/api/game/analyze` continua funcionando para o app 0.9.x.

Como a ZAYRO funciona:
- **Escuta automática:** um detector de fala (VAD) recorta cada fala do jogador; nada é gravado em disco e o áudio só é enviado quando há fala. O microfone é pausado enquanto ela fala ou pensa, para ela não se ouvir. Fones de ouvido ajudam muito, porque o som do jogo no alto-falante também chega ao microfone.
- **Fala espontânea:** a cada ~30–50 s (modo normal) ou ~9–19 s (zueira) ela olha a tela e comenta, se houver algo novo. Dá para desligar no Game Hub ("ZAYRO fala sozinha").
- **Pedidos por voz:** "ZAYRO, fica quieta por 10 minutos" faz ela parar de falar sozinha pelo tempo pedido.
- **Painel:** tocar no nome = falar agora (interrompe a fala); 🎙 liga/desliga a escuta; 😈 liga/desliga a zueira; ✕ encerra. Os mesmos controles de mic e encerrar existem na notificação.
- **Voz:** o Game Hub tem "Trocar voz da ZAYRO" para experimentar as vozes pt-BR instaladas (instale vozes de alta qualidade em Configurações > Texto para voz > Google).

Privacidade: com a ZAYRO ativa o microfone fica aberto e a tela inteira é capturada; a fala detectada e as imagens da tela são enviadas ao Gemini pelo seu Worker. No plano gratuito o Google pode usar esses dados. Evite abrir conversas ou dados pessoais com ela ativa e use o mic mudo quando quiser.

Cota: no modo zueira são muitas chamadas por hora. Se a cota gratuita do Gemini acabar o painel avisa ("cota do Gemini") e ela fica quieta por 90 s antes de tentar de novo.

## Versão 0.10.0 — ZAYRO

- Nova assistente **ZAYRO** (nome na interface, nas notificações e no prompt).
- **Escuta automática** por VAD (sem botão "falar") com mic mudo no painel/notificação e "tocar para falar".
- **Fala espontânea** e modo zueira bem mais debochado, com ritmo próprio e sem repetir piadas (memória da conversa).
- **Mais rápido:** áudio + tela numa só chamada ao Gemini, resposta em streaming com fala frase a frase, imagem de 1024 px, conexão aquecida ao iniciar.
- **Painel mínimo** (pílula com estado e três botões), sem mostrar o texto falado.
- **Voz mais natural:** motor do Google quando instalado, melhor voz pt-BR, ducking do áudio do jogo e seletor de voz no Game Hub.
- Worker v2 (`/api/zuleide/turn`, `/api/zuleide/version`) com testes em Node; o app avisa quando o Worker publicado é antigo.

## Versão 0.9.0 — auditoria e correções

- **Crash ao abrir no Android 8–12:** `PackageManager.ApplicationInfoFlags` só existe no Android 13+. A listagem de jogos agora usa `queryIntentActivities` com verificação de versão.
- **Visão da IA nunca funcionava:** o JPEG era entregue a `InputImage.fromByteArray(..., JPEG)`, que só aceita NV21/YV12 e lançava exceção. O quadro agora é convertido corretamente (respeitando o `rowStride`) e o OCR usa `InputImage.fromBitmap`.
- **IA sem imagem em tela parada:** a captura só gera quadros quando a tela muda e o app descartava imagens com mais de 5 s. Agora o quadro mais recente é retido e sempre enviado.
- **Travamentos:** conversão de imagem pixel a pixel na thread principal (que também desenha o painel). Agora roda numa thread dedicada.
- **Rotação de tela:** a captura é redimensionada quando o jogo gira (Android < 14 não tem callback; Android 14+ usa `onCapturedContentResize`).
- **Segurança:** o broadcast com caminho de arquivo (exportado em Android < 13) foi substituído por uma ponte em memória; o quadro não fica mais gravado em disco.
- **Build:** adicionados Gradle Wrapper e keystore estável (`assembleDebug` falhava sem o keystore gerado pelo CI); removidas dependências não usadas (AppCompat, Material, core-ktx, activity-ktx).
- **Interface:** tema escuro explícito (o tema DayNight podia deixar o texto do seletor de jogos escuro sobre fundo escuro com o aparelho em modo claro), suporte a edge-to-edge do Android 15, painel flutuante em dp (antes em pixels, minúsculo em telas densas), arrasto vertical corrigido (estava invertido), ícone do app e ícone de notificação.
- **Permissões:** microfone e notificações (Android 13+) pedidos juntos; retomada automática depois de autorizar a sobreposição; atalho para as configurações quando o microfone foi negado em definitivo.
- **Estado:** modo trolagem e último jogo persistem (e sobrevivem à rotação); a seleção do jogo não é mais perdida ao voltar para o Hub; o modo pode ser alterado durante a partida, pelo painel.
- **Serviços:** serviço de captura não fica mais órfão se iniciado sem autorização; ativar o companheiro duas vezes não vaza `MediaProjection`; "ENCERRAR COMPANHEIRO IA" no Hub e ação "Encerrar" na notificação.
- **Voz:** resposta enfileirada até o TTS ficar pronto (antes era perdida), aviso quando não há voz pt-BR instalada, `AssistantEngine` ligado como plano B offline e correção de índice negativo quando `hashCode() == Int.MIN_VALUE`.
