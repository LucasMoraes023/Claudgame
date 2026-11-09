# CLAUDGAME

**ClaudGame** é o projeto Android de **Game Hub + Game Booster + AI Game Companion**.

O projeto Python/web original foi preservado em `app/` e `frontend/`. O produto principal agora está sendo construído em Android nativo (Kotlin) dentro de `android/`.

## Objetivo

- 🎮 Biblioteca de jogos instalados e acesso rápido.
- ⚡ Modo de jogo usando somente recursos que o Android realmente permite controlar.
- 👁️ Captura de tela com **MediaProjection**, sempre mediante autorização do usuário.
- 🧠 Pipeline de IA preparado para analisar eventos relevantes em vez de enviar todos os frames continuamente.
- 🗣️ Voz em português usando o TTS nativo do Android.
- 🎙️ Conversa natural com o jogador.
- 😈 **Modo Trolagem**, com intensidade configurável e respostas contextuais.
- 🪟 Assistente flutuante sobre o jogo.
- 🔐 Nenhuma chave de API deve ser embutida no APK.

## Fundação já implementada

- Projeto Gradle/Kotlin nativo.
- Tela inicial ClaudGame.
- Detecção de aplicativos instalados que podem ser iniciados.
- Seleção e abertura de jogo.
- Fluxo de autorização de sobreposição.
- Fluxo de autorização MediaProjection.
- Serviço foreground para captura.
- Aquisição de frames com ImageReader.
- TTS inicial em português.
- Modo Trolagem como configuração local.
- GitHub Actions para gerar APK debug.

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
5. Backend de IA com configuração segura.
6. Reconhecimento de fala + diálogo natural.
7. Modo Trolagem 0–100 com cooldown e contexto.
8. Histórico de sessões e preferências.
9. Testes Android e tratamento completo de permissões.
10. Build release assinada e APK final.

## Build

Workflow: `.github/workflows/android-apk.yml`

Saída:
`android/app/build/outputs/apk/debug/app-debug.apk`

Tecnologias:
- Kotlin 2.0.21
- Android Gradle Plugin 8.7.3
- Gradle 8.9
- Java 17
- minSdk 26
- targetSdk 35
